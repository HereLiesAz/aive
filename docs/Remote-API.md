# Remote API (Android)

Other apps can run prompts on The Aive's providers and launch workflows through the bound service `AiveRemoteService`. IDEaz uses it this way.

## Access

- Bind with action `com.hereliesaz.aive.action.REMOTE`, setting the target package to either `com.hereliesaz.aive` (Play) or `com.hereliesaz.haive` (GitHub).
- The service requires `com.hereliesaz.aive.permission.REMOTE`, which has `signature` protection. Only apps signed with The Aive's release key can bind.
  - Clients only request it with `<uses-permission>`. They must not declare it: a second definition under a different signing key makes installation fail.
  - On Android 11 and later, clients also need a `<queries>` entry for both packages.

## Transport

The service uses `android.os.Messenger`. Each request sets `replyTo` and puts its fields in the message's data `Bundle`. Every reply echoes `requestId` and carries `version`. The constants are in `androidApp/.../remote/AiveRemoteProtocol.kt`, and IDEaz keeps a copy of them. If the protocol changes, update both copies and bump `VERSION`.

| Request | `what` | Fields | Reply |
|---|---|---|---|
| Prompt | 1 | `requestId`; `promptFd`; optional `providerId`, `model` | `RESULT` (101) with `text`, or `ERROR` (102) with `error` |
| Launch workflow | 2 | `requestId`, `projectName`, `objective`; optional `repositoryUrl` | `RESULT` when queued, or `ERROR` |
| Watch run | 3 | `requestId` | `RUN_STATUS` (103), sent repeatedly |

Field details:

- **`promptFd`** is a `ParcelFileDescriptor`: the read end of a pipe carrying the UTF-8 prompt, up to 4 MiB. Prompts do not travel in the Bundle because large prompts would hit Binder's transaction limit.
- **`repositoryUrl`** must be `https://github.com/owner/name`.
- **`RUN_STATUS`** carries `runId`, `objective`, `runStatus` (a `WorkflowRunStatus` name), `tasksTotal`, `tasksDone` and `progressMessage`.

## Behavior

- **Prompts** run in the background, without opening The Aive.
  - With `providerId` set, the prompt uses that provider. Keyless providers (`kilo`, `llm7`, `ovhcloud`) need no linked key.
  - Without `providerId`, the prompt uses the same provider planning picks. If no provider is linked, the request fails. The service never falls back to a keyless gateway the user hasn't linked, because those gateways may log prompts.
- **Workflows** run on the runtime that belongs to The Aive's UI, so launching one only adds it to a queue. The client then opens The Aive (its launch intent), and The Aive starts each queued request through the same path as the launch form. If no planning provider is linked, The Aive runs the starter workflow.
- **Watching** reports whichever run The Aive is currently showing. Updates continue until the client unbinds or sends another watch request.
