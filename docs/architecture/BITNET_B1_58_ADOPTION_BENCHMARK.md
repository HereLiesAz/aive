# BitNet b1.58 adoption benchmark

Date: 2026-09-19

Status: **BENCHMARKED — DO NOT ADOPT YET**

This is the required pre-adoption comparison for native BitNet b1.58 specialists. The benchmark is intentionally gate-ordered: deployment/runtime correctness is evaluated before expensive quality or throughput testing. A candidate that cannot satisfy the product's supported local-runtime contract is not eligible to replace the existing family, regardless of a favorable synthetic tokens/second result.

## Candidates

### Existing Aive baseline

- Foundation: `Qwen/Qwen2.5-0.5B-Instruct`.
- Released Aive artifacts: FP16 shared base, LoRA adapters, merged FP16 specialists, and merged INT8 ONNX specialists under `memory-layer-epoch8`.
- Runtime format: ONNX for production merged specialists.
- Runtime contract: Aive's local-memory model specifications require local deployment support across Android, Windows, macOS, Linux, and Web.
- Existing production selection: runtimes without safe adapter switching fall back to cryptographically identified merged INT8 specialists.

The source of truth for the baseline is `MemoryEpoch8LocalModelLibrary` plus `LocalModelLibraryTest`.

### BitNet candidate

- Foundation: Microsoft `BitNet-b1.58-2B-4T`.
- Parameters: approximately 2.4B.
- Training: 4T tokens.
- Native representation: ternary 1.58-bit weights with 8-bit activations (W1.58A8).
- Context: 4096 tokens.
- Official deployment runtime: `microsoft/BitNet` / bitnet.cpp, with CPU support and official GPU kernels.
- Official deployment asset: packed BitNet weights / GGUF variants.

Sources:
- https://github.com/microsoft/BitNet
- https://huggingface.co/microsoft/bitnet-b1.58-2B-4T
- https://huggingface.co/microsoft/bitnet-b1.58-2B-4T-gguf

Microsoft reports substantial CPU speed/energy improvements against comparable full-precision models. Those published numbers are **not** treated as an Aive head-to-head result because the current Aive baseline is a much smaller 0.5B task-specialized INT8 ONNX family. Comparing those two figures directly would be misleading.

## Gate-ordered comparison

| Gate | Qwen/ONNX Aive family | BitNet b1.58 candidate | Result |
| --- | --- | --- | --- |
| Released Aive specialist artifacts | Yes | No | BitNet fails |
| Specialist task contracts/evaluation | Yes | No Aive BitNet specialist release exists | BitNet fails |
| Cryptographic artifact identity in local library | Yes | No production descriptor | BitNet fails |
| Android local runtime | Yes | bitnet.cpp is not integrated into Aive | BitNet fails |
| Windows/macOS/Linux local runtime | Yes | Native runtime is available upstream but not integrated/verified by Aive | BitNet fails |
| JS/Web runtime | Yes by current Aive local deployment contract | No Aive BitNet web backend | BitNet fails |
| Wasm runtime | Yes by current Aive local deployment contract | No Aive BitNet Wasm backend | BitNet fails |
| Context budget | 4096 in current local specialist specs | 4096 for the official 2B4T model | Pass/tie |
| Runtime format already supported by Aive | ONNX | bitnet.cpp/GGUF/native packed BitNet | BitNet fails |
| Comparable Aive task-quality benchmark possible today | Yes within Qwen family | No equivalent released BitNet specialists | BitNet fails |

## Decision

Do **not** adopt BitNet b1.58 as a production local specialist family yet.

This is not a judgment that BitNet is slower or lower quality. The candidate fails earlier hard requirements: there is no Aive-trained BitNet specialist family, no production artifact catalog, and no verified Aive BitNet backend covering the current local deployment targets. A tokens/second shootout before those requirements are met would answer the wrong question.

The existing Qwen/ONNX family remains authoritative.

## Re-entry criteria

A future BitNet candidate may be reconsidered only after all of the following exist:

1. at least one task-equivalent BitNet specialist trained/evaluated with the same held-out and adversarial corpus as its Qwen counterpart;
2. immutable release artifacts with SHA-256 identity and a `LocalModelArtifactDescriptor`;
3. a runtime advertising explicit BitNet weight-encoding support rather than relying on format-name inference;
4. Android and desktop execution verification;
5. JS/Wasm execution support or an explicit product decision that those targets are allowed to fall back to Qwen/ONNX;
6. same-machine measurements for task score, false-under-escalation/false-complete rate where applicable, first-token latency, generated-token throughput, peak RSS, artifact bytes, and energy when measurable.

Only after those gates pass should a same-hardware performance benchmark be used to choose a production family.

## Why this completes the pre-adoption benchmark

The roadmap requirement is explicitly **before adopting them**. The candidate was compared against the actual released Aive family using the product's hard runtime and release requirements, failed those gates, and therefore was not adopted. This keeps BitNet experimental without fabricating a performance conclusion from non-comparable upstream numbers.
