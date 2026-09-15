# Local memory inference

The Haive's memory clerks run locally and opportunistically use hardware acceleration. Acceleration is never a requirement for memory correctness: unsupported or failed accelerator sessions fall back to ONNX Runtime CPU unless the model explicitly disables CPU fallback.

## Clerical boundary

There are nine memory micro-agents, but two inference workloads:

- Eight structured clerks use autoregressive generation: Sectioner, SalienceFilter, NounTagger, VerbTagger, PhraseSynthesizer, SummarySynthesizer, CategoryClassifier, and CondensationRewriter.
- AssociationLinker uses embedding inference (MiniLM-class models) and cosine similarity.

AssociationLinker may emit `SimilarTo`/`AssociatedWith`. It must not infer contradiction, truth, falsity, or conflict resolution. There is no `ConflictResolver` memory clerk. Conscious reconciliation belongs to an ordinary orchestrated agent after recall and later returns to memory through normal session banking.

## Selection pipeline

```text
Memory micro-agent
  -> MemoryModelRequirements
  -> HardwareCapabilityDetector
  -> MemoryComputeSelector
  -> MemoryComputeDevice / execution provider
  -> cached platform ONNX Runtime session
  -> model-specific tensor/tokenizer adapter
```

`MemoryComputePreference` is `AUTO` by default:

- `AUTO`: embeddings prefer NPU then GPU; autoregressive generation prefers GPU then NPU; CPU remains fallback.
- `HIGH_PERFORMANCE`: GPU, then NPU, then CPU.
- `LOW_POWER`: NPU, then integrated GPU/CPU before discrete GPU.
- `CPU_ONLY`: never selects an accelerator.

A model can further constrain allowed device types, preferred execution providers, minimum dedicated memory, supported models, and whether CPU fallback is allowed.

## Platform policy

The policy is preference ordering, not a promise that a provider exists. The runtime first enumerates the execution providers/devices actually exposed by the installed ORT build and any registered plugin EP libraries.

| Platform | Preferred acceleration | Fallback |
| --- | --- | --- |
| Android | NNAPI, QNN, WebGPU when available | ORT CPU |
| Windows | WebGPU/DirectML when registered, otherwise CUDA when available | ORT CPU |
| macOS | CoreML/WebGPU when exposed by the installed runtime/plugin | ORT CPU |
| Linux | CUDA, then registered WebGPU/ROCm/MIGraphX providers | ORT CPU |
| Web | WebGPU | WASM |

Android disables NNAPI's CPU implementation on API 29+ so unsupported NNAPI work can fall back to ORT CPU rather than quietly presenting NNAPI CPU execution as accelerator work.

Native WebGPU remains optional. `DesktopOrtMemorySessionManager` and `AndroidOrtMemorySessionManager` accept execution-provider plugin libraries and then re-enumerate ORT EP/device tuples. This lets a compatible native WebGPU plugin be benchmarked against native platform/vendor providers instead of being hard-coded as the universal winner.

## Session lifetime and fallback

Sessions are cached by model, artifact path/URI, compute preference, workload, and provider options. Accelerator setup is attempted once. If provider registration or session creation fails and the model allows CPU fallback, the cache receives a CPU session instead; the fallback reason is recorded on the selected device metadata.

The trained artifact is resolved separately from hardware selection. Platform artifact resolvers map a deployment `artifactId` to a local path or browser URI. Model adapters own tokenizer/tensor details, which are necessarily artifact-specific and therefore remain outside the generic hardware scheduler.

## Reporting actual execution

`MemoryExecutionReport` deliberately distinguishes configured hardware from observed execution:

- `SessionConfigured`: an EP/device was selected and the session was created, but no claim is made that graph nodes actually executed there.
- `ProfiledRun`: a completed inference produced provider evidence.

Desktop and Android ORT sessions profile the first run and report the providers that actually executed graph nodes, whether CPU fallback occurred, and the accelerated-node fraction. Browser WebGPU profiling is used only as positive proof that GPU work occurred; if WebGPU was selected but no GPU profiling event is observed, the report stays conservative rather than claiming acceleration.

`isHardwareAccelerated` is true only when an observed non-CPU device appears in `actualDevices`. Merely owning a GPU is insufficient.

## Artifact integration

When trained artifacts land:

1. Give each model a `MemoryMicroAgentDeploymentManifest` and `MemoryModelRequirements`.
2. Use a generative model adapter for the eight Qwen-style clerks.
3. Use an embedding model adapter for the MiniLM AssociationLinker.
4. Supply platform artifact resolvers with the installed model paths/URIs.
5. Construct the platform session manager with the user's `MemoryComputePreference` and, where desired, native EP plugin libraries.
6. Expose `executionReport(modelId)` in diagnostics so UI/debug output reflects observed execution rather than hardware presence.

The generic scheduler, provider selection, fallback, session cache, and reporting do not depend on the final tokenizer, tensor names, or trained weights.
