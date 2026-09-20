# BitNet pre-adoption benchmark

This benchmark answers a narrow roadmap question before any BitNet family is allowed to replace the
published Epoch-8 Qwen/ONNX specialists.

The control is the published INT8 Epoch-8 NounTagger
(`Qwen/Qwen2.5-0.5B-Instruct`, merged ONNX specialist). The candidate is Microsoft's native
`bitnet-b1.58-2B-4T` GGUF executed with a pinned `microsoft/BitNet` / bitnet.cpp revision.

The admission workload uses the same bounded JSON-oriented NounTagger contract Aive expects from a
memory micro-agent. It records JSON validity, required node-kind compliance, explicit-term recall,
artifact size, cold startup/task timing, and the runner environment.

This is deliberately a **pre-adoption** benchmark. Even if the candidate reaches quality parity,
Aive must not select it automatically while the in-repo BITCOS runtime only provides ternary
container/decode primitives and does not execute the full tokenizer/transformer architecture.
Android energy, thermals, and sustained on-device throughput also remain separate physical-device
acceptance gates.

The GitHub Actions run uploads `bitnet-benchmark-report.json`. A measured rejection is a valid
benchmark result; the workflow fails only when the benchmark itself cannot execute.
