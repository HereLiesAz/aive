# MCP host

Agents call tools from MCP servers the keeper connects. The Aive is the MCP *host*: it holds one client per server, offers a run's tools to the model, executes the calls itself, and feeds the results back. This is separate from [Duplex MCP](DUPLEX_MCP.md), a peer link for messages, which runs no tools.

## Parts

All in `shared/.../mcp/` unless noted.

| Type | Role |
|---|---|
| `McpJsonRpc`, `McpProtocol` | JSON-RPC 2.0 framing, protocol constants, header-value encoding |
| `McpTransport` | A message pipe: `McpStreamableHttpTransport` (every platform); `McpStdioTransport` (desktop, `desktopMain`) |
| `McpClient` | One server: era detection, `listTools`, `callTool` |
| `McpServerConfig`, `McpServerStore` | The keeper's servers, as JSON in general settings (no secrets) |
| `McpCredentialVault` | Bearer tokens, per platform: `AndroidMcpCredentialStore` (Keystore), `DesktopMcpCredentialStore` (Keychain, Secret Service, DPAPI), the web credential vault |
| `McpConsentGate`, `PromptingMcpConsentGate` | The approval gate |
| `McpElicitationRequest`, `McpElicitationGate`, `PromptingMcpElicitationGate` | A server's request for the keeper's input, its form fields and validation, and the dialog's queue (`McpElicitationDialog` in the shared UI) |
| `McpHost` (`McpToolSource`) | Servers, clients, consent; hands a run its `McpToolbox` of `McpToolOffer`s |
| `ToolCallingTextGenerationApi`, `McpToolLoop` (`providers/llm`) | The tool-use loop |
| `CodeExecutionToolSource`, `CodeExecutionSettings`, `ScriptSandboxRuntime` | Code execution: the `run_script` tool over the host's tools, the keeper's opt-in, the sandbox contract |
| `QuickJsScriptSandbox` (`desktopMain`) | The desktop sandbox: QuickJS in WebAssembly on Endive |

## Protocol

The client is dual-era. It never declares sampling or roots, so a server cannot borrow the host's model or read its folders.

- **Modern (`2026-07-28`):** stateless. Every request carries `_meta` with `io.modelcontextprotocol/protocolVersion`, `clientInfo` and `clientCapabilities`. The capabilities are `{"extensions":{"io.modelcontextprotocol/tasks":{}}}` on every request, plus `"elicitation":{"form":{},"url":{}}` on a `tools/call` (and its retries and task requests) made where a keeper can be asked. Capabilities are declared per request; nothing carries over from an earlier one. Over HTTP it also sends `MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name` (for `tools/call`) and the `Mcp-Param-*` headers a tool's `x-mcp-header` annotations ask for. A tool whose annotations break the rule is dropped from the listing. Header values outside visible ASCII use the `=?base64?…?=` form.
- **Era detection:** the first request is `server/discover`. A result means modern. A modern error (`-32020`…`-32022`) means modern. If the server speaks only versions The Aive does not, the client stops rather than falling back. Any other answer, or silence for 15 seconds, means a legacy server: `initialize` (offering `2025-11-25`), then `notifications/initialized`. Over HTTP the legacy `Mcp-Session-Id` is echoed, and an expired session (404) is renewed once. A 401 or 403 is reported as a credential refusal.
- **Responses:** JSON or a request-scoped SSE stream; the response with the request's id is taken and notifications before it are skipped. Bodies are capped at 4 MB, requests time out after 120 s, and redirects are never followed.
- **Handshake era:** `initialize` offers empty capabilities, and nothing below applies: no elicitation, no tasks. A handshake-era server that answers with a task is refused.
- **Not used:** resources, prompts, subscriptions (`subscriptions/listen`, `notifications/tasks`), sampling and roots.

### Elicitation (multi round-trip requests)

A modern server may answer `tools/call` with `resultType: "input_required"`: an `inputRequests` map (key → `elicitation/create` request) and/or an opaque `requestState`.

- Every request in the map must be `elicitation/create`, there must be at most 8, and the call must have a keeper to ask; otherwise the call ends with `McpInputRequiredException` and nothing is answered. A map that mixes elicitation with sampling or roots is not answered in part.
- Each request is put to the keeper in turn. The call is then retried as a new JSON-RPC request (new id, same name, arguments and headers) with `inputResponses` (key → `ElicitResult`) and the `requestState` echoed byte for byte. A round with only `requestState` is retried at once. Only the latest round's answers and state are sent; a round without `requestState` sends none. At most 4 retries, then the call ends.
- **Form mode** (`mode` `form` or absent): `requestedSchema` must be a flat object of primitive properties (string with `minLength`, `maxLength`, `format` `email`/`uri`/`date`/`date-time`; number or integer with `minimum`/`maximum`; boolean; single-select `enum`, `enumNames` or `oneOf` of `{const,title}`; multi-select array of `enum` or `anyOf` items with `minItems`/`maxItems`). Defaults prefill the form. The answer is validated before it can be sent and is typed: integers and numbers as JSON numbers, booleans, string arrays. Anything else (a nested object, an unknown type, more than 32 fields) is answered `cancel` without asking.
- **URL mode:** the address must be `https://` or `http://`; anything else is answered `cancel` without asking. It is shown in full as plain, unlinked text with its host set apart, and with a warning for Punycode, non-ASCII hosts, an embedded user name, or cleartext. **Open in browser** hands it to the system browser (`LocalUriHandler`) only on the keeper's tap; The Aive never fetches or reads it. **I'm done, continue** then answers `accept`, and the server decides on the retry whether the out-of-band step finished.
- Every request is titled with the server's name and shows its address and the tool being run; the server's message is quoted as its words. The form says never to type passwords, API keys or other secrets, and a field whose name looks like one (password, token, API key, PIN, card number…) carries its own warning.
- Answers are **Send** (`accept` with content), **Decline** (`decline`) and **Cancel** or dismissing (`cancel`). An unanswered request is cancelled after 10 minutes. Platforms without a UI pass no gate, and their calls do not declare elicitation.

### Tasks (`io.modelcontextprotocol/tasks`, SEP-2663)

A modern server may answer `tools/call` with `resultType: "task"` and a task (`taskId`, `status`, `ttlMs`, `pollIntervalMs`). `McpClient.callTool` drives it internally and returns the final `CallToolResult`, so the loop sees an ordinary result.

- `tasks/get {taskId}` is polled until `completed` (its `result` is the tool result), `failed` (its JSON-RPC `error` is raised) or `cancelled`. Polls wait the longer of the server's `pollIntervalMs` (clamped to 0.5–60 s) and a backoff that starts at 1 s and doubles to 15 s. The whole wait, keeper input included, is capped at 30 minutes and 1,000 polls.
- `input_required` carries `inputRequests`; elicitations are put to the keeper as above and answered with `tasks/update {taskId, inputResponses}`. A key is asked once, however many polls repeat it. Input that cannot be given ends the call.
- If the wait ends for any reason other than the task ending (the run is cancelled, the cap is reached, input cannot be given, a poll fails), `tasks/cancel {taskId}` is sent once, best effort, within 5 seconds. Cancellation is cooperative: the server may still finish the work.
- Over HTTP, `tasks/get`, `tasks/update` and `tasks/cancel` carry `Mcp-Method` and `Mcp-Name: <taskId>`, and, like every modern request, `_meta`.
- Task ids are not persisted; a task outlives neither the run nor the app. The 2025-11-25 experimental tasks (`task` request parameter, `tasks/result`, `tasks/list`) are not used.

## Configuration

Keeper's room › Connections › **MCP servers** (`McpServerConnections`). A server is added by address and an optional bearer token on every platform, or by command on the desktop. Each row has **Check** (connect and count its tools), **Always allow** or **Ask each run**, and **Remove** (which deletes its token).

- An address must be `https://`, or `http://` to this device or a private network (`CleartextPolicy`), and may not embed a user name or password.
- A command is split into a fixed argument list (`McpCommandLine`: quotes only; no variables, globs, pipes or redirection) and started directly in the home folder, never through a shell. Its stderr is discarded.
- On the web, a server is reachable only if its CORS policy admits The Aive's origin.
- Tokens are not part of the setup file.

## Read-only MCP catalog discovery

Keeper's room › Connections › **MCP servers › Add an MCP server** offers an
explicit **Search catalog** action for public MCP servers. The query is sent
only on keeper action (at least three characters), over HTTPS to
`https://azphalt.store/api/mcp/servers?search=...`. The Store's adapter
consumes the standard MCP Registry v0.1 read API, supporting public and
deployer-configured compatible registry origins. Search does not contact the
advertised server, run tools, send a bearer token or add a connection.

`mcp/McpRegistryDirectory.kt` accepts only `schema:1`,
`source:mcp-registry-v0.1`, `kind:mcp-server`, `installable:false`,
`verification:registry_metadata_only` entries with a usable public HTTPS
Streamable HTTP endpoint. It rejects credential-bearing or private URLs,
forged verification status and non-HTTP transports. Publisher versions are
opaque strings retained as metadata, not converted into ANS identities.
Search results are leads only; **Use address** fills the existing name and
address fields and **clears any previously entered bearer token or command**
so credentials intended for another destination cannot follow the selection.
**Add** is still required to connect. The existing per-run MCP tool consent
remains unchanged.

There is no vendor-branded execution runtime, automatic installation,
provider promotion, tool-permission inheritance or mandatory external
control plane. This keeps the architecture compatible with external
Kubernetes-native agents and gateways through standard MCP and A2A, while
Aive retains its native execution and authority model.

## Interoperability with external MCP registries and gateways

The [agentregistry](https://github.com/agentregistry-dev/agentregistry)
implementation provides an **opt-in** MCP Registry v0.1 compatibility
read API. It is disabled by default; an operator enabling
`AGENT_REGISTRY_MCP_REGISTRY_COMPAT_ENABLED=true` can configure the
Azphalt storefront's fixed `MCP_REGISTRY_BASE_URL` to the public HTTPS
registry base (including the optional compatibility path prefix).
Azphalt then queries `/v0.1/servers`. Aive's existing **Search catalog**
shows credential-stripped public Streamable HTTP leads. **Use address**
only fills the connection form and clears previous credentials; adding
a server and granting per-run tool consent are separate actions.

AgentRegistry's `/v0/agents`, `/v0/skills` and `/v0/prompts` are its
native resource APIs, not MCP server connection URLs. Its server catalogue
may contain synthesized localhost endpoints: these are intentionally
discarded by Azphalt and Aive's public URL filters. The upstream
[agentgateway](https://github.com/agentgateway/agentgateway) can front an
MCP server; Aive connects through a keeper-approved HTTPS Streamable HTTP
endpoint exposed by that gateway, without adopting its own authentication
or policy decisions as Aive permissions. Neither project is a mandatory
runtime or privileged control plane for Aive.

## The loop

`TextLlmProvider` takes an optional `McpToolSource`. A run's toolbox is offered when three things hold: the API implements `ToolCallingTextGenerationApi`, the run has no provider-side plan gate, and a server lists at least one tool. Today that means Anthropic Messages (`tool_use`), OpenAI-compatible chat (`tools`, so every hosted provider and store `llm` package) and Gemini (`functionDeclarations` with `parametersJsonSchema`; thought signatures are replayed). OpenAI and xAI Responses and the workspace agents get no tools yet.

- Tools are offered as `m{n}_{tool}`: letters, digits and `_`, at most 64 characters, at most 64 tools per run. Each description names its server.
- `McpToolLoop` runs at most 8 rounds of calls, and at most 8 calls per round; extra calls are answered with an error and not run. After the last round, one more turn is requested with tools refused. Malformed arguments are answered with an error and not run.
- If the first request, the one that offers the tools, fails, the run is retried once without tools. This covers models and services that do not take tools.
- Each call is reported as a `Progress` event ("Calling get_weather on Weather").
- The answer artifact of a run that called tools is labelled `ExternalContent` (`aive.provenance`, which the context-taint rule reads: a later step that can change something is held at a gate) and carries `contentProvenance=external-tool-results` and `mcpToolCalls` (`server/tool, …`).

## Authority

- **Consent is per server, per run.** A server's tools run only if it is **Always allow**, or if the keeper answered the gate for it in this run (the workflow run id, or the task run id outside one). The first call asks. The question names the tool, the server and the arguments that would leave the device. The answers are **Allow for this run**, **Always allow** and **Don't allow**. An unanswered question is refused after 10 minutes. Platforms without a UI use `McpConsentGate.DenyUnasked`. A refusal reaches the model as an error result, and nothing is sent to the server.
- Listing a server's tools is not consent: listing sends no task content.
- **Elicitation is the keeper's own disclosure.** It reaches only calls the keeper already allowed. The Aive sends what the keeper types or picks to that server only, never to the model or into run artifacts (a result the server returns may repeat it, and is fenced like any other). A URL is opened only on the keeper's tap, in the system browser.
- **Results are external content.** Each one is fenced (`⟦external tool result · MCP server "…" · tool "…"⟧ … ⟦end external tool result⟧`), so a server cannot close the fence early, and the model is told to treat it as data. The Aive never executes it (invariant 3).
- **Repository invariant 4 is unchanged.** MCP calls are not `RepositoryOperation` tasks and never receive The Aive's repository credentials. A server that changes a repository with its own credentials is the keeper's explicit choice, gated by the consent above. Workspace agents, which write branches, get no MCP tools.
- Tool arguments are written by the model from the already redacted task payload (invariant 5); the consent question shows them.

## Code execution

Instead of calling tools turn by turn, a model can write one short script that calls the run's tools as functions; only the script's final value goes back to it. Intermediate results (a long listing, a page of search hits) stay in the sandbox, which saves tokens and rounds.

- **Opt-in.** Keeper's room › Connections › MCP servers › **Script sandbox**, off by default (`CodeExecutionSettings`, `aive.mcp.codeExecution.enabled.v1` in general settings). Shown only where a sandbox exists.
- **Shape.** `CodeExecutionToolSource` wraps the `McpHost` and is what the desktop hands the text providers. When it is on and the run has at least one MCP tool, it adds `run_script` (first, within the 64-tool cap) with one string argument, `script`. Off, or with no MCP tools, the run sees exactly what the host offers.
- **Script.** JavaScript, the body of a function: `return` the final value (a string, or anything `JSON.stringify` accepts). Each of the run's other offers is `tools.<offer name>(args)`, synchronous, returning that tool's text (`JSON.parse` it for JSON); a refused or failed call throws. `async`/`await` are not supported. The script cannot call `run_script`.
- **Bridge, no new authority.** A bridged call is an ordinary `McpToolbox.call` on the host's toolbox for the same run, so the per-server, per-run consent applies unchanged: the first call to a server asks, and a refusal reaches the script as an error and nothing is sent. The script receives the tool's unfenced text (`McpToolOutcome.rawText`); the model never does.
- **Result.** The final value is fenced as external content (`⟦external tool result · Script sandbox · tools "Server/tool", …⟧`) and cannot close its fence, and the run's answer carries `contentProvenance=external-tool-results` like any other tool use. A failure's console output is fenced the same way.
- **Limits** (`ScriptLimits`): script 20,000 characters; 16 bridged calls; result 16,000 characters (a longer one is an error, not a truncation); 10 s of computing time, not counting time spent waiting on a server or on the keeper's approval; 12 minutes in all; 64 MB of WebAssembly memory; 4,000 characters of console output, kept only to explain a failure. A run is still bounded by the loop's 8 rounds of 8 calls, so one run makes at most 64 scripts.
- **Desktop sandbox** (`QuickJsScriptSandbox`): [quickjs4j](https://github.com/roastedroot/quickjs4j) (QuickJS compiled to WebAssembly with Javy, ahead-of-time translated to JVM bytecode) on [Endive](https://github.com/bytecodealliance/endive), formerly Chicory, a pure-Java WebAssembly runtime: no native code, no JNI. The guest gets WASI with stdout and stderr only, into a bounded buffer: no preopened directories, no environment or arguments, and WASI preview 1 has no sockets. Its one host module with an effect is `aive` (`tool_names`, `call`, `finish`). Memory is capped by the memory factory; time by a watchdog that interrupts the guest thread, which Endive's compiled code checks. Endive's compiled mode has no fuel metering, so computing time stands in for an instruction budget. Cancelling the run interrupts the guest.
- **Android and the web.** No sandbox yet, so the tool is never offered and the opt-in is not shown. quickjs4j is plain JVM bytecode and may run on Android, but it is unmeasured there (class size, memory, start-up). In the browser, a nested WebAssembly QuickJS (for example quickjs-emscripten) would work but is not bundled. `ScriptSandboxRuntime` is the seam for both.

## Follow-ups

- Elicitation (MRTR `input_required`) and the Tasks extension build on `McpClient` and `McpInputRequiredException`.
- Code execution on Android and the web (see Code execution), and `async` scripts with parallel bridged calls.
- Sampling and roots are not declared and are not planned without their own consent design.
- Durable task ids (to resume polling after a restart) and `notifications/tasks` over `subscriptions/listen`.
- A code-execution sandbox can be offered as another `McpToolSource`.
- Azphalt `kind:"mcp"` packages: the manifest's `mcp` block is untyped (`JsonElement`) and the store refuses it in `llm` packages. Registering servers from a package needs that block specified (transport, address or command, install inputs, signing of the command) before `McpHost` can accept `packageId` entries.
- Per-role server selection, OpenAI and xAI Responses function calling, and stdio on Android.

## Verification

`shared/src/commonTest/.../mcp/`: framing and header encoding (`McpJsonRpcTest`); the modern exchange with `_meta`, mirrored headers and SSE, the legacy fallback, and modern-error handling (`McpClientTest`); offers, the consent gate, Always allow persistence and fencing (`McpHostTest`). `providers/llm/.../McpToolLoopTest`: a round trip through `TextLlmProvider`, the bound, Anthropic over HTTP, and the OpenAI-compatible and Gemini wire formats. `CodeExecutionToolSourceTest`: off by default, the offer, result-only return and its fence, the bridged-call bound, recursion and unknown names refused, consent through a real `McpHost`, script and result size limits. `desktopTest/.../QuickJsScriptSandboxTest`: the real sandbox with a fake bridge (values, synchronous tool functions and thrown refusals, no network or process globals, the time limit, bridge waits not counted, the memory cap, script errors). A live server acceptance test remains to be done.
`shared/src/commonTest/.../mcp/`: framing and header encoding (`McpJsonRpcTest`); the modern exchange with `_meta`, mirrored headers and SSE, the legacy fallback, and modern-error handling (`McpClientTest`); multi round-trip elicitation (answers, `requestState` echo, new ids, the round cap, refusing sampling), form parsing and validation, URL vetting, the prompting gate and the host's labelling (`McpElicitationTest`); task polling, `tasks/update` deduplication, `Mcp-Name` routing, backoff, failure, the cap, and `tasks/cancel` on cancellation (`McpTaskTest`); offers, the consent gate, Always allow persistence and fencing (`McpHostTest`). `providers/llm/.../McpToolLoopTest`: a round trip through `TextLlmProvider`, the bound, Anthropic over HTTP, and the OpenAI-compatible and Gemini wire formats. A live server acceptance test remains to be done.
