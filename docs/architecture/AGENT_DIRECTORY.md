# Agent Directory

Status: **implemented initial discovery and local federation; semantic embeddings, federated identity and gateway failover are not implemented**.

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
| Discovery / inventory | `a2a/AgentDirectory.kt` | Local connected agents, dismissible built-in examples, public A2A Registry search |
| Agent identity and connection | `a2a/A2aAgentConnections.kt`, `A2aDrifters.read` | Stable local `a2a:` ID, stored Agent Card URL, current card validation |
| Endpoint resolution and gateway | `providers/llm/.../A2aAgentProvider.kt` (`A2aClientApi`), `workflow/AgentProviderRegistry.kt` | Resolve first supported JSON-RPC endpoint from fresh card; enforce same origin, secure transport and explicit-only selection |

This retains the original **explicit-only** routing decision: remote A2A agents are
never selected automatically because a search result matched a task. Approval tasks,
context-taint gates and repository permissions stay engine-owned.

## Discovering agents

No registry is contacted on opening the page. Search requires a deliberate
**Search directory** action and at least two characters. That action sends
the search terms only, over HTTPS, to:

`https://a2aregistry.org/api/agents?search=<query>&limit=20&offset=0`

The public API is documented in
[prassanna-ravishankar/a2a-registry](https://github.com/prassanna-ravishankar/a2a-registry).
It is a third-party service, independent of Aive. No token, app history, workflow
prompt, artifact or connected-provider list goes with the search. It sees the
network request, including the client IP. Its own terms apply. If unavailable,
local matches remain visible and the source failure is reported by name.

`AgentDirectorySource` is the adapter seam for additional registries.
`LocalAgentDirectorySource` reads the stored connections and optional examples
without network access. `PublicA2aRegistrySource` parses the public registry's
`PaginatedAgents.agents` records (not an invented A2A wire operation). Every
registry-supplied Agent Card URL must be a public HTTPS domain; local IPs,
loopback/private hostnames, credentials, query strings, fragments, malformed
ports and oversized URLs are rejected. HTTP redirects are not followed, and
responses are capped at 384 KiB with a 15-second timeout. Only the first 20
registry records are considered. Results are deduplicated by card URL.

Search relevance is currently **deterministic word matching** against name, skills
and description. It is not vector/embedding semantic search and it does not infer
a skill or grant authority. Registry health and task-test fields are displayed
only as **registry-reported** observations, never as verified trust or a guarantee
of endpoint availability. A registry can supply false or stale metadata.

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
cannot write connections or control provider routing. A future semantic search
backend may rank candidates, but user consent and card revalidation remain
separate operations. A federated ANS implementation will need explicit
identity bindings and renewal/revocation semantics. Gateway endpoint failover
will require independently governed origin/credential constraints and must not
move a pinned role to another operator without authorization.

Tests: `AgentDirectoryTest`, `A2aDriftersTest`,
`A2aAgentConnectionsTest`, `A2aAgentProviderTest`. The independent public
registry schema is the `PaginatedAgents` model from the linked project.
