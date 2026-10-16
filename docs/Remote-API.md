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
