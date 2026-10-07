# Decision corpus spec (shared by writer and verifier agents)

Aive is an app that orchestrates coding/research agents. Before a task runs, a tiny on-device classifier
answers typed questions about the task's text. You are producing (or checking) training data for it.

## Questions, the text each reads, and the exact meaning of each label

1. `ambiguous-objective` — text: a task objective.
   - `yes`: the objective does not say what to change or what outcome is wanted; a competent engineer
     could not start without asking ("make it better", "fix the thing", "do what we discussed",
     "improve performance" with no target or scope).
   - `no`: it names a concrete target AND an intended outcome, even if the work is large or vague words
     appear ("Improve cold start of the Android app to under 2 s" is `no`).
2. `architectural-decision` — text: a task objective.
   - `yes`: the work requires choosing or changing system structure: picking a technology, framework,
     storage, protocol or library family; drawing or moving module/service boundaries; designing a data
     model or API contract; introducing a cross-cutting pattern (DI, state management, event bus);
     migrating from one technology to another.
   - `no`: changes inside the existing structure, however large (a new screen, a bug fix, a version bump,
     a refactor within one module, editing docs ABOUT the architecture).
3. `multi-step-reasoning` — text: a task objective.
   - `yes`: needs several dependent steps where later steps depend on what earlier ones find or produce
     (investigate → fix → verify; change schema → backfill → switch readers; change across client and
     server; audit then remediate).
   - `no`: one self-contained change a developer would do in one go.
4. `chronological-context` — text: a task objective.
   - `yes`: doing it requires knowing the project's history: order of events, what changed since a time,
     when something started, why something was decided earlier, how something evolved.
   - `no`: anything else, INCLUDING product features about dates or time (date pickers, "sort by newest",
     timestamps, scheduling, "last updated" labels).
5. `verification-operation` — text: ONE part of an acceptance criterion (a short clause, as split on
   "and", commas and semicolons). Label = the check it calls for:
   - `lint`: static style/format/analysis passes (linters, formatters, static analysers, warnings).
   - `test`: automated tests pass or coverage (unit, integration, e2e, regression suites, specs).
   - `build`: it compiles, assembles, packages, links, produces an artifact/binary.
   - `health-check`: a deployed/running service is up, reachable, responding, healthy, serving traffic.
   - `source-verification`: claims/figures are backed by cited, traceable sources or references.
   - `evidence-check`: anything else, checked by inspection or sign-off: screenshots, matches a mockup,
     reviewed by someone, copy approved, behaves as described, changelog updated, a value is shown.

## Domains

- train: mobile apps; web frontends; backend services and APIs; developer tooling and CI; desktop apps
- validation: data pipelines and analytics
- test: game development; infrastructure and operations; documentation and research writing; embedded and IoT

## Style requirements (writers)

- Realistic, varied wording: imperative tickets, user requests, Jira-style titles, questions, casual
  chat, terse fragments, long sentences with context, occasional typos or lowercase. Vary length
  (3 to 40 words). Do NOT reuse sentence frames; no two rows should differ only by a swapped noun.
- At least a quarter of each class must be hard cases: near misses that share surface words with the
  other label (e.g. `no` architectural rows that mention "architecture", `no` chronological rows about
  dates, `ambiguous no` rows containing "improve").
- Stay inside the row's domain; invent concrete names (screens, services, files, tools) per domain.
- English only. No personal data.

## Output format

JSON Lines, one object per line, UTF-8, no trailing commas:
{"question": "<question id>", "text": "<text>", "label": "<label>", "domain": "<domain>", "split": "<train|validation|test>"}
