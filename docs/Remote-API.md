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

`relay/` holds a Cloudflare Worker for reaching The Aive from IDEaz on another device. Nothing in The Aive or IDEaz implements the pairing or connects to it yet, and it has not been deployed; this section is the contract a client must follow.

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

### Lifetime

A room is deleted, with its stored hashes, 30 days after its host last connected or disconnected. A connected host keeps the room alive. Clients are closed with `4001` when the client token is rotated and `4002` when the room is deleted or expires.
