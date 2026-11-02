# A2A (Agent2Agent)

The Aive speaks Google's [Agent2Agent protocol](https://a2a-protocol.org) over its JSON-RPC binding, in both directions:

- **Client:** a role can delegate its task to a remote A2A agent.
- **Server (desktop only, opt-in):** other agents can start workflows in The Aive.

The Aive writes the current specification (1.0): `SendMessage`/`GetTask`/`CancelTask`, ProtoJSON enums such as `TASK_STATE_COMPLETED`, and parts without `kind`. It reads both 1.0 and the widely deployed 0.3 (`message/send`, `tasks/get`, `tasks/cancel`, lowercase states, `kind` members). The codec is `shared/.../a2a/A2aProtocol.kt`.

The existing duplex MCP channel ([DUPLEX_MCP](architecture/DUPLEX_MCP.md)) is separate and unchanged.

## Client: remote agents as providers

**Connecting one.** In **Keeper's room › Connections › Add an A2A agent**, paste the Agent Card URL. A bare address (`https://agent.example`) means its `/.well-known/agent-card.json`. The Aive then asks for an optional bearer token, as it does for any provider key. Leaving it blank links the agent without a token.

- The URL is kept in Settings (`A2aAgentConnections`). The token goes in the platform credential store under the provider id `a2a:<host>`.
- Disconnecting an agent forgets both.

**Drifters at the hive's edge.** The other way in is the hive's edge (`Swarm-OS.md`): **Bring in a drifter** takes the same Agent Card address and reads the card, without a token and without following redirects (`A2aDrifters.read`). The drifter waits at the edge, drawn as a creature picked by a stable hash of its card URL, with its name, description and skill names (one bounded line each; the card is remote text). What went wrong lands on it in plain words: not an address it can be reached at, nothing answered (with the HTTP status or error kind), no Agent Card there (404/410), not an Agent Card, or a card with no `JSONRPC` interface. Waiting drifters last for the session.

- **Let it in** adds the card to `A2aAgentConnections` (the same list the Keeper's room shows; nothing is duplicated), names it after the card, and adds a role `drifter-<host>` to the swarm. The role prefers the drifter's `a2a:<host>` provider, needs only `Research`, and has no authorities, so it can never approve a plan, gate or release. Its description quotes the card's description and skills, bounded and on one line.
- A card with no bearer scheme is linked keyless at once. One that declares `Bearer` comes in waiting for its token: **Give it its token** opens the same credential step as the Keeper's room, which keeps it in the platform credential store under `a2a:<host>`.
- **Send it away** on a waiting drifter just drops it. On one already in, it unlinks the provider, forgets the connection and takes its role out of the swarm.
- Nothing can be let in while no runtime is awake; the drifter says so.

**When it runs.** `A2aAgentProvider` is `explicitOnly`. The router never picks it on its own; it runs only for roles that prefer it, or tasks that require it by id. Choose it as the role's provider in the swarm. It offers `PlanGeneration`, `PlanApproval` (through the engine-owned plan gate), `Messaging` and `Research`, and no repository access.

**A task's life:**

1. The Aive reads the card and uses the first `JSONRPC` interface. The interface's `protocolVersion` picks 1.0 or 0.3. Other bindings (gRPC, HTTP+JSON) are not spoken.
2. `SendMessage` sends one user message. It carries the objective, role instructions, acceptance criteria and approved upstream context as a text part, plus `metadata.aiveTaskRunId`. The remote task id becomes the provider run id, so a restart reconnects (`GetTask`) instead of sending the task again.
3. The Aive polls `GetTask` (every 2 s, for at most 6 h; 5 failed polls in a row end it). Streaming and push notifications are not used.
4. A message to the agent (the creature's Work tab) goes out as `SendMessage` with the task's `taskId` and `contextId`. This is how an `input-required` task gets its input.
5. Cancelling the task sends `CancelTask`. A remote "not cancelable" or "not found" counts as already over.

If the agent answers `SendMessage` with a message instead of a task, that message is the whole result. It does not survive a restart.

| A2A task state | The Aive |
|---|---|
| `submitted`, `working` | Progress |
| `input-required` | Progress "waiting for your reply", and the agent's question as a message |
| `auth-required` | Progress; The Aive cannot supply other credentials |
| `completed` | Artifacts, then Completed |
| `failed`, `rejected`, `canceled` | Failed, with the agent's status message |
| `unknown` (or anything unrecognized) | Progress; never treated as success |

**Artifacts.** On `completed`, the text and data parts of the remote artifacts become one text artifact (Markdown) for each kind the task requires (`CommandOutput` when it requires none). If there are no artifacts, the final status message is used. File parts with an `http(s)` URL become link artifacts. Inline file bytes are named but not kept. Artifact metadata carries `a2aTaskId`.

**Safety:**

- The card URL and the JSON-RPC endpoint must be `https://`, or `http://` to a loopback or private-network host (`CleartextPolicy`).
- When a token is set, the endpoint must be on the card's origin. A card cannot send the token anywhere else.
- Redirects are not followed, and the card is fetched without the token.
- Remote output is opaque text and links. The runtime never executes it.
- Remote output is external content (`THREAT_MODEL.md`, Context provenance). The provider labels its artifacts `ExternalContent`, and the runtime labels whatever an `a2a:` provider produced `ExternalContent` whatever the role (`outputProvenanceOnProvider`). A task that can change things and reads it waits at a context-taint gate for a person.

## Server: The Aive as an A2A agent (desktop)

Turn it on in **Keeper's room › This device › A2A server**. It is off by default and remembers the switch.

- **Address.** It listens on loopback only, `http://127.0.0.1:41241`. Set `AIVE_A2A_PORT` to use a different port (1024 to 65535).
- **Requests from browsers.** Requests whose `Host` header is not `127.0.0.1`, `localhost` or `[::1]` are refused, against DNS rebinding. No CORS headers are sent.
- **Token.** 32 random bytes, base64url. It lives in the OS keychain (macOS Keychain, Secret Service, Windows DPAPI). Without one, the token exists in memory only, changes on every start, and the room says so. **Copy token** puts it on the clipboard and it is never shown. **New token** locks out every holder of the old one.

| Request | Auth | Result |
|---|---|---|
| `GET /.well-known/agent-card.json` (also `/.well-known/agent.json`) | none | The Agent Card (1.0 shape) |
| `POST /a2a` JSON-RPC | `Authorization: Bearer <token>` | See below; `401` with `WWW-Authenticate: Bearer` otherwise |

**The card** names one `JSONRPC` interface at `/a2a`, protocol version `1.0`, and declares an HTTP Bearer scheme (`securitySchemes.bearer.httpAuthSecurityScheme`). Streaming and push notifications are `false`. Its skills are `hive` ("Run a hive") followed by one `role:<id>` skill per enabled role in the swarm. The roles describe who the planner can staff; every message is planned the same way.

**Methods.** 1.0 names are answered in 1.0 shapes and 0.3 names in 0.3 shapes. A request with an `A2A-Version` header other than `0.3` or `1.0` gets `-32009`.

- **`SendMessage` / `message/send`.** The text parts become the objective (at most 20,000 characters). The request is queued as an `ExternalWorkflowLaunchRequest`, and the UI's runtime launches it the way the launch form does, in a new project named `A2A: <first words>`. The result is the task, `submitted`. When the launch finishes it becomes `working` (bound to the new run) or `failed`, with the reason.
  - The task is `rejected` when a run is unfinished or another launch is queued. A launch would replace that run.
  - A message carrying a `taskId` gets `-32004`. A text-less message gets `-32005`.
- **`GetTask` / `tasks/get`.** Reads the run from the workflow store. `Created` maps to `submitted`. `Running` and `AwaitingHuman` map to `working`: the remote client cannot answer The Aive's gates, so they are not `input-required`. `Completed`, `Failed` and `Cancelled` map to `completed`, `failed` and `canceled`. The status message says "N/M tasks done" and the latest progress line. A completed task's artifacts are the text and `https` link artifacts of the run's final tasks, as the hive shows under "What it made". `history` holds the original message.
- **`CancelTask` / `tasks/cancel`.** A queued launch is dropped. The active run is cancelled through `ApplicationRuntime.cancelActiveRun`, like a replaced run. A finished task, or a run that is no longer the active one, gets `-32002`.
- **Everything else.** `SendStreamingMessage`, `SubscribeToTask`, `ListTasks` and `GetExtendedAgentCard` get `-32004`. Push-notification methods get `-32003`. Unknown methods get `-32601`.

**Other behavior:**

- Bodies over 1 MB get `413`.
- Tasks live in memory (the 64 most recent) and are lost when The Aive closes. The runs themselves persist as usual.
- Turning the server off fails any queued launch.

**Authority.** The server cannot approve a plan, task or gate, choose a provider, or touch a repository. A run it starts waits at its gates for the person at The Aive, like any other run.

**Android and web.** There is no A2A server on Android or the web. The client works on every platform. On the web, the remote agent must allow the page's origin (CORS).

## Tests

- `shared/src/commonTest/.../a2a/A2aProtocolTest.kt`: card JSON shape, both message/task shapes, state and method mapping.
- `A2aServerTest.kt`: auth refusal, Host check, launch/get/cancel, legacy names, busy rejection, error codes.
- `A2aAgentConnectionsTest.kt`: URL rules and catalog entries.
- `A2aDriftersTest.kt`: reading a card and each failure, the stable creature, the waiting list, and the role a drifter takes (prefers its provider, `Research` only, no authority).
- `workflow/ContextTaintTest.kt`: an `a2a:` provider's output is external whatever its role.
- `providers/llm/src/commonTest/.../A2aAgentProviderTest.kt`: the client against a mock agent, including the token-origin rule.
