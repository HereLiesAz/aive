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

## Jules

The currently exposed Jules REST activity/session model provides session continuity but does not document a first-class prompt-cache resource or exact cache-hit controls. The Jules adapter should therefore use only behavior that the API actually exposes and must not fabricate cache telemetry.

For separate sessions, The Aive should still keep stable prompt structure deterministic so provider-side reuse remains possible without becoming a dependency.

## Other providers

A future provider may map the same neutral structure onto implicit prefix caching, explicit cached-content resources, cache keys, or cache-control breakpoints.

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
