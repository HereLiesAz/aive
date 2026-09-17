#[cfg(not(target_arch = "wasm32"))]
mod jni_bridge;

use std::fmt;

pub const BITCOS_MAGIC: [u8; 4] = *b"HBCS";
pub const BITCOS_VERSION: u16 = 1;
const HEADER_LEN: usize = 32;
const DIRECTORY_FIXED_LEN: usize = 76;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BitcosError {
    InvalidTernary(i8),
    ShapeMismatch { expected: u64, actual: usize },
    InvalidContainer(&'static str),
    InvalidUtf8,
    BufferTooSmall,
}

impl fmt::Display for BitcosError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidTernary(value) => write!(f, "invalid ternary value {value}; expected -1, 0, or +1"),
            Self::ShapeMismatch { expected, actual } => {
                write!(f, "tensor shape contains {expected} elements but input has {actual}")
            }
            Self::InvalidContainer(message) => write!(f, "invalid BITCOS container: {message}"),
            Self::InvalidUtf8 => write!(f, "invalid UTF-8 tensor name"),
            Self::BufferTooSmall => write!(f, "output buffer is too small"),
        }
    }
}

impl std::error::Error for BitcosError {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BitcosPayload {
    pub element_count: u64,
    pub nonzero_count: u64,
    pub presence: Vec<u8>,
    pub signs: Vec<u8>,
}

impl BitcosPayload {
    pub fn pack(values: &[i8]) -> Result<Self, BitcosError> {
        let mut presence = vec![0u8; values.len().div_ceil(8)];
        let mut signs = Vec::<u8>::new();
        let mut nonzero_count = 0u64;

        for (index, &value) in values.iter().enumerate() {
            if !matches!(value, -1 | 0 | 1) {
                return Err(BitcosError::InvalidTernary(value));
            }
            if value == 0 {
                continue;
            }

            set_bit(&mut presence, index as u64, true);
            let sign_index = nonzero_count;
            nonzero_count += 1;
            let required = (nonzero_count as usize).div_ceil(8);
            if signs.len() < required {
                signs.resize(required, 0);
            }
            set_bit(&mut signs, sign_index, value < 0);
        }

        Ok(Self {
            element_count: values.len() as u64,
            nonzero_count,
            presence,
            signs,
        })
    }

    pub fn symbol_bits(&self) -> u64 {
        self.element_count + self.nonzero_count
    }

    pub fn effective_bits_per_weight(&self) -> f64 {
        if self.element_count == 0 {
            return 0.0;
        }
        self.symbol_bits() as f64 / self.element_count as f64
    }

    pub fn zero_density(&self) -> f64 {
        if self.element_count == 0 {
            return 0.0;
        }
        1.0 - (self.nonzero_count as f64 / self.element_count as f64)
    }

    pub fn decode(&self) -> Result<Vec<i8>, BitcosError> {
        decode_payload(
            self.element_count,
            &self.presence,
            &self.signs,
        )
    }

    pub fn dot_i8(&self, activations: &[i8]) -> Result<i64, BitcosError> {
        if activations.len() != self.element_count as usize {
            return Err(BitcosError::ShapeMismatch {
                expected: self.element_count,
                actual: activations.len(),
            });
        }
        dot_i8_payload(self.element_count, &self.presence, &self.signs, activations)
    }
}

#[derive(Debug, Clone)]
pub struct BitcosTensorInput {
    pub name: String,
    pub shape: Vec<u64>,
    pub group_size: u32,
    pub weights: Vec<i8>,
    /// Raw IEEE-754 binary16 scale bytes in little-endian order.
    pub scales_f16_le: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BitcosTensorMetadata {
    pub name: String,
    pub shape: Vec<u64>,
    pub group_size: u32,
    pub scale_bits: u16,
    pub element_count: u64,
    pub nonzero_count: u64,
}

#[derive(Debug, Clone)]
pub struct BitcosTensorView<'a> {
    pub metadata: BitcosTensorMetadata,
    pub presence: &'a [u8],
    pub signs: &'a [u8],
    pub scales: &'a [u8],
}

impl<'a> BitcosTensorView<'a> {
    pub fn decode(&self) -> Result<Vec<i8>, BitcosError> {
        decode_payload(
            self.metadata.element_count,
            self.presence,
            self.signs,
        )
    }

    pub fn dot_i8(&self, activations: &[i8]) -> Result<i64, BitcosError> {
        if activations.len() != self.metadata.element_count as usize {
            return Err(BitcosError::ShapeMismatch {
                expected: self.metadata.element_count,
                actual: activations.len(),
            });
        }
        dot_i8_payload(
            self.metadata.element_count,
            self.presence,
            self.signs,
            activations,
        )
    }

    pub fn effective_bits_per_weight(&self) -> f64 {
        if self.metadata.element_count == 0 {
            0.0
        } else {
            (self.metadata.element_count + self.metadata.nonzero_count) as f64
                / self.metadata.element_count as f64
        }
    }

    pub fn zero_density(&self) -> f64 {
        if self.metadata.element_count == 0 {
            0.0
        } else {
            1.0 - self.metadata.nonzero_count as f64 / self.metadata.element_count as f64
        }
    }
}

#[derive(Debug)]
pub struct BitcosContainer<'a> {
    pub tensors: Vec<BitcosTensorView<'a>>,
}

impl<'a> BitcosContainer<'a> {
    pub fn parse(bytes: &'a [u8]) -> Result<Self, BitcosError> {
        if bytes.len() < HEADER_LEN {
            return Err(BitcosError::InvalidContainer("header is truncated"));
        }
        if bytes[0..4] != BITCOS_MAGIC {
            return Err(BitcosError::InvalidContainer("magic must be HBCS"));
        }

        let version = read_u16(bytes, 4)?;
        if version != BITCOS_VERSION {
            return Err(BitcosError::InvalidContainer("unsupported version"));
        }
        let tensor_count = read_u32(bytes, 8)? as usize;
        let directory_len = read_u32(bytes, 12)? as usize;
        let directory_offset = read_u64(bytes, 16)? as usize;
        let data_offset = read_u64(bytes, 24)? as usize;

        if directory_offset != HEADER_LEN {
            return Err(BitcosError::InvalidContainer("unexpected directory offset"));
        }
        let directory_end = directory_offset
            .checked_add(directory_len)
            .ok_or(BitcosError::InvalidContainer("directory length overflow"))?;
        if directory_end > bytes.len() || data_offset < directory_end || data_offset > bytes.len() {
            return Err(BitcosError::InvalidContainer("directory/data range is invalid"));
        }

        let mut cursor = directory_offset;
        let mut tensors = Vec::with_capacity(tensor_count);
        for _ in 0..tensor_count {
            if cursor + DIRECTORY_FIXED_LEN > directory_end {
                return Err(BitcosError::InvalidContainer("tensor directory entry is truncated"));
            }

            let name_len = read_u16(bytes, cursor)? as usize;
            let rank = read_u16(bytes, cursor + 2)? as usize;
            let group_size = read_u32(bytes, cursor + 4)?;
            let scale_bits = read_u16(bytes, cursor + 8)?;
            if !matches!(scale_bits, 0 | 16) {
                return Err(BitcosError::InvalidContainer("only absent or fp16 scales are supported"));
            }
            let element_count = read_u64(bytes, cursor + 12)?;
            let nonzero_count = read_u64(bytes, cursor + 20)?;
            if nonzero_count > element_count {
                return Err(BitcosError::InvalidContainer("nonzero count exceeds element count"));
            }
            let presence_offset = read_u64(bytes, cursor + 28)? as usize;
            let presence_len = read_u64(bytes, cursor + 36)? as usize;
            let signs_offset = read_u64(bytes, cursor + 44)? as usize;
            let signs_len = read_u64(bytes, cursor + 52)? as usize;
            let scales_offset = read_u64(bytes, cursor + 60)? as usize;
            let scales_len = read_u64(bytes, cursor + 68)? as usize;

            cursor += DIRECTORY_FIXED_LEN;
            let dims_len = rank
                .checked_mul(8)
                .ok_or(BitcosError::InvalidContainer("rank overflow"))?;
            if cursor + dims_len + name_len > directory_end {
                return Err(BitcosError::InvalidContainer("tensor metadata exceeds directory"));
            }
            let mut shape = Vec::with_capacity(rank);
            for _ in 0..rank {
                shape.push(read_u64(bytes, cursor)?);
                cursor += 8;
            }
            let name_bytes = &bytes[cursor..cursor + name_len];
            cursor += name_len;
            let name = std::str::from_utf8(name_bytes)
                .map_err(|_| BitcosError::InvalidUtf8)?
                .to_owned();

            let expected_elements = checked_shape_product(&shape)?;
            if expected_elements != element_count {
                return Err(BitcosError::InvalidContainer("shape does not match element count"));
            }
            if presence_len != (element_count as usize).div_ceil(8) {
                return Err(BitcosError::InvalidContainer("presence length does not match element count"));
            }
            if signs_len != (nonzero_count as usize).div_ceil(8) {
                return Err(BitcosError::InvalidContainer("sign length does not match nonzero count"));
            }
            if scale_bits == 16 && scales_len % 2 != 0 {
                return Err(BitcosError::InvalidContainer("fp16 scale payload has odd byte length"));
            }

            let presence = checked_slice(bytes, presence_offset, presence_len, data_offset)?;
            let signs = checked_slice(bytes, signs_offset, signs_len, data_offset)?;
            let scales = checked_slice(bytes, scales_offset, scales_len, data_offset)?;

            tensors.push(BitcosTensorView {
                metadata: BitcosTensorMetadata {
                    name,
                    shape,
                    group_size,
                    scale_bits,
                    element_count,
                    nonzero_count,
                },
                presence,
                signs,
                scales,
            });
        }

        if cursor != directory_end {
            return Err(BitcosError::InvalidContainer("directory has trailing bytes"));
        }

        Ok(Self { tensors })
    }

    pub fn tensor(&self, name: &str) -> Option<&BitcosTensorView<'a>> {
        self.tensors.iter().find(|tensor| tensor.metadata.name == name)
    }
}

pub fn encode_container(tensors: &[BitcosTensorInput]) -> Result<Vec<u8>, BitcosError> {
    let mut packed = Vec::with_capacity(tensors.len());
    let mut directory_len = 0usize;

    for tensor in tensors {
        let element_count = checked_shape_product(&tensor.shape)?;
        if element_count != tensor.weights.len() as u64 {
            return Err(BitcosError::ShapeMismatch {
                expected: element_count,
                actual: tensor.weights.len(),
            });
        }
        if tensor.scales_f16_le.len() % 2 != 0 {
            return Err(BitcosError::InvalidContainer("fp16 scale payload has odd byte length"));
        }
        let payload = BitcosPayload::pack(&tensor.weights)?;
        directory_len = directory_len
            .checked_add(DIRECTORY_FIXED_LEN)
            .and_then(|value| value.checked_add(tensor.shape.len() * 8))
            .and_then(|value| value.checked_add(tensor.name.as_bytes().len()))
            .ok_or(BitcosError::InvalidContainer("directory length overflow"))?;
        packed.push((tensor, payload));
    }

    let data_offset = HEADER_LEN
        .checked_add(directory_len)
        .ok_or(BitcosError::InvalidContainer("data offset overflow"))?;
    let mut data_cursor = data_offset as u64;
    let mut directory = Vec::with_capacity(directory_len);
    let mut data = Vec::<u8>::new();

    for (tensor, payload) in &packed {
        let presence_offset = data_cursor;
        let presence_len = payload.presence.len() as u64;
        data.extend_from_slice(&payload.presence);
        data_cursor += presence_len;

        let signs_offset = data_cursor;
        let signs_len = payload.signs.len() as u64;
        data.extend_from_slice(&payload.signs);
        data_cursor += signs_len;

        let scales_offset = data_cursor;
        let scales_len = tensor.scales_f16_le.len() as u64;
        data.extend_from_slice(&tensor.scales_f16_le);
        data_cursor += scales_len;

        write_u16(&mut directory, tensor.name.as_bytes().len() as u16);
        write_u16(&mut directory, tensor.shape.len() as u16);
        write_u32(&mut directory, tensor.group_size);
        write_u16(
            &mut directory,
            if tensor.scales_f16_le.is_empty() { 0 } else { 16 },
        );
        write_u16(&mut directory, 0);
        write_u64(&mut directory, payload.element_count);
        write_u64(&mut directory, payload.nonzero_count);
        write_u64(&mut directory, presence_offset);
        write_u64(&mut directory, presence_len);
        write_u64(&mut directory, signs_offset);
        write_u64(&mut directory, signs_len);
        write_u64(&mut directory, scales_offset);
        write_u64(&mut directory, scales_len);
        tensor.shape.iter().for_each(|&dim| write_u64(&mut directory, dim));
        directory.extend_from_slice(tensor.name.as_bytes());
    }

    if directory.len() != directory_len {
        return Err(BitcosError::InvalidContainer("internal directory size mismatch"));
    }

    let mut output = Vec::with_capacity(data_offset + data.len());
    output.extend_from_slice(&BITCOS_MAGIC);
    write_u16(&mut output, BITCOS_VERSION);
    write_u16(&mut output, 0);
    write_u32(&mut output, tensors.len() as u32);
    write_u32(&mut output, directory_len as u32);
    write_u64(&mut output, HEADER_LEN as u64);
    write_u64(&mut output, data_offset as u64);
    output.extend_from_slice(&directory);
    output.extend_from_slice(&data);
    Ok(output)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum DecodeBackend {
    Scalar = 0,
    Neon = 1,
    Bmi2 = 2,
    Avx2Bmi2 = 3,
    Avx512Bmi2 = 4,
}

pub fn best_decode_backend() -> DecodeBackend {
    #[cfg(target_arch = "x86_64")]
    {
        if std::arch::is_x86_feature_detected!("bmi2") {
            if std::arch::is_x86_feature_detected!("avx512f") {
                return DecodeBackend::Avx512Bmi2;
            }
            if std::arch::is_x86_feature_detected!("avx2") {
                return DecodeBackend::Avx2Bmi2;
            }
            return DecodeBackend::Bmi2;
        }
    }

    #[cfg(target_arch = "aarch64")]
    {
        if std::arch::is_aarch64_feature_detected!("neon") {
            return DecodeBackend::Neon;
        }
    }

    DecodeBackend::Scalar
}

pub fn decode_payload(
    element_count: u64,
    presence: &[u8],
    signs: &[u8],
) -> Result<Vec<i8>, BitcosError> {
    validate_payload(element_count, presence, signs)?;
    let mut output = vec![0i8; element_count as usize];
    decode_payload_into(element_count, presence, signs, &mut output)?;
    Ok(output)
}

pub fn decode_payload_into(
    element_count: u64,
    presence: &[u8],
    signs: &[u8],
    output: &mut [i8],
) -> Result<(), BitcosError> {
    validate_payload(element_count, presence, signs)?;
    if output.len() < element_count as usize {
        return Err(BitcosError::BufferTooSmall);
    }

    #[cfg(target_arch = "x86_64")]
    if std::arch::is_x86_feature_detected!("bmi2") {
        // SAFETY: guarded by runtime BMI2 feature detection; slice bounds are validated.
        unsafe {
            return decode_payload_bmi2(element_count, presence, signs, output);
        }
    }

    decode_payload_scalar(element_count, presence, signs, output)
}

pub fn dot_i8_payload(
    element_count: u64,
    presence: &[u8],
    signs: &[u8],
    activations: &[i8],
) -> Result<i64, BitcosError> {
    validate_payload(element_count, presence, signs)?;
    if activations.len() != element_count as usize {
        return Err(BitcosError::ShapeMismatch {
            expected: element_count,
            actual: activations.len(),
        });
    }

    let mut sign_index = 0u64;
    let mut accumulator = 0i64;
    for index in 0..element_count {
        if !get_bit(presence, index) {
            continue;
        }
        let value = activations[index as usize] as i64;
        if get_bit(signs, sign_index) {
            accumulator -= value;
        } else {
            accumulator += value;
        }
        sign_index += 1;
    }
    Ok(accumulator)
}

fn decode_payload_scalar(
    element_count: u64,
    presence: &[u8],
    signs: &[u8],
    output: &mut [i8],
) -> Result<(), BitcosError> {
    let mut sign_index = 0u64;
    for index in 0..element_count {
        if !get_bit(presence, index) {
            output[index as usize] = 0;
            continue;
        }
        output[index as usize] = if get_bit(signs, sign_index) { -1 } else { 1 };
        sign_index += 1;
    }
    Ok(())
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "bmi2")]
unsafe fn decode_payload_bmi2(
    element_count: u64,
    presence: &[u8],
    signs: &[u8],
    output: &mut [i8],
) -> Result<(), BitcosError> {
    use std::arch::x86_64::_pdep_u64;

    let mut base = 0u64;
    let mut sign_index = 0u64;
    while base < element_count {
        let lanes = (element_count - base).min(64) as usize;
        let mut presence_word = read_bits_u64(presence, base);
        if lanes < 64 {
            presence_word &= (1u64 << lanes) - 1;
        }
        let compact_signs = read_bits_u64(signs, sign_index);
        let negative_mask = _pdep_u64(compact_signs, presence_word);

        for lane in 0..lanes {
            let mask = 1u64 << lane;
            output[(base as usize) + lane] = if presence_word & mask == 0 {
                0
            } else if negative_mask & mask != 0 {
                -1
            } else {
                1
            };
        }

        sign_index += presence_word.count_ones() as u64;
        base += lanes as u64;
    }
    Ok(())
}

fn validate_payload(
    element_count: u64,
    presence: &[u8],
    signs: &[u8],
) -> Result<(), BitcosError> {
    let expected_presence = (element_count as usize).div_ceil(8);
    if presence.len() != expected_presence {
        return Err(BitcosError::InvalidContainer("presence length mismatch"));
    }
    let nonzero = count_bits(presence, element_count);
    let expected_signs = (nonzero as usize).div_ceil(8);
    if signs.len() != expected_signs {
        return Err(BitcosError::InvalidContainer("sign length mismatch"));
    }
    Ok(())
}

fn count_bits(bytes: &[u8], valid_bits: u64) -> u64 {
    if valid_bits == 0 {
        return 0;
    }
    let full_bytes = (valid_bits / 8) as usize;
    let remainder = (valid_bits % 8) as u32;
    let mut count: u64 = bytes
        .iter()
        .take(full_bytes)
        .map(|byte| byte.count_ones() as u64)
        .sum();
    if remainder > 0 && full_bytes < bytes.len() {
        let mask = ((1u16 << remainder) - 1) as u8;
        count += (bytes[full_bytes] & mask).count_ones() as u64;
    }
    count
}

fn get_bit(bytes: &[u8], index: u64) -> bool {
    let byte_index = (index / 8) as usize;
    let bit_index = (index % 8) as u32;
    byte_index < bytes.len() && ((bytes[byte_index] >> bit_index) & 1) != 0
}

fn set_bit(bytes: &mut [u8], index: u64, value: bool) {
    if !value {
        return;
    }
    let byte_index = (index / 8) as usize;
    let bit_index = (index % 8) as u32;
    bytes[byte_index] |= 1u8 << bit_index;
}

fn read_bits_u64(bytes: &[u8], bit_offset: u64) -> u64 {
    let byte_offset = (bit_offset / 8) as usize;
    let shift = (bit_offset % 8) as u32;
    let mut window = 0u128;
    for index in 0..9 {
        if let Some(&byte) = bytes.get(byte_offset + index) {
            window |= (byte as u128) << (index * 8);
        }
    }
    (window >> shift) as u64
}

fn checked_shape_product(shape: &[u64]) -> Result<u64, BitcosError> {
    if shape.is_empty() {
        return Err(BitcosError::InvalidContainer("tensor rank must be at least one"));
    }
    shape.iter().try_fold(1u64, |product, &dim| {
        if dim == 0 {
            return Err(BitcosError::InvalidContainer("tensor dimensions must be non-zero"));
        }
        product
            .checked_mul(dim)
            .ok_or(BitcosError::InvalidContainer("tensor shape overflow"))
    })
}

fn checked_slice<'a>(
    bytes: &'a [u8],
    offset: usize,
    len: usize,
    minimum_offset: usize,
) -> Result<&'a [u8], BitcosError> {
    if offset < minimum_offset {
        return Err(BitcosError::InvalidContainer("tensor payload overlaps metadata"));
    }
    let end = offset
        .checked_add(len)
        .ok_or(BitcosError::InvalidContainer("tensor payload length overflow"))?;
    bytes
        .get(offset..end)
        .ok_or(BitcosError::InvalidContainer("tensor payload is truncated"))
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, BitcosError> {
    let raw: [u8; 2] = bytes
        .get(offset..offset + 2)
        .ok_or(BitcosError::InvalidContainer("u16 is truncated"))?
        .try_into()
        .map_err(|_| BitcosError::InvalidContainer("u16 is truncated"))?;
    Ok(u16::from_le_bytes(raw))
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, BitcosError> {
    let raw: [u8; 4] = bytes
        .get(offset..offset + 4)
        .ok_or(BitcosError::InvalidContainer("u32 is truncated"))?
        .try_into()
        .map_err(|_| BitcosError::InvalidContainer("u32 is truncated"))?;
    Ok(u32::from_le_bytes(raw))
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, BitcosError> {
    let raw: [u8; 8] = bytes
        .get(offset..offset + 8)
        .ok_or(BitcosError::InvalidContainer("u64 is truncated"))?
        .try_into()
        .map_err(|_| BitcosError::InvalidContainer("u64 is truncated"))?;
    Ok(u64::from_le_bytes(raw))
}

fn write_u16(target: &mut Vec<u8>, value: u16) {
    target.extend_from_slice(&value.to_le_bytes());
}

fn write_u32(target: &mut Vec<u8>, value: u32) {
    target.extend_from_slice(&value.to_le_bytes());
}

fn write_u64(target: &mut Vec<u8>, value: u64) {
    target.extend_from_slice(&value.to_le_bytes());
}

#[no_mangle]
pub extern "C" fn haive_bitcos_abi_version() -> u32 {
    BITCOS_VERSION as u32
}

#[no_mangle]
pub extern "C" fn haive_bitcos_best_decode_backend() -> u32 {
    best_decode_backend() as u32
}

/// Caller-owned C ABI for native hosts. Returns 0 on success and a negative code on invalid input.
#[no_mangle]
pub unsafe extern "C" fn haive_bitcos_decode_payload(
    element_count: u64,
    presence_ptr: *const u8,
    presence_len: usize,
    signs_ptr: *const u8,
    signs_len: usize,
    output_ptr: *mut i8,
    output_len: usize,
) -> i32 {
    if (presence_len > 0 && presence_ptr.is_null())
        || (signs_len > 0 && signs_ptr.is_null())
        || (output_len > 0 && output_ptr.is_null())
    {
        return -1;
    }

    let presence = std::slice::from_raw_parts(presence_ptr, presence_len);
    let signs = std::slice::from_raw_parts(signs_ptr, signs_len);
    let output = std::slice::from_raw_parts_mut(output_ptr, output_len);
    match decode_payload_into(element_count, presence, signs, output) {
        Ok(()) => 0,
        Err(BitcosError::BufferTooSmall) => -2,
        Err(_) => -3,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paper_column_encodes_presence_and_compacted_signs_in_order() {
        let values = [1, 1, 1, 1, -1, 0, -1, 1];
        let payload = BitcosPayload::pack(&values).unwrap();

        assert_eq!(payload.presence, vec![0xDF]);
        assert_eq!(payload.signs, vec![0x30]);
        assert_eq!(payload.nonzero_count, 7);
        assert_eq!(payload.decode().unwrap(), values);
    }

    #[test]
    fn payload_round_trip_and_direct_dot_product() {
        let weights = [0, -1, 1, 0, 1, -1, 1, 0, 0, -1];
        let activations = [4, 3, -2, 9, 5, 8, -1, 7, 6, 2];
        let payload = BitcosPayload::pack(&weights).unwrap();

        assert_eq!(payload.decode().unwrap(), weights);
        let expected: i64 = weights
            .iter()
            .zip(activations)
            .map(|(&weight, activation)| weight as i64 * activation as i64)
            .sum();
        assert_eq!(payload.dot_i8(&activations).unwrap(), expected);
        assert_eq!(payload.symbol_bits(), 10 + 6);
        assert!((payload.effective_bits_per_weight() - 1.6).abs() < 1e-9);
    }

    #[test]
    fn container_round_trip_preserves_tensor_metadata_and_payloads() {
        let first = BitcosTensorInput {
            name: "layer.0.weight".into(),
            shape: vec![2, 4],
            group_size: 4,
            weights: vec![1, 0, -1, 1, 0, 0, 1, -1],
            scales_f16_le: vec![0x00, 0x3c, 0x00, 0x40],
        };
        let second = BitcosTensorInput {
            name: "layer.1.weight".into(),
            shape: vec![4],
            group_size: 4,
            weights: vec![-1, -1, 0, 1],
            scales_f16_le: vec![],
        };

        let encoded = encode_container(&[first.clone(), second.clone()]).unwrap();
        let parsed = BitcosContainer::parse(&encoded).unwrap();

        assert_eq!(parsed.tensors.len(), 2);
        let layer0 = parsed.tensor("layer.0.weight").unwrap();
        assert_eq!(layer0.metadata.shape, first.shape);
        assert_eq!(layer0.metadata.group_size, 4);
        assert_eq!(layer0.metadata.scale_bits, 16);
        assert_eq!(layer0.scales, first.scales_f16_le);
        assert_eq!(layer0.decode().unwrap(), first.weights);

        let layer1 = parsed.tensor("layer.1.weight").unwrap();
        assert_eq!(layer1.metadata.scale_bits, 0);
        assert_eq!(layer1.decode().unwrap(), second.weights);
    }

    #[test]
    fn malformed_ternary_input_is_rejected() {
        assert_eq!(
            BitcosPayload::pack(&[0, 1, 2]).unwrap_err(),
            BitcosError::InvalidTernary(2),
        );
    }

    #[test]
    fn backend_detection_always_returns_a_supported_tier() {
        assert!(matches!(
            best_decode_backend(),
            DecodeBackend::Scalar
                | DecodeBackend::Neon
                | DecodeBackend::Bmi2
                | DecodeBackend::Avx2Bmi2
                | DecodeBackend::Avx512Bmi2
        ));
    }
}
