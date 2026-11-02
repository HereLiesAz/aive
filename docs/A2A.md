# A2A (Agent2Agent)

The Aive speaks Google's [Agent2Agent protocol](https://a2a-protocol.org) over its JSON-RPC binding, in both directions:

- **Client:** a role can delegate its task to a remote A2A agent.
- **Server (desktop only, on by default, this computer only):** other agents on the same computer that hold its token can start workflows in The Aive. **Open to this network** (off by default) lets allowed computers on the local network do the same.

The Aive writes the current specification (1.0): `SendMessage`/`GetTask`/`CancelTask`, ProtoJSON enums such as `TASK_STATE_COMPLETED`, and parts without `kind`. It reads both 1.0 and the widely deployed 0.3 (`message/send`, `tasks/get`, `tasks/cancel`, lowercase states, `kind` members). The codec is `shared/.../a2a/A2aProtocol.kt`.

The existing duplex MCP channel ([DUPLEX_MCP](architecture/DUPLEX_MCP.md)) is separate and unchanged.

## Client: remote agents as providers

**Connecting one.** In **Keeper's room › Connections › Add an A2A agent**, paste the Agent Card URL. A bare address (`https://agent.example`) means its `/.well-known/agent-card.json`. The Aive then asks for an optional bearer token, as it does for any provider key. Leaving it blank links the agent without a token.

- The URL is kept in Settings (`A2aAgentConnections`). The token goes in the platform credential store under the provider id `a2a:<host>`.
- Disconnecting an agent forgets both.

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

## Server: The Aive as an A2A agent (desktop)

It runs on desktop unless the keeper turns it off in **Keeper's room › This device › A2A server**. That row shows the address it listens on, and has **Copy token** and **New token**.

- **On by default, and the choice is kept.** The switch is stored only when the keeper presses it (Java preferences node `com/hereliesaz/haive/a2a-server`, key `enabled`). A device that never pressed it, including one installed while the default was off, gets the server on. A keeper who turned it off keeps it off. The default lives in `a2aServerEnabled` (`A2aServerSetting.kt`).
- **Address.** It listens on loopback only, `http://127.0.0.1:41241`. Set `AIVE_A2A_PORT` to use a different port (1024 to 65535).
- **Port in use.** If another program holds the port, the server stays off for this session and the row says so in plain words. The rest of The Aive works as usual. It starts off the UI thread, so a slow keychain does not hold up the window.
- **Requests from browsers.** Requests whose `Host` header is not `127.0.0.1`, `localhost` or `[::1]` are refused, against DNS rebinding. No CORS headers are sent.
- **Token.** 32 random bytes, base64url, made on the first start and kept in the OS keychain (macOS Keychain, Secret Service, Windows DPAPI). It is never written to a log, shown on screen or put in the Agent Card. Without one, the token exists in memory only, changes on every start, and the room says so. **Copy token** puts it on the clipboard and it is never shown. **New token** locks out every holder of the old one.

| Request | Auth | Result |
|---|---|---|
| `GET /.well-known/agent-card.json` (also `/.well-known/agent.json`) | none | The Agent Card (1.0 shape) |
| `POST /a2a` JSON-RPC | `Authorization: Bearer <token>` | See below; `401` with `WWW-Authenticate: Bearer` otherwise |

### Open to this network

Under the A2A server row, **Open to this network** is off by default, even though the server itself is on. The switch is kept once pressed (preferences key `lan`; `a2aServerOpenToNetwork`). Turning it on or off restarts the server on the other address; a launch still queued at that moment fails, as when the server is turned off.

- **Binding.** While on, the server binds every IPv4 interface (`0.0.0.0`) on the same port. This computer still reaches it at `127.0.0.1`. The row lists the Agent Card URL on each of this computer's IPv4 addresses (up, not loopback, not link-local), re-read every 30 seconds. IPv6 is not served. A single chosen interface is not offered yet.
- **Host check.** A request's `Host` must still be a loopback name, or one of this computer's own addresses as an IP literal. Host names are refused, so DNS rebinding still fails.
- **Who may call.** A caller from another computer needs the bearer token *and* its IP address on the keeper's allowlist (preferences key `lan-allowed`). A caller with the right token whose address is not listed gets `403` and appears on the row as "`<address>` has the token and wants to send work", with **Allow**. Allowed addresses are listed with **Remove**. A caller without the token gets `401` and is never listed, so scanners cannot fill the list. At most 8 waiting addresses are shown, newest first. Callers on this computer need no entry. While the switch is off, every non-loopback caller is refused, card included.
- **Why an allowlist and not an Inbox approval.** Approval in the hive's Needs/Inbox would need a new kind of wait that belongs to no run, plus a pending-client queue across restarts. The allowlist is deny-by-default, needs one keeper action per computer, is decided where the switch lives, and the "wants to send work" line gives the same first-contact prompt without new Inbox plumbing. It is keyed by address, not identity: see the threat model's residual risks.
- **Rate limit.** `SendMessage` is limited to 6 calls a minute per client (each network address; this computer counts as one client), whether or not the launch is accepted. Over the limit the answer is HTTP `429` with `Retry-After` in seconds. `GetTask` and `CancelTask` are not limited.
- **Agent Card.** A caller that reached the server at a network address gets a card whose interface URL is `http://<that address>:<port>/a2a`. A caller on loopback gets the loopback URL. With the switch off the card never names a network address. The card is public, as A2A discovery expects, so anyone on the network can read it while the switch is on.
- **No encryption.** It is plain HTTP. TLS is not offered: the JDK has no public way to make a certificate, and A2A clients would reject a self-signed one without pinning that they do not offer. The token and everything sent travel in clear text, and anyone else on the network can read them. The row says so beside the switch. Use it only on a trusted network, and press **New token** after using it on one you do not trust.

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
- `A2aServerSettingTest.kt`: on by default, an explicit choice kept.
- `desktopApp/src/test/.../DesktopA2aServerTest.kt`: binds loopback on a free port, serves the card, refuses calls without the token, keeps the token across starts, reports a port in use; open to the network it binds every interface and advertises the network URL, closing it rebinds loopback, an older switch change cannot undo a newer one, and only addresses can be allowed.
- `A2aServerLanTest.kt`: network callers refused while closed, the card's URL per caller, Host checks, the allowlist and waiting list, the per-client `SendMessage` limit, address normalization.
- `A2aAgentConnectionsTest.kt`: URL rules and catalog entries.
- `providers/llm/src/commonTest/.../A2aAgentProviderTest.kt`: the client against a mock agent, including the token-origin rule.
