# Distributed compute relay

The Aive can place a task on another authenticated device in the same compute pool. Distribution changes **placement only**: `TaskExecutor.Distributed` wraps a concrete `delegate` executor, and the delegate remains the semantic source of what the task does. A `Distributed` executor may not wrap another `Distributed` executor.

Code: `shared/src/commonMain/kotlin/com/hereliesaz/geministrator/distributed/` (protocol, client, worker, executor integration, delegation helpers, configuration) and `computeRelay/` (the relay server).

## Topology

- **Relay** — `computeRelay/` is a standalone Ktor (CIO) WebSocket server. Nodes connect to `/v1/compute/{poolId}`. Pools are created on demand and removed once they have no nodes and no leases.
- **Nodes** — Android and Desktop builds connect as nodes. A node can publish leases (origin) and, when sharing is enabled, claim leases published by other nodes (worker). Web builds do not host a node.
- **Protocol** — JSON messages versioned by `DISTRIBUTED_COMPUTE_PROTOCOL_VERSION` (currently `1`). The first frame must be `register`; a version mismatch is rejected and the connection closed.

## Tokens and configuration

The relay reads a single shared bearer token from `AIVE_RELAY_TOKEN` (`HAIVE_RELAY_TOKEN` is accepted for compatibility) and its port from `PORT`, `AIVE_RELAY_PORT`, or `HAIVE_RELAY_PORT` (default `8080`). Connections without `Authorization: Bearer <token>` are closed as unauthorized. Any holder of the token can join any pool ID on that relay.

Clients store relay URL, pool ID, node ID, display name, sharing flag, max parallel leases, and metered/external-power policy under `haive.distributed-compute.configuration.v1`. The token is stored separately:

- Android — encrypted with an Android Keystore AES-GCM key in private SharedPreferences.
- Desktop — `AIVE_RELAY_TOKEN`/`HAIVE_RELAY_TOKEN` if set, otherwise the OS credential store (macOS `security`, Windows PowerShell credential manager, Linux `secret-tool`).

## Delegation targets

`ComputeDelegationTarget` is `Local`, `AnyRemote`, or `Node(nodeId)`. Delegation can be applied to a whole workflow, to every task of a role, or to one task. `AnyRemote` clears required/preferred node IDs; `Node` pins both to that node. Returning to `Local` unwraps the delegate (restoring the implicit role-agent executor where it matched the task's role). `HumanApproval` tasks are never delegated.

## Leases, heartbeats, and progress

1. The origin's `DistributedComputeExecutorIntegration` publishes a `DistributedTaskEnvelope` with lease ID `distributed:<runId>:<taskRunId>:<attempt>` and a default 30 s lease duration (minimum 5 s). Artifacts of other task runs are stripped from the envelope. The integration is used only while the relay client is connected.
2. The relay offers the lease to every other node whose descriptor `canRun` it (accepting work, CPU/memory/accelerator/capability/model/node requirements, supported executor kind) and that is below its `maxParallelLeases`, ordered by preference score.
3. A worker claims the lease; the relay assigns it to the first claimant and sets an expiry. The worker sends lease heartbeats every lease-duration/3 (at least 1 s), forwards `Running`/`Verifying` progress, and completes with `Completed` or `Failed` plus artifacts.
4. Expired or disconnected worker leases are requeued and reoffered (the relay sweeps every 5 s). If the origin disconnects, its leases are cancelled.

Clients also send a node heartbeat every 15 s and reconnect with exponential backoff (1 s to 15 s). The origin maps lease phases back onto the `TaskRun`; a cancelled lease reports as `Failed`.

## What nodes accept

Workers run leases through `SystemExecutorDistributedWorkloadRunner`, which accepts only system executors that the node has a local integration for. Role-agent and human-approval work is not executed remotely by the shipped runners. Before running a lease the runner re-checks it and refuses (fails the lease) unless the submitted workflow validates, the task really is a distributed placement of the requested executor, any mutating repository operation has a completed human-approval ancestor in the submitted run, and credential-using work (repository operations, GitHub Actions, GitHub-backed scripts) targets a repository of a project linked on the worker's own device. Advertised `supportedExecutorKinds`:

- Android — `script`; `github-action` and `repository-operation` with a GitHub token; `repository-operation` with a GitLab token.
- Desktop — `repository-operation`; `github-action` with a GitHub token.

Android accepts work only when sharing is enabled and its metered-network and external-power policy is satisfied. Desktop cannot observe power state, so enabling "require external power" stops it accepting work.

## Encrypted compute mesh primitives

`shared/.../mesh/` holds a separate compute-mesh layer used by `RemoteComputeCoordinator`. `MeshCrypto.kt` defines pairing invitations (relay URL, room ID, random 256-bit room key, random relay auth token) and an AES-GCM `MeshCipher` with associated data; Android/Desktop use JCA, and JS/Wasm use `webApp/src/webMain/resources/mesh-crypto.js`, which exposes a `HaiveMeshCrypto` Web Crypto wrapper.
