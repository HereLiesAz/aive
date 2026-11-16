# Platform parity: distributed compute and remote access

What each build of The Aive can do for **distributed compute** (a pool of the keeper's own devices) and **remote access** (reaching this device from another one). Written from the code and checked against `TODO.md`, `PRIVACY.md`, [DISTRIBUTED_COMPUTE](architecture/DISTRIBUTED_COMPUTE.md), [Remote API](Remote-API.md), [A2A](A2A.md) and [CLI](CLI.md).

Legend: **yes** it works; **limit** the platform cannot do it, and the app says so under Keeper's room > This device; **decision** the owner chose to leave it out of that build (a release decision, not a platform rule); **not built** it could exist and does not yet (see "What remains").

Builds: **Play** is the Android Google Play build; **GitHub** is the Android GitHub release build; **Desktop** is the JVM app; **Web** is the PWA (Wasm); **CLI** is `aive serve` / `aive run`.

## Distributed compute

| Capability | Play | GitHub | Desktop | Web | CLI |
|---|---|---|---|---|---|
| Join a pool as a worker | decision | yes (`script`, `github-action`, `repository-operation`) | yes (`repository-operation`, `github-action`) | limit (a tab cannot stay connected once it sleeps) | not built |
| Offload a step to a pool | decision | yes | yes | not built (feasible as an origin, see below) | not built |
| Relay URL, pool, device name | decision | yes (Compute part) | yes | limit | not built |
| Pool token | decision | yes (Keystore) | yes (OS store, or `AIVE_RELAY_TOKEN`) | limit | not built |
| Pool owner token (revoke others, re-enrol) | decision | yes (Keystore, in-app field) | yes (OS store, in-app field; `AIVE_RELAY_OWNER_TOKEN` still works) | limit | not built |
| Per-device identity and enrolment | decision | yes (Android Keystore key) | yes (OS store key) | not built | not built |
| List enrolled devices, revoke one | decision | yes | yes | not built | not built |
| Hold work while metered | decision | yes | n/a: a desktop cannot see whether its network is metered | limit | not built |
| Hold work until plugged in | decision | yes | yes (reads the power source; was stuck "never accepts" before) | limit | not built |
| Hold work when hot | decision | yes (thermal status, battery temperature) | n/a (no sensor read) | limit | not built |
| Say why a device holds work (Compute) | decision | yes | yes | limit | not built |
| Host the relay | not offered in an app | not offered in an app | not offered in an app | limit | `computeRelay/` is its own server (Ktor/CIO), run it anywhere; `aive serve` does not host it |

Notes.

- **Why Play is a decision.** The 1.0 scope (`TODO.md`) holds distributed compute back from the Play build. An older code comment blamed the shared-token trust model; per-node identity has since replaced it, so the honest wording is "the owner has not released it in Play 1.0". The Compute destination is hidden there and This device says compute pools are not in the Play version.
- **Why Web is a limit.** A browser tab cannot keep a WebSocket alive once it sleeps, cannot run the executors that use the keeper's stored tokens, and holds those tokens in page-readable storage. Publishing work to a pool *as an origin* is technically possible (the relay client is plain Ktor WebSockets), but the node signer is synchronous and Web Crypto is asynchronous; that is a redesign, not a flag (see "What remains").
- **One rule everywhere.** `computeWorkDecision` (shared, pure, `ComputeWorkGateTest`) decides sharing, heat, network and power the same way on every platform. A platform that cannot tell passes `null`. Each hold is published as a node capability so Compute can show the reason under the device's row.

## Remote access

| Capability | Play | GitHub | Desktop | Web | CLI |
|---|---|---|---|---|---|
| Local control API (HTTP, loopback, token) | no HTTP API: IDEaz on the same phone uses the signature-protected bound service | same as Play | yes (port 41242) | limit (a browser cannot listen) | yes (`aive serve`, port 41243) |
| Bound service for IDEaz on the same device | yes | yes | n/a | n/a | n/a |
| Remote relay link (host side, encrypted, off by default) | decision: needs the Play Console foreground-service declaration first (owner step) | yes (foreground service; manifest declares it for this build only) | not built | limit | not built |
| Pairing (code, Copy, Share, Reset, revoke) | decision | yes | not built | limit | not built |
| A2A server (other agents start workflows here) | not built | not built | yes (on by default, loopback; opt-in LAN with an allow list) | limit | not built |
| A2A client (connect to an agent by its card) | yes | yes | yes | yes | yes |
| Launch a workflow over the relay | decision | queued until the app is next opened (see below) | not built | limit | not built |

Notes.

- **Relay Worker.** The default relay `https://aive-relay.hereliesaz.workers.dev` has no deployed Worker yet (`relay/`, `npx wrangler@4 deploy`). Until it is, Remote access on GitHub only works with a relay the keeper enters under Advanced. Deploying it needs the owner's Cloudflare account.
- **Workflows over the relay** run on the runtime tied to the app's UI, so a `launch` is queued in memory and starts when the app is next opened on that device (Remote API, "Limitation"). Prompts run at once.
- **Mesh primitives.** `shared/.../mesh/` (pairing invitation, AES-GCM cipher, scheduler) compiles for every target, including Web Crypto, but no platform builds a mesh transport from it yet; it is not part of any flow above.

## What changed in this pass

- One shared gating rule and the "why it is holding" reason in Compute (all platforms that join a pool).
- Desktop: "Require external power" reads the real power source instead of never accepting; the worker stays subscribed and picks up work once plugged in; the pool owner token can be saved in the app (it was environment-only).
- Android GitHub: Remote access is available again (the Play build keeps it off, with a note).
- Web and Play: the missing features are now a plain platform note instead of a missing screen.
- Docs: `TODO.md`, `PRIVACY.md`, `THREAT_MODEL.md`, `Remote-API.md` and `DISTRIBUTED_COMPUTE.md` agree with the code on what ships where.

## What remains, and why

Each needs an owner decision or a larger build than a parity pass:

1. **Deploy the relay Worker** (`relay/`): needs the owner's Cloudflare account. Until then the GitHub build's default relay has nothing behind it.
2. **Remote access in Play**: needs the Play Console foreground-service (`specialUse`) declaration with a video; then set `REMOTE_ACCESS_AVAILABLE = true` in `androidApp/src/play` and move the manifest entries.
3. **Distributed compute in Play**: the owner's 1.0 release decision; set `DISTRIBUTED_COMPUTE_AVAILABLE = true` in `androidApp/src/play` when it should ship.
4. **Desktop and CLI relay host (Remote access)**: `RelayHostLink`, `RelayHostRouter` and `RelaySealedFrames` are plain JVM code but live in the Android module. Moving them to a JVM source set both hosts can share is the closest equivalent; not done because it cannot be compiled or exercised here.
5. **CLI as a headless pool worker**: `aive serve` has no node key store or pool token store. It needs the desktop credential stores moved out of `desktopApp` (or a file-based store with owner-only permissions) and a session started by `HeadlessEngine`.
6. **Web as a pool origin**: needs an asynchronous `ComputeNodeSigner` (Web Crypto ECDSA returns IEEE P1363 and the relay expects DER) and a browser store for the identity; an owner call on whether to hold a device key in page-readable storage.
7. **A2A server on Android and CLI**: the server core is shared (`a2a/A2aServer.kt`); only desktop hosts it. Android would need a foreground service; the CLI could host it directly.
8. **Workflows over the relay while the app is closed**: needs the runtime to run headless on Android.

| Feature | Android | Desktop | Web |
|---|---|---|---|
| LiteRT-LM generative runtime (small tasks, Qwen2.5-0.5B) | Yes (arm64-v8a, x86_64; CPU) | Yes (Windows x64, macOS arm64, Linux x64/arm64; CPU) | No: its JavaScript API is an early preview, and ORT Web has not been run in a browser |

## Local model inference (ONNX)

Android and Desktop run ONNX models through the native ONNX Runtime; Web runs them through ONNX Runtime Web (`BrowserOrt.wasmJs.kt`, WebGPU then WASM, single-threaded). The Web runtime is built and unit-tested for its wire format, but loading ORT and running a model in a real browser have not been verified, and no web model is bundled or registered with the memory layer yet. See [Memory layer](Memory-layer.md).
