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

## Follow-ups

- Elicitation (MRTR `input_required`) and the Tasks extension build on `McpClient` and `McpInputRequiredException`.
- A code-execution sandbox can be offered as another `McpToolSource`.
- Azphalt `kind:"mcp"` packages: the manifest's `mcp` block is untyped (`JsonElement`) and the store refuses it in `llm` packages. Registering servers from a package needs that block specified (transport, address or command, install inputs, signing of the command) before `McpHost` can accept `packageId` entries.
- Per-role server selection, OpenAI and xAI Responses function calling, and stdio on Android.

## Verification

`shared/src/commonTest/.../mcp/`: framing and header encoding (`McpJsonRpcTest`); the modern exchange with `_meta`, mirrored headers and SSE, the legacy fallback, and modern-error handling (`McpClientTest`); offers, the consent gate, Always allow persistence and fencing (`McpHostTest`). `providers/llm/.../McpToolLoopTest`: a round trip through `TextLlmProvider`, the bound, Anthropic over HTTP, and the OpenAI-compatible and Gemini wire formats. A live server acceptance test remains to be done.
