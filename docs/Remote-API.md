# Remote API (Android)

Other apps can run prompts on The Aive's providers and launch workflows through the bound service `AiveRemoteService`. IDEaz uses it this way.

## Access

- Bind with action `com.hereliesaz.aive.action.REMOTE`, setting the target package to either `com.hereliesaz.aive` (Play) or `com.hereliesaz.haive` (GitHub).
- The service requires `com.hereliesaz.aive.permission.REMOTE`, which has `signature` protection. Only apps signed with The Aive's release key can bind.
  - Clients only request it with `<uses-permission>`. They must not declare it: a second definition under a different signing key makes installation fail.
  - On Android 11 and later, clients also need a `<queries>` entry for both packages.
- Both flavors declare the same permission name. Android lets two installed packages define one custom permission only when they are signed with the same certificate, so the Play and GitHub builds must keep sharing one signing key. If either flavor is ever signed with a different key (for example, Play App Signing re-signing the Play build with a Google-held key), installing the second flavor fails with `INSTALL_FAILED_DUPLICATE_PERMISSION`, and a client signed with the other key can no longer bind. Moving to per-flavor permission names would then be required, in IDEaz too.

## Transport

The service uses `android.os.Messenger`. Each request sets `replyTo` and puts its fields in the message's data `Bundle`. Every reply echoes `requestId` and carries `version`. The constants are in `androidApp/.../remote/AiveRemoteProtocol.kt`, and IDEaz keeps a copy of them. If the protocol changes, update both copies and bump `VERSION`.

| Request | `what` | Fields | Reply |
|---|---|---|---|
| Prompt | 1 | `requestId`; `promptFd`; optional `providerId`, `model` | `RESULT` (101) with `text`, or `ERROR` (102) with `error` |
| Launch workflow | 2 | `requestId`, `projectName`, `objective`; optional `repositoryUrl` | `RESULT` with `launchId` when queued, or `ERROR` |
| Watch run | 3 | `requestId` | `RUN_STATUS` (103) and `LAUNCH_OUTCOME` (104), sent repeatedly |

Field details:

- **`promptFd`** is a `ParcelFileDescriptor`: the read end of a pipe carrying the UTF-8 prompt, up to 4 MiB. Prompts do not travel in the Bundle because large prompts would hit Binder's transaction limit.
- **`repositoryUrl`** must be `https://github.com/owner/name`.
- **`LAUNCH_OUTCOME`** carries `launchId`, `launchStarted` (boolean) and, when the launch did not start, `error`.
- **`RUN_STATUS`** carries `runId`, `objective`, `runStatus` (a `WorkflowRunStatus` name), `tasksTotal`, `tasksDone` and `progressMessage`.

## Behavior

- **Prompts** run in the background, without opening The Aive.
  - With `providerId` set, the prompt uses that provider. Keyless providers (`kilo`, `llm7`, `ovhcloud`) need no linked key.
  - Without `providerId`, the prompt uses the same provider planning picks. If no provider is linked, the request fails. The service never falls back to a keyless gateway the user hasn't linked, because those gateways may log prompts.
- **Workflows** run on the runtime that belongs to The Aive's UI, so launching one only adds it to a queue. The client then opens The Aive (its launch intent), and The Aive starts each queued request through the same path as the launch form. If no planning provider is linked, The Aive runs the starter workflow.
  - A request leaves the queue only when its launch has started or failed; each ends in exactly one `LAUNCH_OUTCOME`. If The Aive closes mid-launch, the request stays queued and is launched again.
  - The queue lives in memory only. It is lost if Android kills The Aive's process, holds at most 10 requests (further requests get `ERROR`), and a request still queued an hour after it arrived expires with a failed `LAUNCH_OUTCOME` instead of launching.
- **Watching** reports whichever run The Aive is currently showing, plus the outcomes of queued launches (the 10 most recent are replayed when watching starts). Updates continue until the client unbinds, dies, or sends another watch request.
- Version 2 added `launchId` and `LAUNCH_OUTCOME`; version 1 clients can ignore both.

## Relay

`relay/` holds a Cloudflare Worker for reaching The Aive from IDEaz on another device. The Aive (the host) creates a pairing, keeps a WebSocket open to the relay while the user has **Remote access** on, and answers sealed messages from IDEaz (the client). The relay has not been deployed yet; this section is the contract both apps follow.

### Pairing

The Aive creates the pairing. It generates:

- a room id: 16 to 64 characters of `A-Z a-z 0-9 _ -`;
- a **host token**, which it keeps and never shares;
- a **client token**, which goes into the invitation for IDEaz together with the room id.

Each token is base64url (no padding) of at least 32 bytes from a secure random source, so at least 43 characters. The relay rejects shorter tokens, tokens over 128 characters and tokens with fewer than 16 distinct characters, and the two tokens must differ. The relay stores only SHA-256 hashes of both.

### Requests

All requests carry `Authorization: Bearer <token>`. WebSocket clients therefore need a stack that can set request headers on the upgrade (OkHttp can; browsers cannot).

| Request | Token | Effect |
|---|---|---|
| `POST /v1/rooms/{roomId}` with JSON `{"clientToken": "..."}` | host | Creates the room (`201`). On an existing room with the same host token, replaces the client token and disconnects every client (`204`). Another host token gets `403`. |
| `DELETE /v1/rooms/{roomId}` | host | Deletes the room and disconnects everyone (`204`). |
| `GET /v1/rooms/{roomId}?role=host` (WebSocket) | host | Connects as host, replacing an earlier host socket (closed with `4000`). |
| `GET /v1/rooms/{roomId}?role=client` (WebSocket) | client | Connects as a client. `404` if the room does not exist, `429` when 8 clients are already connected. |

A client token never authenticates as host, so a client cannot create a room, evict the host or receive other clients' frames.

### Frames

Frames are JSON text, at most 1,000,000 UTF-8 bytes; anything larger or binary closes the socket with `1009`. The relay reads only the fields below and forwards `data` as an opaque string; sealing it (for example with a key in the invitation) is the apps' job.

- Client to relay: `{"data": "..."}`. The host receives `{"from": "<clientId>", "data": "..."}`; the relay assigns each client connection a random UUID.
- Host to relay: `{"to": "<clientId>", "data": "..."}`. Only that client receives `{"data": "..."}`; a `to` that is not a connected client's id is dropped.
- Relay events to clients: `{"event": "host-online"}` and `{"event": "host-offline"}`, sent when a client connects, when the host connects, and when the host's socket closes or errors.

### Sealed frames and messages

The relay forwards `data` without reading it. The apps seal it end to end with a key that only the pairing code carries, so the relay sees ciphertext only.

**Pairing code.** `aive1:` followed by base64url (no padding) of the UTF-8 JSON

~~~json
{"v": 1, "relay": "https://aive-relay.hereliesaz.workers.dev", "room": "<roomId>", "clientToken": "<token>", "key": "<base64url of 32 random bytes>"}
~~~

The host token is not in the code; The Aive keeps it. IDEaz connects with `clientToken` as `role=client` to `relay` and `room`, and seals with `key`. The default relay is `https://aive-relay.hereliesaz.workers.dev`; the user can enter another before turning Remote access on, and the code carries whichever was used. The reference implementation is `RemoteRelayPairing` in `androidApp/.../remote/RemoteRelayPairing.kt`.

**Frame `data`.** `<seq>.<base64url(nonce12 || ciphertext || tag16)>` (no padding):

- AES-256-GCM with the pairing key and a fresh random 12-byte nonce for every frame (never reuse one);
- associated data is the UTF-8 string `aive-relay/1|<room>|<dir>|<seq>`, where `<dir>` is `c2h` for client to host and `h2c` for host to client, and `<seq>` is the decimal prefix. A frame therefore cannot be moved between rooms, directions or positions;
- the plaintext is UTF-8 JSON of at most 600,000 bytes (the sealed frame stays under the relay's 1,000,000-byte limit).

**Envelope.** Every plaintext JSON object carries:

- `sid`: the sender's random 16-character lowercase hex session id, chosen once per connection;
- `seq`: the same decimal as the prefix, strictly increasing per `sid`, starting at 1;
- `ts`: the sender's clock in epoch milliseconds.

A receiver silently drops (no reply) a frame whose tag or associated data does not verify, whose `seq` differs from its prefix, whose `seq` is not greater than the last one it accepted for that `sid`, or whose `ts` is more than 10 minutes from its own clock. The host remembers the last `seq` for at most 256 `sid`s (least recently used are forgotten), so a client must pick a new `sid` for each connection and keep its clock roughly right.

**Client to host** (`t` selects the message; `id` is the client's request id, echoed back):

| `t` | Fields | Reply |
|---|---|---|
| `prompt` | `id`, `prompt`; optional `providerId`, `model` | `result` or `error` |
| `launch` | `id`, `projectName`, `objective`; optional `repositoryUrl` | `launched` or `error` |
| `watch` | `id` | `run_status` now (if a run is showing) and whenever it changes, and `launch_outcome` for queued launches |
| `ping` | `id` | `pong` |

**Host to client:**

| `t` | Fields |
|---|---|
| `result` | `id`, `text` |
| `error` | `id`, `error` (a user-facing message; `id` is 0 when the request had none) |
| `launched` | `id`, `launchId` |
| `launch_outcome` | `launchId`, `started`, optional `error` |
| `run_status` | `runId`, `objective`, `runStatus`, `tasksTotal`, `tasksDone`, optional `progressMessage` |
| `pong` | `id` |

The messages mean the same as the Messenger ones above, and run through the same code (`AiveRemoteHandler`, `AiveRemoteBridge`), so a prompt is never sent to an unlinked keyless provider on this path either. Relay-specific limits: a prompt over 500,000 UTF-8 bytes gets an `error`; at most 4 prompts run at once per host (more get an `error`); a reply that cannot fit in 600,000 bytes becomes an `error`. `watch` subscriptions last until the client's connection ends (the host keeps at most 16 watching clients).

### Opt-in and the foreground service

Remote access is off by default, in both Android flavors. **Settings → Remote Access** turns it on, with a relay URL field (HTTPS only; plain HTTP is accepted only for loopback) that can be edited while it is off. Turning it on creates the pairing, stores it (host token included) encrypted under the Android Keystore (`aive.remote-relay.pairing`, excluded from backup), shows the pairing code with Copy and Share buttons and a warning that anyone holding it can run prompts on the user's linked AI providers and start workflows, and starts `AiveRelayHostService`.

`AiveRelayHostService` is a non-exported foreground service (`foregroundServiceType="specialUse"`, with a persistent notification that has a **Turn off** action). On start it creates the room (`POST /v1/rooms/{room}`, which also drops any connected client, so clients must reconnect), then holds the host WebSocket and reconnects with exponential backoff (1 s doubling to 60 s, with jitter). It creates the room again only if the relay answers 404 or closes the socket with `4002`. It never deletes anything on its own: stopping the process, losing the network or rebooting leaves the room and pairing in place (the service restarts the next time The Aive is opened). Turning Remote access off, or pressing **Turn off** in the notification, deletes the room (`DELETE /v1/rooms/{room}`) and forgets the pairing, so the old code stops working. **Reset pairing** does that and creates a new room, tokens and key; IDEaz must be given the new code.

Android 13 and later ask for the notification permission when Remote access is turned on; the link works if it is refused, but the notification is hidden.

**Limitation: workflows over the relay start later.** Prompts run headless, as with the Messenger API. Workflows run on the runtime that belongs to The Aive's UI, so `launch` only queues the request in memory (`launched` confirms that) and the workflow starts when The Aive is next opened on that device; a `watch`ing client then receives its `launch_outcome` and `run_status`. A request still queued an hour after it arrived expires, as described under Behavior.

### Release steps

- **Google Play Console.** The service uses the `specialUse` foreground service type, which needs a declaration under App content → Foreground service permissions: choose "Other" (special use), paste the subtype text from the manifest property `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, describe the feature as above (opt-in, user-visible notification, encrypted link to the user's own app), and attach a short video of turning Remote access on. Without it a Play release that targets Android 14 or later is rejected.
- **Cloudflare.** Deploy `relay/` (`cd relay && npx wrangler@4 deploy`) under the account whose workers.dev subdomain matches the default relay URL, or change `RelayProtocol.DEFAULT_RELAY_URL` to match.
- **Privacy.** `docs/PRIVACY.md` describes Remote access; keep it, the published page and the Play data-safety form in step.

### Limits

- A frame is at most 1,000,000 UTF-8 bytes; a larger one closes the socket with `1009`.
- Each socket may send a burst of 40 frames and then 10 per second; a sender over that is closed with `1008`.
- One client address may create (or re-key) at most 20 rooms per hour; more get `429`.
- A room admits 8 clients at once (`429` beyond that).
- The host's replay guard lives for the life of the host link, not of one connection, so a reconnect does not reset what it has already seen. It is held in memory: after the app restarts, a frame captured before the restart can still be replayed once within the 10-minute clock window.

### Lifetime

A room is deleted, with its stored hashes, 30 days after its host last connected or disconnected. A connected host keeps the room alive. Clients are closed with `4001` when the client token is rotated and `4002` when the room is deleted or expires.
