# The Haive

Back when ChatGPT hit the stage, before it was something OpenAI offered themselves, curious about hacking an LLM to get it to do things it wasn't supposed to, I remember being amazed by all the pseudo-GPTs and what they were able to do. Gemini popped out shortly after, having been called Bard, and it's what I had access to. Google's Agent Development Kit wasn't even heard of back then, and this project started as a way to get LLMs to legitimately talk to themselves. And with those pseudo-GPTs in mind, the idea of chaining them together to follow a production workflow came out. 

This project was abandoned until TMux Orchestrator hit the scene, and I realized I'd missed out on bringing that idea to life. Then the ADK hit (weirdly softly) and I picked it up again, under the name Geministrator. And Google released Jules, so I assumed there wasn't much need for this idea, turning it into what became IDEaz. But I guess I'm just slow, because immediately after I restarted, all sorts of apps like IDEaz came out, so that was abandoned too. 

Come to find out, both are now entire markets of their own, with very valuable companies in both. Shoulda stuck with it. So I've begun again--no time like the present and all--first building Geministrator into The Haive, and later I'll pick up IDEaz again.

## What it is

The Haive is an orchestration product, not an IDE. It does not own source editing, terminals, or a generic file explorer. Its primary interface is the live workflow itself: an H2G2-inspired animated mindmap whose nodes represent real work and whose state comes from the runtime.

Workflow nodes may be performed by AI providers such as Jules, by people, or by systems such as GitHub Actions, test runners, and deployment jobs. Progress belongs to the task run, not to a particular kind of worker.

## Targets

- Android — application ID `com.hereliesaz.haive`
- Desktop JVM
- Web — JavaScript and WebAssembly

## Repository map

- `shared/` — domain, orchestration, persistence, policies, runtime projection, shared Compose UI
- `providers/` — provider adapters, beginning with Jules
- `androidApp/` — Android launcher
- `desktopApp/` — Desktop launcher
- `webApp/` — browser launcher
- `docs/` — current product documentation and privacy policy
- `branding/` — locked source brand and icon assets

## Documentation

Start with [`docs/README.md`](docs/README.md).

- [Architecture](docs/architecture/ARCHITECTURE.md)
- [Persistence](docs/architecture/PERSISTENCE.md)
- [Prompt caching](docs/architecture/PROMPT_CACHING.md)
- [Branding](docs/BRANDING.md)
- [Privacy policy](docs/PRIVACY.md)
