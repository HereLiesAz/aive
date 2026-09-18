# Haive BITCOS runtime

This directory contains Haive's experimental BITCOS support for ternary model weights.

BITCOS ("BITmap + COmpacted Signs") stores:

- one presence bit for every ternary weight;
- one compacted sign bit only for every non-zero weight, in tensor order.

For zero density `z`, the symbol payload is therefore `2 - z` bits per weight. The implementation follows the decoding semantics described in *Breaking the 1.58-bit Barrier for Ternary LLMs* (Georganas, Heinecke, Dubey, 2026).

## Scope

Implemented here:

- lossless `{-1,0,+1}` pack/unpack;
- direct dot products against packed BITCOS payloads without materializing a persistent 2-bit tensor;
- a versioned multi-tensor `HBCS` container;
- fp16 scale side-channel storage;
- scalar fallback valid on every Rust target used by Haive;
- x86 BMI2 `PDEP` sign placement, selected automatically when available;
- runtime capability tiers for scalar, AArch64 NEON-capable, BMI2, AVX2+BMI2, and AVX-512+BMI2 hosts;
- caller-owned C ABI for native host integration;
- `haive-bitcos` pack / inspect / unpack CLI.

Not yet claimed by this crate:

- the paper's fused AVX-512 FP16 matvec microkernel;
- the paper's AVX2 VNNI INT8 contraction microkernel;
- Intel Xe2/XeTLA kernels;
- a released BitNet/Bonsai/CAT-Q/TriLM/Maple model artifact;
- tokenizer / transformer execution.

Those remain separate runtime work. Haive will not relabel ordinary Qwen ONNX weights as ternary or BITCOS.

## HBCS v1 container

All integers are little-endian.

Header (32 bytes):

| Field | Type |
| --- | --- |
| magic | 4 bytes: `HBCS` |
| version | u16 |
| flags | u16 |
| tensor count | u32 |
| directory byte length | u32 |
| directory offset | u64 |
| data offset | u64 |

Each tensor directory entry contains:

- UTF-8 name and shape;
- group size;
- scale bit width (0 or 16);
- element and non-zero counts;
- absolute offsets/lengths for presence bitmap, compact signs, and scales.

Presence and sign bits are LSB-first. A sign bit of `1` means negative. This makes the compact sign stream directly compatible with the paper's `PDEP` reconstruction semantics.

## Converter

A manifest names raw signed-i8 ternary tensor files:

```json
{
  "tensors": [
    {
      "name": "model.layers.0.mlp.down_proj.weight",
      "shape": [2048, 5632],
      "group_size": 128,
      "weights_i8": "down_proj.i8",
      "scales_f16_le": "down_proj.scales-f16le"
    }
  ]
}
```

Each raw weight byte must be signed i8 `-1`, `0`, or `+1` (bytes `255`, `0`, `1`).

```bash
cargo run --manifest-path native/bitcos/Cargo.toml -- \
  pack manifest.json model.bitcos

cargo run --manifest-path native/bitcos/Cargo.toml -- \
  inspect model.bitcos

cargo run --manifest-path native/bitcos/Cargo.toml -- \
  unpack model.bitcos unpacked/
```

The converter does not quantize FP16/INT8 models into ternary weights. It only repacks an already-ternary checkpoint losslessly.
