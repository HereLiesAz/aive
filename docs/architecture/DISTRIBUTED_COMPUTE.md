# Distributed compute relay

The Aive can place a task on another authenticated device in the same compute pool. Distribution changes **placement only**: `TaskExecutor.Distributed` wraps a concrete `delegate` executor, and the delegate remains the semantic source of what the task does. A `Distributed` executor may not wrap another `Distributed` executor.

Code: `shared/src/commonMain/kotlin/com/hereliesaz/geministrator/distributed/` (protocol, client, worker, executor integration, delegation helpers, configuration) and `computeRelay/` (the relay server).

## Availability

The Android Play build does not offer distributed compute for 1.0: the Compute screen is hidden and no relay session starts (`DISTRIBUTED_COMPUTE_AVAILABLE` in `androidApp/src/play`). The Android GitHub release build, desktop and web keep it. This is a store-policy choice, not a security gap: per-node identity (below) did not change it.

## Topology

- **Relay** — `computeRelay/` is a standalone Ktor (CIO) WebSocket server. Nodes connect to `/v1/compute/{poolId}`. Pools are created on demand and removed once they have no nodes and no leases.
- **Nodes** — Android and Desktop builds connect as nodes. A node can publish leases (origin) and, when sharing is enabled, claim leases published by other nodes (worker). Web builds do not host a node.
- **Protocol** — JSON messages versioned by `DISTRIBUTED_COMPUTE_PROTOCOL_VERSION` (currently `2`; version 2 added per-node identity, so version 1 clients cannot connect). The first frame must be `register`; a version mismatch is rejected and the connection closed.

## Tokens, node identity and configuration

The relay reads a shared pool token from `AIVE_RELAY_TOKEN` (`HAIVE_RELAY_TOKEN` is accepted for compatibility) and its port from `PORT`, `AIVE_RELAY_PORT`, or `HAIVE_RELAY_PORT` (default `8080`). The pool token is used **only to enrol** a node; it no longer opens a session.

Each node has its own key pair (ECDSA P-256 / SHA-256, `ES256`; `ComputeNodeSigner`, protocol in `ComputeNodeIdentity.kt`):

- Android — generated inside the Android Keystore (alias `aive.distributed-compute.node-identity`), not exportable.
- Desktop — generated with JCA and kept in the OS credential store (account `node-signing-key`, Windows file `distributed-compute-node-key.dpapi`), with the same plaintext-preferences fallback as the token when no store works.

Enrolment, once per relay, pool, node ID and key (remembered under `haive.distributed-compute.enrolment.v1`):

~~~
POST /v1/compute/{poolId}/enrol
Authorization: Bearer <pool token>
{"protocolVersion":2,"nodeId":…,"displayName":…,"algorithm":"ES256","publicKey":<SPKI, Base64>,
 "timestampEpochMillis":…,"signature":<proof of possession>}
~~~

The signature covers the pool, node ID, display name, key and timestamp, so the request proves the node holds the key. Enrolling the same key again only updates the name. A different key for an enrolled node ID is refused (`node-id-taken`), and so is any key for a revoked one (`node-revoked`); either way the device needs a new device ID. A pool holds at most 256 node identities.

Every session then opens with a short-lived signed request. The WebSocket upgrade carries `X-Aive-Node-Id`, `X-Aive-Node-Timestamp`, `X-Aive-Node-Nonce` and `X-Aive-Node-Signature`, the signature covering pool, node ID, timestamp and nonce. The relay accepts it only if the node is enrolled and not revoked, the timestamp is within 5 minutes of the relay clock, the nonce has not been seen in that window, and the signature verifies against the enrolled key. The `register` frame must then name the same node ID. A relay that has lost a node's record answers `node-not-enrolled`, and the client enrols again.

Any enrolled node can list the pool's nodes (`list_enrolled_nodes`; the relay also sends `enrolled_nodes` on register and on every change) and revoke another node (`revoke_node`; not itself). Revocation closes that node's live session, cancels the leases it published, requeues the leases it held, and stops its key and node ID for good. The Keeper's room Compute part lists the enrolled devices with key fingerprint and work counts, and offers Revoke for each other device while this one is connected. A revoked client stops reconnecting. Revocation does not take the pool token from the device, so a device that may still know it could enrol afresh under a new ID: change the relay token too.

Clients store relay URL, pool ID, node ID, display name, sharing flag, max parallel leases, and metered/external-power policy under `haive.distributed-compute.configuration.v1`. The pool token is stored separately:

- Android — encrypted with an Android Keystore AES-GCM key in private SharedPreferences.
- Desktop — `AIVE_RELAY_TOKEN`/`HAIVE_RELAY_TOKEN` if set, otherwise the OS credential store: macOS `security`, Linux `secret-tool`, and on Windows a per-user file under `%LOCALAPPDATA%\Aive` encrypted with DPAPI (`ConvertFrom-SecureString`), written and read through PowerShell with the token on stdin/stdout, never on a command line. When no store works (for example, no `secret-tool`), or the stored token does not read back, the token falls back to **plaintext** Java user preferences (`com/hereliesaz/haive/distributed-compute`).

## Delegation targets

`ComputeDelegationTarget` is `Local`, `AnyRemote`, or `Node(nodeId)`. Delegation can be applied to a whole workflow, to every task of a role, or to one task. `AnyRemote` clears required/preferred node IDs; `Node` pins both to that node. Returning to `Local` unwraps the delegate (restoring the implicit role-agent executor where it matched the task's role). `HumanApproval` tasks are never delegated.

## Leases, heartbeats, and progress

1. The origin's `DistributedComputeExecutorIntegration` publishes a `DistributedTaskEnvelope` with lease ID `distributed:<runId>:<taskRunId>:<attempt>` and a default 30 s lease duration (minimum 5 s). Artifacts of other task runs are stripped from the envelope. The integration is used only while the relay client is connected.
2. The relay offers the lease to every other node whose descriptor `canRun` it (accepting work, CPU/memory/accelerator/capability/model/node requirements, supported executor kind) and that is below its `maxParallelLeases`, ordered by preference score.
3. A worker claims the lease; the relay assigns it to the first claimant and sets an expiry. The worker sends lease heartbeats every lease-duration/3 (at least 1 s), forwards `Running`/`Verifying` progress, and completes with `Completed` or `Failed` plus artifacts. While executing, the worker continuously guards its lease: explicit cancellation, requeue/reassignment, or relay disconnect cancels the running coroutine. Transport loss is treated as cancellation, not fabricated task failure.
4. Expired or disconnected worker leases are requeued and reoffered (the relay sweeps every 5 s).
   Origin disconnect does **not** cancel accepted work. A claimed worker continues while its own relay
   session remains healthy; unclaimed work stays eligible for workers that join later.
5. The relay retains terminal lease results for 24 hours. Unfinished leases are not expired merely
   because their origin is offline: dropping an unfinished external run ID would make it unsafe to infer
   that side effects can be repeated. When the same logical origin reconnects, the relay replays its
   accepted/claimed/progress/completed state so the client can reconstruct the existing lease instead
   of dispatching it again. They are bounded all the same: an unfinished lease is cancelled
   (`LeaseCancelled` to origin and worker) and dropped 24 hours after the relay accepted it.
6. Publishing is capped: a lease may ask for at most a 10-minute lease duration, an origin may have
   at most 64 unfinished leases in a pool, and a pool holds at most 512 leases including retained
   terminal ones. Over a cap, `PublishLease` gets a `bad-request` or `bad-state` error.

Clients also send a node heartbeat every 15 s and reconnect with exponential backoff (1 s to 15 s).
The origin maps replayed/current lease phases back onto the `TaskRun`; a cancelled lease reports as
`Failed`. A node ID belongs to the key enrolled for it, and a session to the node that signed its
request. Even its own holder cannot open a second live session: a second registration gets
`node-id-in-use` and is closed (code 1013), so a node that reconnects is accepted only once the relay
has seen its old socket close (at most the 45 s WebSocket timeout). State changes are also fenced by
peer identity, so a stale socket cannot unregister the node or act for it.

## Attribution

Every lease is attributed to the node that ran it, as the relay saw it: the `workerNodeId` of a claim,
progress and completion is the authenticated session's node, never a value the worker sends. The
terminal lease record keeps that node (and replays it to the origin), the node's identity record counts
its completed and failed leases, and the origin's task run ends with "(ran on <node>)".

## Relay process-restart persistence

By default the relay keeps lease state only in memory. Set `AIVE_RELAY_STATE_DIR` (legacy
`HAIVE_RELAY_STATE_DIR` is accepted) to make lease state survive relay-process restarts. Each pool is
stored in its own opaque hashed filename. The file payload is AES-256-GCM encrypted; the encryption key
is domain-separated and derived from the relay bearer token, and the pool ID is authenticated as GCM
associated data. Enrolled node identities, their revocations and work counts are kept in a second file per
pool (`<hash>.nodes`, associated data `nodes:<pool>`). Without `AIVE_RELAY_STATE_DIR` they live only in
memory: a relay restart then forgets every enrolment and revocation, nodes enrol again with the pool
token, and a revoked device that still knows the token could too. A pool with enrolled nodes is never
dropped from memory by the idle sweep.

Persistence records only lease transport state (envelope, origin, last known worker for terminal
history, progress/result and completion time). Live sockets are never persisted. On restart, unfinished
leases are deliberately restored as **unclaimed** because the old relay process severed every worker
WebSocket; worker-side ownership guards cancel work on that transport loss. Completed results remain replayable for the normal 24-hour terminal window. Expired terminal entries
are purged by the active relay sweep and when a persisted pool next loads; an encrypted snapshot for a
pool that never reconnects can therefore remain on disk until that pool is accessed or the operator
removes its state file.

State writes use a temp file followed by atomic replace where the filesystem supports it, and owner-only
POSIX permissions are applied when available. Changing the relay bearer token also changes the at-rest
key, so existing state must be drained/cleared or migrated before token rotation. If an encrypted state
file cannot authenticate or decode, the relay fails that pool load rather than silently discarding
authoritative lease state.

## What nodes accept

Workers run leases through `SystemExecutorDistributedWorkloadRunner`, which accepts only system executors that the node has a local integration for. Role-agent and human-approval work is not executed remotely by the shipped runners. Before running a lease the runner re-checks it and refuses (fails the lease) unless the submitted workflow validates, the task really is a distributed placement of the requested executor, the task is not a mutating repository operation (only `status` and `fetch` run remotely: approval state arrives in the envelope, which any pool-token holder can forge, so a worker cannot trust it and mutations stay on the origin device), and credential-using work (repository operations, GitHub Actions, GitHub-backed scripts) targets a repository of a project linked on the worker's own device. Advertised `supportedExecutorKinds`:

- Android — `script`; `github-action` and `repository-operation` with a GitHub token; `repository-operation` with a GitLab token.
- Desktop — `repository-operation`; `github-action` with a GitHub token.

Android accepts new work only when sharing is enabled and its current metered-network and external-power policy is satisfied. The Android session watches default-network and battery/power broadcasts, updates its relay descriptor when those conditions change, and the local worker evaluates each incoming offer against that same live descriptor. An already-claimed lease is not cancelled merely because power/network policy later becomes ineligible; ownership loss/cancellation remains the authority for stopping side-effecting work. Desktop cannot observe power state, so enabling "require external power" stops it accepting work.

## Encrypted compute mesh primitives

`shared/.../mesh/` holds a separate compute-mesh layer used by `RemoteComputeCoordinator`. `MeshCrypto.kt` defines pairing invitations (relay URL, room ID, random 256-bit room key, random relay auth token) and an AES-GCM `MeshCipher` with associated data; Android/Desktop use JCA, and JS/Wasm use `webApp/src/webMain/resources/mesh-crypto.js`, which exposes a `HaiveMeshCrypto` Web Crypto wrapper.
