# BITCOS ternary model runtime

The Aive treats BITCOS as an experimental weight encoding for genuinely ternary models, not as a generic compressor for FP16, INT8, GGUF, or ONNX checkpoints.

The implementation is based on Intel researchers Evangelos Georganas, Alexander Heinecke, and Pradeep Dubey, *Breaking the 1.58-bit Barrier for Ternary LLMs* (arXiv:2609.16338, 2026).

## Semantics

For a ternary tensor whose weights are in `{-1, 0, +1}`, BITCOS stores:

1. a dense presence bitmap with one bit per element, set when the weight is non-zero;
2. a compacted sign stream with one bit per non-zero element, in tensor order.

The Aive uses `1 = negative` for sign bits and LSB-first bit numbering. If a tensor has zero density `z`, the symbol payload is `2 - z` bits per weight before scale and container metadata.

This layout is lossless for already-ternary weights. The Aive does not convert ordinary Qwen/ONNX specialists to BITCOS by relabeling or compressing their bytes.

## Runtime architecture

```text
verified ternary checkpoint
        |
        v
haive-bitcos pack
        |
        v
HBCS v1 container
  - tensor directory
  - presence bitmaps
  - compact sign streams
  - optional fp16 scales
        |
        v
native/bitcos Rust runtime
  - validation
  - direct packed dot product
  - scalar portable decode
  - x86 BMI2/PDEP sign placement
        |
        +--> JNI --> Android
        |
        +--> JNI/resource extraction --> Desktop
        |
        v
LocalModelRuntimeCapabilities
  supportedWeightEncodings += bitcos-v1
  supportedPrecisions += ternary
        |
        v
LocalModelLibrary.plan(...)
```

The local-model planner requires explicit `bitcos-v1` support. A runtime that merely supports a model family or ternary precision cannot accidentally select a BITCOS artifact.

## HBCS v1

`native/bitcos` defines The Aive's transport container for BITCOS tensors. It is intentionally small and independent of any one transformer architecture.

The fixed header contains:

- magic `HBCS`;
- version;
- tensor count;
- directory length;
- directory and data offsets.

Each tensor directory entry records:

- UTF-8 tensor name;
- dimensions;
- group size;
- element count;
- non-zero count;
- scale width;
- absolute payload ranges for presence, signs, and scales.

Scales are currently absent or raw little-endian IEEE binary16. Architecture/tokenizer metadata remains owned by the model-family manifest rather than duplicated into HBCS.

## Converter

The `haive-bitcos` CLI takes a JSON manifest pointing to already-ternary signed-i8 tensor files and optional raw fp16 scale files.

It supports:

- `pack`: create an HBCS container;
- `inspect`: print shape, zero density, non-zero count, and exact symbol bits/weight;
- `unpack`: reconstruct signed-i8 ternary tensors and scale sidecars.

Conversion rejects any weight not equal to `-1`, `0`, or `+1`.

## CPU execution

### Portable path

The scalar decoder and direct dot-product path are the correctness baseline and work on Android ARM and every desktop target. The direct dot product walks only present weights and performs addition/subtraction against int8 activations; it does not need to create a persistent 2-bit tensor.

This is the required Android fallback.

### x86 BMI2

On x86-64 with BMI2, the decoder uses `PDEP` to scatter compact sign bits back into positions selected by each presence word. This matches the central sign-reconstruction operation described in the BITCOS paper.

Runtime probing reports the available hardware tier. AVX2/AVX-512 availability is recorded alongside BMI2 so the next fused-kernel layer can dispatch without changing the container or planner contract.

### Fused kernels still to implement

The current code does **not** yet claim Intel-equivalent throughput. Remaining optimized execution work is:

- AVX-512 FP16 grouped-scale matvec using mask registers and `PDEP`;
- AVX2 + VNNI INT8 contraction with grouped activation quantization;
- AArch64 NEON/SVE materialization or direct dot kernels;
- Intel Xe2/XeTLA implementation;
- architecture-specific transformer operators and tokenizer integration.

These optimizations must compare bit-for-bit against the portable decoder and task-for-task against The Aive's existing local specialists before BITCOS becomes preferred automatically.

## Model-family compatibility

BITCOS is appropriate only when the underlying checkpoint is ternary. Candidate families include BitNet b1.58 and other ternary-native or ternary-quantized families whose actual released tensor semantics are known.

A released Aive BITCOS artifact must include:

- stable logical model identity;
- upstream/foundation identity;
- model-family format;
- `precision = ternary`;
- `weightEncoding = bitcos-v1`;
- SHA-256 release digest;
- tokenizer/config compatibility;
- group-size and scale semantics consistent with the source checkpoint.

Until such artifacts are published and benchmarked, existing ONNX INT8 memory specialists remain the production default.

## Acceptance gates before preferred production use

1. Lossless converter round-trip against source ternary tensors.
2. Portable decoder parity on Android ARM, Linux, Windows, and macOS.
3. Fused-kernel numerical parity against the portable path.
4. Task-accuracy parity or improvement on Aive specialist evaluations.
5. Measured startup, RAM, decode throughput, energy, and thermal behavior on physical devices.
6. Fallback to verified existing local artifacts whenever runtime capability or artifact compatibility is unproven.
