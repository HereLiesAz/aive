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
| `McpHost` (`McpToolSource`) | Servers, clients, consent; hands a run its `McpToolbox` of `McpToolOffer`s |
| `ToolCallingTextGenerationApi`, `McpToolLoop` (`providers/llm`) | The tool-use loop |
| `CodeExecutionToolSource`, `CodeExecutionSettings`, `ScriptSandboxRuntime` | Code execution: the `run_script` tool over the host's tools, the keeper's opt-in, the sandbox contract |
| `QuickJsScriptSandbox` (`desktopMain`) | The desktop sandbox: QuickJS in WebAssembly on Endive |

## Protocol

The client is dual-era and declares no client capabilities.

- **Modern (`2026-07-28`):** stateless. Every request carries `_meta` with `io.modelcontextprotocol/protocolVersion`, `clientInfo` and `clientCapabilities` (`{}`). Over HTTP it also sends `MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name` (for `tools/call`) and the `Mcp-Param-*` headers a tool's `x-mcp-header` annotations ask for. A tool whose annotations break the rule is dropped from the listing. Header values outside visible ASCII use the `=?base64?…?=` form.
- **Era detection:** the first request is `server/discover`. A result means modern. A modern error (`-32020`…`-32022`) means modern. If the server speaks only versions The Aive does not, the client stops rather than falling back. Any other answer, or silence for 15 seconds, means a legacy server: `initialize` (offering `2025-11-25`), then `notifications/initialized`. Over HTTP the legacy `Mcp-Session-Id` is echoed, and an expired session (404) is renewed once. A 401 or 403 is reported as a credential refusal.
- **Responses:** JSON or a request-scoped SSE stream; the response with the request's id is taken and notifications before it are skipped. Bodies are capped at 4 MB, requests time out after 120 s, and redirects are never followed.
- **Not yet:** `input_required` results (MRTR elicitation, sampling, roots) end the call with `McpInputRequiredException`, which carries the server's request. Resources, prompts, subscriptions and the Tasks extension are not used.

## Configuration

Keeper's room › Connections › **MCP servers** (`McpServerConnections`). A server is added by address and an optional bearer token on every platform, or by command on the desktop. Each row has **Check** (connect and count its tools), **Always allow** or **Ask each run**, and **Remove** (which deletes its token).

- An address must be `https://`, or `http://` to this device or a private network (`CleartextPolicy`), and may not embed a user name or password.
- A command is split into a fixed argument list (`McpCommandLine`: quotes only; no variables, globs, pipes or redirection) and started directly in the home folder, never through a shell. Its stderr is discarded.
- On the web, a server is reachable only if its CORS policy admits The Aive's origin.
- Tokens are not part of the setup file.

## The loop

`TextLlmProvider` takes an optional `McpToolSource`. A run's toolbox is offered when three things hold: the API implements `ToolCallingTextGenerationApi`, the run has no provider-side plan gate, and a server lists at least one tool. Today that means Anthropic Messages (`tool_use`), OpenAI-compatible chat (`tools`, so every hosted provider and store `llm` package) and Gemini (`functionDeclarations` with `parametersJsonSchema`; thought signatures are replayed). OpenAI and xAI Responses and the workspace agents get no tools yet.

- Tools are offered as `m{n}_{tool}`: letters, digits and `_`, at most 64 characters, at most 64 tools per run. Each description names its server.
- `McpToolLoop` runs at most 8 rounds of calls, and at most 8 calls per round; extra calls are answered with an error and not run. After the last round, one more turn is requested with tools refused. Malformed arguments are answered with an error and not run.
- If the first request, the one that offers the tools, fails, the run is retried once without tools. This covers models and services that do not take tools.
- Each call is reported as a `Progress` event ("Calling get_weather on Weather").
- The answer artifact of a run that called tools carries `contentProvenance=external-tool-results` and `mcpToolCalls` (`server/tool, …`).

## Authority

- **Consent is per server, per run.** A server's tools run only if it is **Always allow**, or if the keeper answered the gate for it in this run (the workflow run id, or the task run id outside one). The first call asks. The question names the tool, the server and the arguments that would leave the device. The answers are **Allow for this run**, **Always allow** and **Don't allow**. An unanswered question is refused after 10 minutes. Platforms without a UI use `McpConsentGate.DenyUnasked`. A refusal reaches the model as an error result, and nothing is sent to the server.
- Listing a server's tools is not consent: listing sends no task content.
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
- Azphalt `kind:"mcp"` packages: the manifest's `mcp` block is untyped (`JsonElement`) and the store refuses it in `llm` packages. Registering servers from a package needs that block specified (transport, address or command, install inputs, signing of the command) before `McpHost` can accept `packageId` entries.
- Per-role server selection, OpenAI and xAI Responses function calling, and stdio on Android.

## Verification

`shared/src/commonTest/.../mcp/`: framing and header encoding (`McpJsonRpcTest`); the modern exchange with `_meta`, mirrored headers and SSE, the legacy fallback, and modern-error handling (`McpClientTest`); offers, the consent gate, Always allow persistence and fencing (`McpHostTest`). `providers/llm/.../McpToolLoopTest`: a round trip through `TextLlmProvider`, the bound, Anthropic over HTTP, and the OpenAI-compatible and Gemini wire formats. `CodeExecutionToolSourceTest`: off by default, the offer, result-only return and its fence, the bridged-call bound, recursion and unknown names refused, consent through a real `McpHost`, script and result size limits. `desktopTest/.../QuickJsScriptSandboxTest`: the real sandbox with a fake bridge (values, synchronous tool functions and thrown refusals, no network or process globals, the time limit, bridge waits not counted, the memory cap, script errors). A live server acceptance test remains to be done.
