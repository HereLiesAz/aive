# Agent-neutral Duplex MCP

Aive's orchestration is not itself an agent. Tasks, agents, memory, and executors retain separate identities, roles, and authorities. The existing `MemorySessionObserver` receives provider events and has a narrowly scoped `reply` callback into that provider's `ManagedSessionGateway.message`; a memory recall is neither a provider identity nor permission to call tools.

## Transport boundary

`shared/.../duplex/` supplies two complementary, opt-in parts:

- `DuplexAgentBus` is an in-process, platform-neutral pair of independent inboxes with simultaneous sends, request correlation, explicit acknowledgements, bounded capacity and volatile queues. Its `notifications` are hints, not delivery receipts.
- `DuplexMcpPeer` connects **as either equal peer** to an MCPServed Duplex endpoint (`/mcp/a` or `/mcp/b`) over authenticated Streamable HTTP. Both peers can call `duplex_send`, `duplex_receive`, `duplex_ack` and `duplex_status`. `notifications()` reads optional server-sent MCP notifications; a reconnect must replay `duplex_receive` and acknowledge processing. Each peer receives a different bearer token. The connection is HTTPS or loopback HTTP; redirect following is disabled.

The bridge is **not** an implicit MCP tool executor. `DuplexManagedSessionBridge` is explicitly attached to one `ManagedSessionHandle`. It forwards inbound `message`/`request` text through that session's existing provider gateway, never through global launch, approval, shell or repository operations. A request response means **accepted for delivery**, not that the provider completed the requested task. Rejections of ordinary messages are not acknowledged, allowing later retry. Duplicate delivery after process death is possible; do not advertise exactly-once behavior. `event`, `response`, and `cancel` frames are metadata, not arbitrary commands.

`DuplexSessionObserver` composes with Aive's existing memory observer and must be bound to one specific session handle. It forwards that session's user-visible provider events without exporting `Thinking`, usage telemetry, the memory layer's own `⟦memory⟧` messages, or introducing a second memory bank. Peer transport failure does not change the workflow's status. A caller may supply an explicitly paired, task-scoped `DuplexTaskSessionAttachment` to `App` or `ApplicationRuntime.create`; the existing `ProviderBackedManagedSessionGateway` then attaches the peer automatically whenever that selected task starts or reconnects. Its separate inbox worker drains the peer, without taking ownership of task permissions. **There is still no default pairing or automatic connection to arbitrary tasks.** Procedure-specific duplex events and durable procedural journals remain *proposed* in [Procedural Memory](PROCEDURAL_MEMORY.md).

## Wire contract (MCPServed Duplex 1)

- POST JSON-RPC 2.0 `tools/call` to `/mcp/{a|b}`, `Authorization: Bearer <peer-token>`.
- `duplex_send`: `{kind:"message|event|request|response|cancel",body:string,replyTo?:string}` → a `{seq,id,from,kind,body,replyTo?,time}` envelope.
- `duplex_receive`: `{after?:integer,limit?:integer}` → envelope array. The omitted cursor resumes after the latest acknowledged sequence.
- `duplex_ack`: `{through:integer}` → `{acknowledged:integer}`. Acknowledge only after local processing.
- `duplex_status`: `{}` → `{queued,acknowledgedThrough,latestSequence,peerStreamOnline}`.
- GET on the same URL optionally streams `notifications/message` SSE; its `params.data` contains the same envelope. Treat the notification as a wake-up signal, not a separate delivery.
- Sending a response or cancellation requires `replyTo` referencing a live request, with ownership checked by the broker. Queues and outstanding requests are bounded, and they do not survive a broker restart.

## Authority

Do not equate reachability, identity or receipt with consent. Aive's provider and workflow gates remain authoritative; the bridge cannot widen them. MCPServed's Android grants, session TTLs, and fail-closed backend selection remain unchanged. Neither application should install a default privileged peer, persist authentication tokens in source, or silently fall back to unrestricted device access.

## Verification

`shared/src/commonTest/.../duplex/DuplexAgentBusTest.kt` verifies independent directions, request ownership, cancellation, backpressure, acknowledgements, UTF-8 byte limits, and pending-request expiry. MCPServed has matching Node integration tests for the HTTP and SSE bridge. A cross-process, live Aive-provider ↔ MCPServed acceptance test remains a separate integration task.
