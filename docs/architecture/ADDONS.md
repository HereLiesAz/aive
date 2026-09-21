# Workflow Add-Ons Architecture

The Aive consumes Azphalt `kind:"workflow"` and `kind:"role"` packages so workflows and reusable
roles can be installed without exposing Aive internals to third-party packages. The Store also
discovers `kind:"asset"` packages in Azphalt's `model` media domain so compatible ONNX, TFLite,
LiteRT, task, and speech-model assets are visible from inside The Aive.

## Host identity and catalog discovery

The current Azphalt host ID is `com.hereliesaz.aive`. Packages published for the historical
`com.hereliesaz.haive` host ID remain compatible so old catalog entries do not disappear merely
because the product name changed.

The Store requests workflow and role packages plus model-domain asset packages from the Repository API using the current Aive host ID.
If an app-scoped search incorrectly returns an empty first page while the unscoped repository catalog
is healthy, the client performs one defensive unscoped retry and filters the returned summaries
locally using `targetApps`. Only packages that are global, target `com.hereliesaz.aive`, or target
the legacy `com.hereliesaz.haive` identity survive that fallback.

This fallback is a resilience mechanism, not a replacement for correct repository indexing. The
centralized Azphalt deployment workflow verifies both the generated catalog and the live
`azphalt.store/packages` endpoint contain known Aive workflow/role packages after deployment.

Model assets have their own install lifecycle and are never sent through the workflow/role installer.
On Android, The Aive currently accepts every model asset type defined by Azphalt:
`onnx`, `tflite`, `litert`, `task`, `sherpa-bundle`, `vosk-bundle`, and generic `model`.
Generic model packages are routed from their materialized contents when a known concrete format is
detectable.

Model installation:
- verifies the signed `.azp` package and revocation state before mutation;
- verifies every remote model member against its declared SHA-256 digest and supports resumable downloads;
- materializes bundled and remote multi-file assets into app-private storage;
- validates ONNX graphs through the bundled ONNX Runtime, validates TFLite/LiteRT flatbuffer headers,
  validates task bundles, and checks the required Sherpa/Vosk bundle structure;
- records the concrete installed artifact path, backend, role, package/version, and digest in the
  durable inference-model registry;
- replaces updates transactionally with rollback to the previous files and registry entries on failure;
- removes both files and registry entries on uninstall;
- supports Android ACTION_VIEW/ACTION_SEND imports through the same trust/install path.

Model-license metadata is surfaced in the install review before mutation. Model files remain owned by
the Azphalt model installer; the inference registry stores identity and resolution metadata rather than
duplicating weights.

A failed repository request must surface as an error. A legitimately empty compatible catalog is
reported separately from transport/server failure; the UI must not quietly translate a failed refresh
into "0 installed" or "no packages."

## NEVER EXPOSE

The following internal platform capabilities MUST NEVER be exposed to the add-on layer:

- The Memory layer (`MemoryMicroAgents`, `MemoryMicroAgentDeployment`, embeddings, etc.)
- Provider credentials and platform credentials
- Raw filesystem paths
- Internal persistence implementations
- Raw `AgentProvider` or `TaskExecutorIntegration` instances

## Mediated Contracts

All operations exposed to add-ons are strictly mediated. Add-ons request operations symbolically
(for example via symbolic UI actions), and The Aive interprets and executes those requests only when
the package has the corresponding granted `HostPermission`.

Workflow/role installation materializes validated definitions into Aive's normal workflow/role
repositories. The add-on does not receive direct access to those repositories.

## Native Declarative Screens

Add-ons can provide declarative UI structures such as `AddonScreen`, which The Aive renders
natively in Compose. Add-ons never load arbitrary HTML/JS or custom bytecode into the host.
