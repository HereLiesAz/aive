# The Aive

Back when ChatGPT hit the stage, before it was something OpenAI offered themselves, curious about hacking an LLM to get it to do things it wasn't supposed to, I remember being amazed by all the pseudo-GPTs and what they were able to do. Gemini popped out shortly after, having been called Bard, and it's what I had access to. Google's Agent Development Kit wasn't even heard of back then, and this project started as a way to get LLMs to legitimately talk to themselves. And with those pseudo-GPTs in mind, the idea of chaining them together to follow a production workflow came out. 

This project was abandoned until TMux Orchestrator hit the scene, and I realized I'd missed out on bringing that idea to life. Then the ADK hit (weirdly softly) and I picked it up again, under the name Geministrator. And Google released Jules, so I assumed there wasn't much need for this idea, turning it into what became IDEaz. But I guess I'm just slow, because immediately after I restarted, all sorts of apps like IDEaz came out, so that was abandoned too. 

Come to find out, both are now entire markets of their own, with very valuable companies in both. Shoulda stuck with it. So I've begun again--no time like the present and all--first building Geministrator into The Aive, and later I'll pick up IDEaz again.

## What it is

The Aive is an AI swarm orchestration manager featuring living workflows: an H2G2-inspired UI with an approachable, user-friendly fishbowl game-like interface for monitoring and managing roles and progress whose nodes represent real work and whose state comes from the runtime.

Workflow nodes may be performed by AI providers such as specialized model agents, LLMs, coordinated sub-workflow roles, by people, or by systems like GitHub Actions, test runners, and deployment jobs. Progress belongs to the task run, not to a particular kind of worker.

## Targets

- Android — application ID `com.hereliesaz.aive` (Google Play); GitHub-release builds keep `com.hereliesaz.haive` permanently so existing installs upgrade in place
- Desktop JVM
- Web — JavaScript and WebAssembly

## Repository map

- `shared/` — domain, orchestration, persistence, policies, runtime projection, shared Compose UI
- `providers/` — provider adapters: `jules/` (Jules) and `llm/` (native OpenAI, Anthropic, Gemini, and xAI adapters, OpenAI-compatible hosted providers, GitLab workspace agent)
- `androidApp/` — Android launcher
- `desktopApp/` — Desktop launcher
- `webApp/` — browser launcher (includes `mesh-crypto.js`, the Web Crypto AES-GCM backend for `MeshCrypto` on JS/Wasm)
- `computeRelay/` — Ktor WebSocket relay server for [distributed compute](docs/architecture/DISTRIBUTED_COMPUTE.md)
- `buildSrc/` — Gradle build logic for generating and verifying brand assets
- `native/` — Rust native code: `bitcos/` (BITCOS ternary-weight runtime) and `djl-tokenizer/` (16 KB-aligned Android build of DJL's tokenizer JNI library)
- `tools/` — offline tooling: Azphalt package builder (`azp/`), BitNet benchmark, specialist optimization pipeline, Google Play asset generator
- `vendor/` — `conveyance-h2g2`, the H2G2 style library included as a composite build
- `docs/` — current product documentation and privacy policy
- `docs/azphalt-packages/` — source for the Azphalt workflow/role packages packed by `tools/azp`
- `docs/swarm-terrarium/` — terrarium node-creature design brief, character manifests, role prompts, and source sheets
- `memory_and_orchestration_layers.ipynb` — Colab/Kaggle notebook for training the memory-clerk specialist models
- `branding/` — locked source brand and icon assets

## Documentation

Start with [`docs/README.md`](docs/README.md).

- [Architecture](docs/architecture/ARCHITECTURE.md)
- [Persistence](docs/architecture/PERSISTENCE.md)
- [Distributed compute](docs/architecture/DISTRIBUTED_COMPUTE.md)
- [Versioning and grouped releases](docs/VERSIONING.md)
- [Workflow add-ons / Azphalt](docs/architecture/ADDONS.md)
- [Live runtime acceptance](docs/architecture/LIVE_RUNTIME_ACCEPTANCE.md)
- [Prompt caching](docs/architecture/PROMPT_CACHING.md)
- [Branding](docs/BRANDING.md)
- [Privacy policy](docs/PRIVACY.md)
