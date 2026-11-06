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

**Drifters at the hive's edge.** The other way in is the hive's edge (`Swarm-OS.md`): **Bring in a drifter** takes the same Agent Card address and reads the card, without a token and without following redirects (`A2aDrifters.read`). The drifter waits at the edge, drawn as a creature picked by a stable hash of its card URL, with its name, description and skill names (one bounded line each; the card is remote text). What went wrong lands on it in plain words: not an address it can be reached at, nothing answered (with the HTTP status or error kind), no Agent Card there (404/410), not an Agent Card, or a card with no `JSONRPC` interface. Waiting drifters last for the session.

- **Let it in** adds the card to `A2aAgentConnections` (the same list the Keeper's room shows; nothing is duplicated), names it after the card, and adds a role `drifter-<host>` to the swarm. The role prefers the drifter's `a2a:<host>` provider, needs only `Research`, and has no authorities, so it can never approve a plan, gate or release. Its description quotes the card's description and skills, bounded and on one line.
- A card with no bearer scheme is linked keyless at once. One that declares `Bearer` comes in waiting for its token: **Give it its token** opens the same credential step as the Keeper's room, which keeps it in the platform credential store under `a2a:<host>`.
- **Send it away** on a waiting drifter just drops it. On one already in, it unlinks the provider, forgets the connection and takes its role out of the swarm.
- Nothing can be let in while no runtime is awake; the drifter says so.

**Seeded drifters.** A few public A2A agents wait at the edge from the first launch, with no setup (`a2a/A2aDrifterSeeds.kt`, `SEEDS`). They are third-party agents, not The Aive's. Until the keeper acts they show only the seed's own name, a line about them and who runs them: their card is read only on **Let it in**, so nothing is fetched from them before then, and a failure then lands on the seed. **Send it away** dismisses a seed for good on this device (Settings key `aive.a2a.drifters.dismissed-seeds.v1`); so does sending away a seed that was let in. A seed already let in is not offered again.

A seed must need no key, serve its JSON-RPC endpoint on its card's origin, and answer `SendMessage` with something useful for free. Each entry records when and how it was checked. Current seeds, checked 2026-11-02 by reading the card without a token (no security schemes) and sending one real `SendMessage`; both were listed as reachable and passing by [a2aregistry.org](https://a2aregistry.org/):

| Seed | Card | Run by | Check |
|---|---|---|---|
| World Bank Indicators | `https://agentbazaar.tech/a2a/connectors/worldbank/.well-known/agent.json` | AgentBazaar (JNK Corp.), not affiliated with the World Bank | JSON-RPC 1.0 and 0.3; "GDP of France" completed with a World Bank API recipe |
| The Moorish Times | `https://mcp.moorish.cloud/.well-known/agent-card.json` | The Moorish Times | JSON-RPC 1.0; "latest news" answered with linked news briefs |

No official a2aproject agent is hosted publicly; its samples run locally. Public agents come and go: if a seed stops answering, its failure shows on it, and it should be taken out of `SEEDS`.

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

### Describing providers

The card can also say which providers the swarm can use, so another agent can see what a multi-provider hive is made of. **Keeper's room > This device > A2A server > Describe my providers in the card** controls it (desktop only; Android and web have no server).

- **What is added.** One skill `provider:<id>` per provider that is linked here (the live provider registry holds only those), up to 24, sorted by id. Each has the name the app shows, the description "Linked provider. Reports: plan generation, research." built from its capability names, and tags `provider` plus the capability names. Each `role:<id>` skill gains the tag `provider:<id>` of its preferred provider (while that provider is linked) or `provider:auto`, and a clause "Provider: ..." in its description. A remote A2A agent shows as `provider:a2a-<hash>` named "Remote A2A agent", never by host.
- **What is never added.** Keys, tokens, base URLs, account names or emails, model names, balances, anything from the secret stores. Only id, shown name and capabilities are read.
- **Default (the rule).** If the keeper never pressed the switch, providers are described while the server is on this computer only, and left out as soon as **Open to this network** is on, since the card is then readable by the whole network. Once pressed, the choice is kept as set, on or off, on any network (`a2aDescribeProviders`, key `describe-providers` in the server's preferences). Loopback only is already limited to this computer's processes; the network is a wider audience, so it needs a deliberate yes.

## Hive cards

A hive card is a portable record of one hive: which role each creature has and which provider it runs on, plus its strands. It works on every platform and needs no server. It is an A2A 1.0 Agent Card (`A2aCodec.encodeCard`) whose skills are the creatures (`creature:<id>`, tagged `role:<id>` and `provider:<id>` or `provider:auto`), with the record itself in one `capabilities.extensions` entry, URI `urn:aive:a2a:ext:hive-card:v1`, `required: false`. It describes a hive and serves nothing, so it lists no interface. Code: `a2a/A2aHiveCard.kt`.

The record (`params`): `format: "aive-hive-card"`, `version: 1`, `hive.name`, `includesObjectives`, and `creatures` (at most 12), each with `id`, `name`, `kind` (`role`, `approval` or `other`), `role` (role id), `assignment` (`pin` with `provider`, or `auto`), optional `cardUrl` (only for a pinned `a2a:` provider, and only an http(s) address without user info, query or fragment), `dependsOn` (strands) and, only if asked, a one-line `objective`.

- **Sharing.** **Share hive card** appears on a hive (the resting hive in Build, and as a pill on a live hive). **Include each objective** is OFF by default: an objective can say what the work is about, so the default card carries names and roles only. **Copy hive card** puts the text on the clipboard (there is no separate platform share sheet). A pin is a task's required provider, or a role's preferred provider. Conditions on strands are not recorded.
- **Never included.** Keys, tokens, repository paths or URLs, memory, prompts or role instructions, scripts, executors.
- **Importing.** **Import hive card** (Hives list) takes pasted text, reads it, shows what it will do in plain words, and **Create this hive** makes a NEW hive (a free `hive-N` id; nothing is overwritten). Creatures whose role is in this swarm keep that role, with the executor taken from this device's role. A pin to a provider that is linked here is kept; a pin to one that is not becomes auto and the summary says "Needs X". It connects nothing and fetches nothing. For an `a2a:` provider that is not linked, the card's address is shown as text, to bring in by hand through **Bring in a drifter**. Creatures whose role is missing are left out with a note; steps of other kinds (scripts, actions, deployments, repository operations, nested workflows) are always left out and counted; human approval steps come back as plain human approvals. Strands to a left-out creature are dropped and counted.
- **Validation.** At most 256 KB, nesting depth 16, 12 creatures; version must be 1; ids are 1 to 64 of `A-Za-z0-9._:-`; duplicate ids, unknown strands, bad kinds and assignments are refused; names and objectives are cut to one line. The result goes through `WorkflowGraphValidator` like an authored hive (cycles refused). Errors are plain sentences; nothing throws.
- **Tests.** `A2aHiveCardTest` (round trip, missing provider and role, hostile input, nothing private) and `A2aProviderCardsTest` (provider skills, the default and network rule, bounds, no secrets from a registry of fake providers).

**Android and web.** There is no A2A server on Android or the web. The client works on every platform. On the web, the remote agent must allow the page's origin (CORS).

## Tests

- `shared/src/commonTest/.../a2a/A2aProtocolTest.kt`: card JSON shape, both message/task shapes, state and method mapping.
- `A2aServerTest.kt`: auth refusal, Host check, launch/get/cancel, legacy names, busy rejection, error codes.
- `A2aServerSettingTest.kt`: on by default, an explicit choice kept.
- `desktopApp/src/test/.../DesktopA2aServerTest.kt`: binds loopback on a free port, serves the card, refuses calls without the token, keeps the token across starts, reports a port in use; open to the network it binds every interface and advertises the network URL, closing it rebinds loopback, an older switch change cannot undo a newer one, and only addresses can be allowed.
- `A2aServerLanTest.kt`: network callers refused while closed, the card's URL per caller, Host checks, the allowlist and waiting list, the per-client `SendMessage` limit, address normalization.
- `A2aAgentConnectionsTest.kt`: URL rules and catalog entries.
- `A2aDrifterSeedsTest.kt`: every seed is a normalized https card with its check written down, seeds wait unread, dismissal persists, a connected seed is not offered.
- `A2aDriftersTest.kt`: reading a card and each failure, the stable creature, the waiting list, and the role a drifter takes (prefers its provider, `Research` only, no authority).
- `workflow/ContextTaintTest.kt`: an `a2a:` provider's output is external whatever its role.
- `providers/llm/src/commonTest/.../A2aAgentProviderTest.kt`: the client against a mock agent, including the token-origin rule.
