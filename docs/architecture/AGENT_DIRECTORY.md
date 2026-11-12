# Agent Directory

Status: **SearchA2A-backed indexed semantic discovery and local federation implemented; publisher identity verification and cross-endpoint gateway failover are not implemented**. Semantic ranking depends on SearchA2A Workers AI availability; explicit keyword fallback is labeled.

The Agent Directory lives under **Keeper's room › Connections › Connect remote agent**.
It is not an Azphalt Store category or a second agent-adoption screen.

## Purpose and boundaries

An Azphalt package is signed, installed and governed by the Store. An A2A agent is a
service operated somewhere else. The directory helps the keeper **find** a service;
the provider connection enables Aive to **call** it only when the keeper assigns that
provider to a swarm role. Discovery alone can never start a workflow, add a provider,
approve a gate, or send task context.

The directory has three layers:

| Layer | Implementation | Scope |
|---|---|---|
| Discovery / inventory | `a2a/AgentDirectory.kt` | Local connections and suggestions; SearchA2A's public indexed semantic search with a read-only published-catalog fallback |
| Agent identity and connection | `a2a/A2aAgentConnections.kt`, `A2aDrifters.read` | Stable local `a2a:` ID, stored Agent Card URL, current card validation |
| Endpoint resolution and gateway | `providers/llm/.../A2aAgentProvider.kt` (`A2aClientApi`), `workflow/AgentProviderRegistry.kt` | Resolve first supported JSON-RPC endpoint from fresh card; enforce same origin, secure transport and explicit-only selection |

This retains the original **explicit-only** routing decision: remote A2A agents are
never selected automatically because a search result matched a task. Approval tasks,
context-taint gates and repository permissions stay engine-owned.

## Discovering agents

No external service is contacted on opening the page. **Search directory** under
Keeper's room › Connections requires at least two typed characters and an explicit
keeper action. Aive sends only those search terms to SearchA2A's public HTTPS
`GET https://searcha2a-api.hereliesaz.workers.dev/api/v1/directory?q=...`.
SearchA2A is the discovery system shared with the Azphalt Store, avoiding an
independently maintained registry parser and crawler inside Aive. The old direct
`a2aregistry.org` adapter has been removed.

SearchA2A reads its published metadata-only index (mirrored to the `agent-feed`
branch) and returns up to 20 matches. It uses Workers AI embeddings with semantic
similarity when available, reporting `match_mode: semantic`. Otherwise the
service reports `keyword-fallback`; Aive labels this honestly. Both are only
relative relevance rankings, never reliability, identity, or permission scores.

If the SearchA2A Worker is unavailable or rate-limited, Aive can still read
`https://raw.githubusercontent.com/HereLiesAz/searcha2a/agent-feed/site/api/v1/azphalt-listings.json`,
filtering that published snapshot using local deterministic keyword relevance
and labelling the result **cached catalog**. It does not send the search terms
to that GitHub raw-CDN URL, though the network request reveals the client IP.
Failure of both sources leaves local connected-provider and suggestion matches
available, with the SearchA2A source shown as unavailable.

`AgentDirectorySource` remains the adapter seam for testability. The implementation
is `SearchA2aDirectorySource`, not a second registry. Responses are capped at
900 KiB, must declare `schema: 1` and `source: searcha2a`, and only
`kind: a2a-agent`, `installable: false`, `verification: card_read_only`
records are accepted. Invalid HTTPS hosts, IP literals, private/local hostnames,
credentials, query strings, fragments, malformed ports, and oversized URLs are
rejected. Redirects are not followed. Results are deduplicated by Agent Card URL,
with locally approved connection state preserved independently of the search hit.

No credential, workflow prompt, task artifact or connected-provider inventory is
sent to SearchA2A. The query and caller IP reach the SearchA2A gateway. The indexed
catalog is stale until the next crawl and its card metadata is not evidence of
agent uptime or publisher identity. Workers AI may consume Cloudflare inference
quota, governed by SearchA2A's public per-IP limiter.
## Resolving and connecting

1. **Use address** copies a candidate's card URL into the ordinary connection
   form. This does not fetch the card or send a task.
2. **Connect agent** fetches that card independently, with no credentials,
   redirect following or implicit permissions. It requires a compatible JSON-RPC
   interface before storing the URL and sanitized name in `A2aAgentConnections`.
   The optional bearer credential follows the existing platform credential flow.
3. In the Swarm role editor the keeper explicitly assigns that connected
   provider. No role is invented from registry text.
4. When work is sent, `A2aClientApi.endpoint()` re-reads the card and checks
   the endpoint's scheme and same-origin restriction before forwarding approved
   context or the token. There is no silent substitute on provider failure.

The local `a2a:<host>` ID is a stable connection key, **not a globally
authenticated agent name**. Fetching an Agent Card proves reachability and
protocol compatibility, not publisher identity. There is currently no certificate,
signature chain, DNS ANS verification or trust federation for A2A operators.

## Extending safely

A new `AgentDirectorySource` can supply candidates and report failures; it
cannot write connections or control provider routing. SearchA2A may rank candidates semantically, but user consent and independent
Agent Card revalidation remain separate operations. A federated ANS implementation will need explicit
identity bindings and renewal/revocation semantics. Gateway endpoint failover
will require independently governed origin/credential constraints and must not
move a pinned role to another operator without authorization.

Tests: `AgentDirectoryTest`, `A2aDriftersTest`,
`A2aAgentConnectionsTest`, `A2aAgentProviderTest`. The upstream response schemas
are defined in [SearchA2A's API reference](https://github.com/HereLiesAz/searcha2a/blob/main/docs/API.md).
