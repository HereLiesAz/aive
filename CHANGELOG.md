# Changelog

All notable changes to **The Aive** are documented here from the current product line forward. Every merge to `main` ships a `0.9.6.BUILD` build; all of them are grouped under the `0.9.6` GitHub Release.

## 0.9.6 — from 2026-09-19

- Memory now links similar memories across sessions much more often: the association step offers memories of the same kind first, then other kinds, and skips memories already condensed, so related memories, including ones that disagree, are far more likely to be recalled together. Within a group of similar memories, the ones that agree can now condense while the ones that differ stay separate.
- Memory never merges memories that disagree on a value, whichever engine runs condensation, and a merged memory must keep its sources' values exactly; local and hosted clerks are held to the same rule as the built-in one.
- Each local memory clerk now ships as its own small adapter on one shared base: installing a clerk downloads the base once, plus about 18 MB for the clerk. Each memory clerk and orchestration helper now has its own training notebook, so one can be retrained without the others.
- Plan approval is now owned by the engine: the provider drafts a plan before any run starts, and the run begins only after you approve it. Rejecting a plan cancels nothing, and a held plan survives a restart unchanged.
- Added a training pipeline for the local memory clerks (`tools/memory_training`): a corpus recorded from the running memory layer in the app's own answer contract, a Kaggle notebook, and catalog registration. Local memory runtimes now send the chat-templated prompt the clerks are trained on.
- The broken epoch-8 generative memory models are no longer offered for install; a memory stage set to a local model runs programmatically until a trained clerk is released.
- Orchestration helper adapters now train for at least 700 steps each; the Agent Router adapter had failed its gates with too few.
- Desktop can now install the released local orchestration helpers from Settings, as Android can; the `AIVE_LOCAL_ORCHESTRATION_SPECIALISTS` and `AIVE_ORCHESTRATION_SPECIALIST_MODE` environment switches are gone.
- Desktop memory stages set to a local model now run the installed epoch-8 models (install/remove per stage on the Memory screen) instead of falling back to programmatic.
- Large model downloads on Android and desktop now stream to disk instead of being buffered in memory first.
- Removed the Jules provider. Any Jules key still stored on a device is deleted the next time credentials load.
- Added a Memory screen on every platform: the memory pipeline live in the terrarium, per-stage engines, tuning, queue retry/discard, on-device model install/remove, and forget/export/import of stored memory. Desktop and web now have memory.
- Memory stages default to deterministic on-device clerks (no download) and can each be switched to a local model or a configured hosted provider; memory can be turned off or its consolidation paused.
- Moved agent memory to SQLite (SQLDelight) with a one-time import of the older graph, and made memory commits, recall and association refresh substantially faster.
- Added composable role execution/data surfaces: AI providers, GitHub Actions, isolated JavaScript, and GitHub-backed JavaScript/Python can share attached CSV/TSV/XLSX/public Google Sheets, SQLite query surfaces, and native Mermaid flowcharts; writable local spreadsheet/SQLite surfaces accept replay-safe script mutations.
- Fixed Android upgrade continuity by restoring the GitHub APK lineage to its original `com.hereliesaz.haive` application ID while keeping Google Play on `com.hereliesaz.aive`.
- Added durable write-through persistence for user drafts, navigation, appearance, workflow composition, company-role editing, project setup, artifact browsing, agent messages, compute settings, and add-on navigation.
- Added portable `.ive` project save/load with detected-file lists and manual file selection on Android and Desktop.
- Made saved Android provider keys, repository tokens, and compute relay tokens synchronously durable.
- Added GitHub-flavor automatic update checks/downloads with Android installer handoff.
- Added Play-flavor update notifications that open Google Play without requesting sideload permissions.
- Added resumable, SHA-verified Android downloads for large local memory/orchestration release assets, including retained partial files and HTTP Range resume.
- Added Azphalt catalog recovery when an app-scoped workflow/role search incorrectly returns empty, with local `targetApps` filtering for current and legacy Aive host IDs.
- Exposed Azphalt model assets alongside workflows and roles, including a dedicated Models filter while keeping model packages out of the workflow/role installer.
- Added full Android Azphalt model installation for ONNX, TFLite, LiteRT, MediaPipe/TFLite task bundles, Sherpa-ONNX bundles, Vosk bundles, and generic model assets, with verified/resumable remote members, app-private persistence, durable inference-registry paths, updates with rollback, uninstall, and direct .azp import support.
- Grouped all `0.9.6.x` build assets under one `v0.9.6` GitHub Release while preserving immutable four-part build tags.
- Moved four-part build-version derivation and patch-grouped GitHub Release policy into `HereLiesAz/workflows`.
- GitHub Release containers are patch-scoped; exact build identity remains in immutable four-part tags and artifact filenames.
- Android release builds are shrunk with R8; the Play mapping.txt is uploaded with each bundle through the shared `google-play-publish` action, and the GitHub flavor's mapping is kept as a workflow artifact.
- Workflow planning and plan repair run on the linked cloud LLM (Gemini, OpenAI, Claude, Grok, then hosted providers). The on-device planner and its 3.86 GB model download are removed, and leftover planner files are deleted on launch; with no LLM linked, runs start from the starter workflow.
- Desktop plans with the linked cloud LLM too. An optional local planner (fine-tuned Qwen2.5-1.5B via ONNX Runtime) is built but disabled: end-to-end testing showed the epoch-8 model emits the wrong schema, no roles or objectives, and malformed JSON.
- Android draws edge-to-edge on every API level; the shared Scaffold pads content by the system-bar insets.
- The BITCOS native library links with 16 KB page alignment, and release CI verifies alignment of every 64-bit native library in the Play bundle.
- DJL's tokenizer JNI library is built from source (`native/djl-tokenizer`) with 16 KB page alignment instead of DJL's 4 KB-aligned prebuilt, so every native library in the Play bundle now meets the 16 KB requirement; the release alignment check is strict. DJL's bundled desktop tokenizer natives (~55 MB) are excluded from Android packages.
- GitHub repositories get an automatic coding agent: OpenCode (open source) runs headless on GitHub Actions with a free OpenCode Zen model, no key. Aive installs `.github/workflows/aive-opencode-agent.yml` on the default branch (and keeps it in step with the app), streams every agent step into the run's progress through a check run, and returns the work as a pushed `aive/opencode-*` branch plus patch. The GitHub token needs Contents, Actions, Checks and Workflows access.
- Jules is never chosen automatically; it runs only where a role names it, since it reports almost nothing between plan approval and completion.
- New hosted LLM providers with free tiers: Ollama Cloud, Z.ai (GLM-4.7-Flash), Cloudflare Workers AI (`ACCOUNT_ID:API_TOKEN`), and three that work with no key at all: Kilo Gateway, LLM7 and OVHcloud AI Endpoints. Keyless providers are linked by saving a blank key and are tried last for planning.
- A failed provider task now records the provider's own error (for example an HTTP 429 quota message) as its last status and in the timeline's failure reason, instead of only "Executor failed".
- Text LLM responses are labelled by the task's declared artifact, then its role, then whole words in the objective. Role instructions no longer count, so an orchestrator's plan is no longer filed as a failure analysis.
- The live runtime acceptance test accepts any hosted provider, including keyless ones (`AIVE_LIVE_PROVIDER=kilo`), prints per-gate evidence, and fails fast with the provider's reason.
- GitHub-release crash/ANR reporting stays on by default during pre-release (turn it off in Settings) and becomes opt-in at the production release (`CrashReportPolicy.DEFAULT_ENABLED`). It is now disclosed in `docs/PRIVACY.md`; Play builds still have no crash reporter.
- The GitHub-release installed-Gemini accessibility bridge now shows a disclosure (what it reads, when, and where the text goes) before sending you to Accessibility settings, and stays inert unless the in-app opt-in is on. The disclosure, like the bridge, is absent from Play builds.
- Documentation brought in line with shipped behavior (privacy policy, threat model, architecture, index, versioning, roadmap checkboxes); stray patch and CI-trigger files removed.
- Repository operations placed on another device (`TaskExecutor.Distributed`) now need the same human approval as local ones.
- Compute-pool workers re-check every lease before running it: the workflow must validate, a repository mutation needs a completed approval in the submitted run, and anything using the worker's repository credentials must target a repository of a project linked on that device.
- BouncyCastle (`bcprov-jdk18on`, pulled in by the Android cryptography provider) is pinned to 1.85, fixing the five advisories on 1.83 (two critical, two high, one moderate).
- The LLM provider test suite compiles again, its stale tests were fixed, and CI now runs it. Along the way, a text provider resumed after an app restart with an already-approved plan no longer fails with "Plan result was not stored"; it runs the approved task once.
- Installing or updating the OpenCode workflow in a repository now always waits for a person to approve a plan that says it will be committed to the default branch. OpenCode results are size-bounded, and resume searches up to 1,000 recent runs.
- The web app encrypts saved provider and repository credentials (AES-GCM, non-extractable key in IndexedDB); existing plaintext entries are re-encrypted on first load.
- Nested workflow nodes (`TaskExecutor.NestedWorkflow`) now run on Android, Desktop and Web: each starts a child run of the referenced workflow on the same engine and storage, shows the child's progress, and completes or fails with it. After a restart the node reconnects to its existing child run. Nesting deeper than four levels and nesting that loops back to a workflow already in the chain are refused. Human approval gates inside the child stay pending until approved from the nested node's inspector.
- The OpenCode runner workflow was verified on real GitHub infrastructure (live check-run step log, a correct fix pushed to an `aive/opencode-*` branch, a parseable result artifact). The app's own dispatch path still needs a live run.

## Foundation — before 0.9.6

### Added

- Compose Multiplatform applications for Android, Desktop, JavaScript, and WebAssembly.
- Provider-neutral workflow domain and runtime.
- Jules provider integration.
- Governed company roles for planning, implementation, testing, review, recovery, and release.
- Durable workflow runs, retries, escalation, approval gates, artifacts, and progress.
- Animated H2G2 workflow mindmap with role personality motion, inherited branch motion, engagement wobble, and node-fill progress.
- Live workflow-to-mindmap projection and technical inspector data.
- Android/Desktop/Web CI and GitHub Pages deployment.
- The Aive brand and production icon system.
- Privacy policy documenting current local storage, provider data flow, credential handling, and tracking posture.

### Changed

- Product renamed from Geministrator to **The Aive**.
- Repository renamed to `aive`.
- Android namespace and Google Play application ID changed to `com.hereliesaz.aive`; GitHub releases keep `com.hereliesaz.haive` so existing installs upgrade in place.
- Progress is modeled as task-run execution data rather than agent-specific state.
- `main` is the canonical product branch.

### Removed

- Legacy IDE-era source trees and historical reconstruction scaffolding.
- Transitional version-two naming and branch references.
- Repository-generated text backup snapshots and backup workflow.
