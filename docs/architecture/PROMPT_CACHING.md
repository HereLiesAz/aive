# Prompt reuse and caching

Prompt caching is a provider optimization. It is not workflow semantics and a cache hit must never be required for correctness.

## Neutral prompt structure

A task request is divided into two ordered regions:

1. `stablePrefix` — standing instructions, role instructions, repository conventions, approved specifications, architecture, approved verification contracts, and other context expected to repeat.
2. `dynamicContext` — the current task objective, current artifacts, retry-specific failure context, recent events, and other volatile information.

Stable context should remain deterministic and come first when provider behavior permits it. Dynamic material belongs after it.

## Provider-neutral contract

`PromptContext` carries stable blocks, dynamic blocks, a `PromptReusePolicy`, and an optional logical cache namespace.

Provider capability reporting may describe mechanisms such as:

- unsupported
- implicit prefix reuse
- explicit reusable context
- explicit breakpoints
- session-scoped reuse

The workflow engine may prefer reuse but must behave identically when no cache exists.

## Providers without cache controls

A provider that documents no prompt-cache resource or cache-hit controls must not fabricate cache telemetry. The Aive still keeps stable prompt structure deterministic so provider-side reuse remains possible without becoming a dependency.

## Hosted text providers

`TextLlmProvider` renders a `CacheablePrompt`: role instructions and the `stablePrefix` blocks first, then the task, acceptance criteria, `dynamicContext`, repository and artifacts. The request's `reusePolicy` travels with it. A memory interruption extends only the dynamic part, and the plan draft appends its instruction there too, so every call in a run shares the same prefix. The provider declares its API's `promptCaching` capabilities and reports `UsageReported.cacheHitFraction` as cached input tokens over all input tokens whenever the response says how many were cached.

| Adapter | Mode | Request | Cached tokens read from |
| --- | --- | --- | --- |
| `AnthropicMessagesApi` | `ExplicitBreakpoints` | Prefix in `system`, task in the user turn. `PreferCache` puts `cache_control: {type: "ephemeral"}` on the prefix block; `ProviderDefault` and `DisableCache` send the same shape without it | `usage.cache_read_input_tokens`; input counts `input_tokens + cache_creation_input_tokens + cache_read_input_tokens` |
| `OpenAiResponsesApi`, `XaiResponsesApi` | `ImplicitPrefix` | One input string, prefix first | `usage.input_tokens_details.cached_tokens` |
| `GeminiGenerateContentApi` | `ImplicitPrefix` | One text part, prefix first (implicit caching; no `cachedContents` resource) | `usageMetadata.cachedContentTokenCount` |
| `OpenAiCompatibleChatApi` | `ImplicitPrefix` | One user message, prefix first | `usage.prompt_tokens_details.cached_tokens`, or DeepSeek's `usage.prompt_cache_hit_tokens` |

Implicit caching cannot be switched off from the request, so `DisableCache` changes nothing for those adapters. Prefixes below a provider's minimum cacheable length are simply not cached. APIs that implement only `generate(prompt: String)` receive `CacheablePrompt.text` and keep the default `Unsupported` capability.

## Other providers

A future provider may map the same neutral structure onto explicit cached-content resources, cache keys, or session-scoped reuse.

Those mappings belong inside provider adapters. Provider cache IDs never become domain identity.

## Good stable-prefix candidates

- swarm-wide operating rules
- role definitions and standing instructions
- repository conventions
- architecture constraints
- approved product specifications
- approved pre-code verification contracts
- tool-use rules
- unchanged environment specifications

## Poor stable-prefix candidates

- current retry errors
- latest test output
- timestamps
- volatile branch/PR state
- current task-specific requests
- recent provider messages

## Approved verification artifacts

Approved pre-code verification artifacts are particularly useful reusable context because the same specification and verification contract may be consumed by implementation, Crash Test Dummy, QA, review, and recovery work.

If an approved artifact changes, that is a new logical version and therefore a new cache identity.

## Observability

When a provider exposes cache metrics, The Aive may record operational values such as reusable input tokens, cache-hit tokens, cache writes, estimated cost difference, and latency impact.

These metrics are diagnostic only. They must never change task correctness, approval state, or verification requirements.
