# Privacy Policy for The Aive

**Effective date: November 2, 2026**

This policy describes the current source-available builds of **The Aive**, application ID `com.hereliesaz.aive`.

The Aive is a workflow orchestration application. It can store workflow information locally and, when you choose to use an external executor or provider, send the information needed to perform that work to the service you selected.

Android ships in two flavors. The **GitHub release** flavor (APKs published on GitHub Releases) adds optional crash reporting, an optional installed-Gemini bridge, and update checks against GitHub Releases. The **Google Play** flavor contains none of these. Desktop and web builds contain none of them either.

## Summary

- The current source does **not** include advertising SDKs.
- The current source does **not** include third-party analytics, behavioral tracking, Crashlytics, Sentry, Mixpanel, Amplitude, or similar telemetry SDKs.
- The GitHub release flavor for Android has opt-in crash/ANR reporting: it is **off unless you turn it on** in Settings. It is first-party code that posts to a first-party relay, not a telemetry SDK. See [Crash and ANR reports](#crash-and-anr-reports-github-release-flavor-only).
- Android has an opt-in **Remote access** feature, **off by default**, that lets your own IDEaz on another device reach The Aive through a relay. The relay sees only encrypted data, a room name and your IP address. See [Remote access](#remote-access-android).
- Every build has an opt-in **Trace export** to an OpenTelemetry collector that you name, **off by default**. It sends run structure, timing, token counts and cost; prompts and outputs only if you turn on a second toggle. See [Trace export](#trace-export-opentelemetry).
- The Aive does **not** sell personal information.
- Workflow state is stored locally by the application unless it must be sent to an external service to perform work you requested.
- External providers process data under their own privacy policies and retention practices.
- Credentials and secret values are intentionally excluded from workflow persistence.

## Information stored locally

Depending on how you use the app, The Aive may store:

- project names and repository references
- workflow definitions
- task and run state
- role definitions
- progress and status information
- approval gates and decisions
- workflow events
- a reviewed plan you have not yet decided on (its objective, the plan, the reviewer's reasons and the repository you named), kept for up to a week so a restart does not lose it (`aive.plan-review.pending.v1`); it is removed when you start or decline the plan, is never exported, and is cleared by clearing the app's data
- the app's own diagnostic log: the newest 500 lines (launch refusals and failures, settings and store-step failures, with stack traces), dated, with keys scrubbed before they are kept, in the same settings store as the app's other preferences (`aive.app-log.v1`); the Log tab shows it, and clearing the app's data removes it. It is never sent anywhere: it is not part of crash reports or trace export
- artifacts and references to artifacts
- provider/executor run identifiers needed to resume work
- settings, such as distributed-compute configuration and whether crash reporting, the Gemini bridge, Remote access or trace export is enabled (with the trace collector's address and headers)
- on the Android GitHub release flavor, crash/ANR reports waiting to be sent (only while crash reporting is on)

Workflow state is stored locally:

- Android: a SQLite database (`aive-workflows.db`) in the app's private database directory
- Desktop/JVM: a SQLite database at `~/.aive/workflows.db`
- Web: browser localStorage

On desktop with [Entire](Entire.md) recording on, each Local Git coding run's transcript (the task, the plan, the diff, the files changed and the tokens used) is written to `~/.aive/entire/sessions/<session>.jsonl`; transcripts not written for 30 days are deleted when the next run starts. The Aive also writes its Entire plugin launcher, `~/.aive/bin/entire-agent-aive`, a small script that starts The Aive. When Entire attaches a run, it copies that transcript into the checkpoint it commits to the repository's git history (on Entire's checkpoint branch, pushed with the run's branch). From then on it lives wherever that repository is pushed, under the repository's access rules and Entire's terms, and deleting the local file does not remove it. Before a Local Git run plans, The Aive also reads the repository's recent checkpoints (other agents' prompts and summaries included) and sends them in the planning prompt to the provider you chose.

When an Android or desktop install first opens the SQLite store, workflow state saved by an earlier version (SharedPreferences or Java Preferences) is moved into it. A JSON copy of that earlier state is kept beside it (`workflows-settings-backup-<time>.json`, in the app's private files on Android, in `~/.aive` on desktop) until you delete it, clear the app's data or uninstall.

Agent memory (banked session text and the graph derived from it) is kept in SQLite databases, one memory bank per workflow run: on Android, `aive-memory-<run>.db` (and the pre-split `aive-memory.db`, kept as a backup) in the app's private database directory; on desktop, `~/.aive/memory/banks/<run>.db` (and the pre-split `~/.aive/memory/memory.db`, kept as a backup). On the web it is a SQLite database in the browser's Origin Private File System for this site (browsers without it keep browser localStorage), and a memory log stored by an earlier version in localStorage is moved into it the first time it opens. The Memory screen can export a run's memory, forget individual episodes, or clear the selected run's memory bank; other runs' banks are not affected. Once the split of the pre-split database is verified, the Memory screen offers to delete that backup (Forget and Clear do not touch it). To remove all memory, clear the app's data/storage. On Android, a memory graph stored by an earlier version in SharedPreferences is moved into the database the first time it opens.

Memory stages run on-device by default. If you set a memory stage to a hosted model, the text of each packet that stage processes (bounded excerpts of banked session text or memory nodes) is sent to the provider you chose for that stage.

This data is used to restore workflows and continue runs after the application or browser restarts.

The Android app sets `allowBackup="true"`. If Android device backup is on, the system may include workflow state, settings and the agent-memory databases in your device backup under Google's backup terms. Credentials and downloaded models are excluded from backup and device transfer (`data_extraction_rules.xml`, `backup_rules.xml`); credentials are also encrypted with an Android Keystore key that never leaves the device. You have to reconnect providers after a restore, and models download again when you reinstall them.

## External providers and executors

The Aive works with external services only when you connect them and assign work to them.

- **Hosted LLM providers.** OpenAI, Anthropic (Claude), Google Gemini, xAI (Grok), DeepSeek, Groq, Cerebras, Mistral, Hugging Face Inference, OpenRouter, Together AI, Fireworks AI, Perplexity, Cohere, NVIDIA NIM, SambaNova, Ollama Cloud, Z.ai, and Cloudflare Workers AI. Each receives prompts through its own API using the key you supply. If you connect OpenAI, node-character image generation also sends its image prompts to OpenAI's Images API.
- **Plan review.** When you launch a hive with a linked planner, or draft one from a description, the plan the planner wrote is sent, with your objective and the names of the roles it uses, to a reviewer before anything runs (up to three times: the plan, the reviewer's objections, a reworked plan). The reviewer is a provider you have linked, the one its role is pinned to if it is pinned, otherwise the first of your linked providers that answers (a linked keyless gateway last, with the same logging as above). It is never sent to an unlinked gateway. What the reviewer says is shown to you as plain text and is not kept after you decide.
- **Keyless free tiers.** Kilo Gateway, LLM7, and OVHcloud AI Endpoints can be linked without a key. Prompts then go to those gateways unauthenticated, and their rate limits are keyed by your IP address.
- **Companion apps on Android (IDEaz).** Apps signed with The Aive's own key can ask it to run a prompt or start a workflow ([Remote API](Remote-API.md)). A prompt goes to the provider that app names, or else to the provider you linked for planning; The Aive never sends one to an unlinked keyless gateway on its own. Other apps cannot use this. With [Remote access](#remote-access-android) on, your own IDEaz on another device can do the same through an encrypted relay.
- **Remote A2A agents.** An agent you add by its Agent Card URL receives the tasks of roles you assign to it: the task, the role's instructions and approved context. A token you add stays in this device's credential store and is sent only to that agent's own address. On desktop, the A2A server lets agents on the same computer that hold its token start workflows. It is on by default, listens only on this computer, and can be turned off in Keeper's room › This device. Its token is made on first start, kept in the OS keychain, and never logged or sent anywhere by The Aive; it leaves the app only when you press **Copy token**. **Open to this network** is off by default; while you have it on, computers on your network that you allowed and that hold the token can start workflows too. That connection is not encrypted, so the token and the work sent can be read by others on the same network. The addresses you allow are kept on this device only ([A2A](A2A.md)).
- **Language models from the Azphalt Store.** A hosted model you install from the Store becomes a provider that The Aive calls directly at the address in its signed package. Prompts go to that operator, and the Store shows what the operator does with them (for example, that a free route may log and train on prompts) before you install. A key you add stays in this device's credential store and is sent only to that address. The package's setup script is never run.
- **MCP servers.** These are servers you add under Keeper's room › Connections › MCP servers. When an agent calls one of their tools, the arguments the model wrote go to that server, and these can include task content. The tool's result goes back to the model. Each server's tools are used only after you allow them, once per run or always. A server you add by command on desktop runs on your computer with your permissions. Bearer tokens for these servers are stored like other credentials and are not included in setup files. On desktop, if you turn on **Script sandbox**, an agent can also run a short script on your computer that calls these tools; the script, the tool results it reads and its output stay on the device, except for the script's final result, which goes back to the model, and the tool calls themselves, which go to their servers under the same approval.
- **GitLab workspace agents** (gitlab.com). A linked LLM provider receives a bounded file tree and selected file contents from your GitLab project. The Aive then uses your GitLab token to commit the model's changes to a new `haive/...` branch.
- **Local Git workspace agents** (desktop only). A linked LLM provider receives a bounded snapshot of files from your local repository. Changes are committed to a new local `haive/...` branch. Nothing is pushed unless a separate repository operation pushes it.
- **GitHub Actions executors and script runners.** The Aive uses your GitHub token to dispatch workflows in your repository. The task context, and for script roles the script source, are sent as `workflow_dispatch` inputs.
- **OpenCode agent on GitHub Actions.** Before the first run, The Aive uses your GitHub token to install `.github/workflows/aive-opencode-agent.yml` on your repository's default branch. If that file later differs from the app's copy, The Aive overwrites it. Each run sends the prompt (role instructions, context, objective, acceptance criteria) as a `workflow_dispatch` input and runs the open-source OpenCode agent on a GitHub-hosted runner. By default it uses a free OpenCode Zen model. Changes are pushed to an `aive/opencode-...` branch in your repository, and the agent's step log is written to a check run on that repository.
- **Distributed compute relay.** Not available in the Android Google Play build. Elsewhere, distributed compute is optional and has no default server. The relay URL, pool, and token are all yours. When you delegate a task to another device, The Aive sends a lease to the relay you configured. The lease contains the project, workflow definition, run state, task, and role. The relay forwards it to the device in your pool that claims it. To let work survive an originating phone/desktop disconnect, the bundled relay keeps resumable lease state in memory. Unfinished leases remain until they complete/cancel; completed results are replayable for 24 hours. If the relay operator sets `AIVE_RELAY_STATE_DIR` (or legacy `HAIVE_RELAY_STATE_DIR`), those lease records are also written to local storage so a relay-process restart can resume them. Stored relay-state files are AES-256-GCM encrypted with a key derived from the relay bearer token and use opaque hashed pool filenames. Expired terminal records are purged when that pool is next active/loaded; an untouched encrypted snapshot can remain until the pool reconnects or the relay operator removes it. Without that setting, restarting the relay clears its in-memory state. Your device's name, platform, CPU/memory figures, network and power state, and supported executor kinds are shared with the other devices in the pool.
- **Role surfaces.** A spreadsheet source you attach by HTTPS URL or Google Sheets ID is fetched from that host.
- **Other executors** you configure, such as deployment systems, test runners, and external services.

Free tiers often pay for themselves with your data. OpenCode Zen's free models, Kilo Gateway's free routing, LLM7, and OVHcloud's anonymous tier may log prompts and use them to improve models. Keep confidential material out of tasks sent to them.

When you assign work to an external service, The Aive may send information required for that task, such as:

- task objective and instructions
- repository identity or source reference
- acceptance criteria
- approved specifications or architecture
- relevant upstream artifacts
- retry/failure context
- messages or approval responses you choose to send

The external service receives that information because you asked it to perform work. Its handling of the data is governed by that service's own terms and privacy policy.

GitHub Actions inputs, run names, check-run output, uploaded `aive-result` artifacts, pushed branches, and commit messages are stored by GitHub in your repository. Anyone who can read the repository's Actions runs can see them, and in a public repository that means anyone.

## Installed-Gemini bridge (GitHub release flavor only)

On the Android GitHub release flavor, the Gemini provider can use the Gemini app installed on your device instead of an API key. This works through an Android accessibility service named "The Aive · Gemini bridge".

- **Opt-in with disclosure.** Before sending you to Android's Accessibility settings, The Aive shows a disclosure of what the bridge does. The bridge works only when you have both agreed in The Aive and turned the service on in Android settings.
- **Limited to Gemini.** The service is declared for the `com.google.android.apps.bard` and `com.google.android.googlequicksearchbox` packages only. It ignores events from every other app.
- **Only during a handoff.** It acts only while The Aive is waiting on a Gemini response. At all other times it does nothing.
- **What it reads and writes.** It types the task prompt into Gemini's input field. If direct text entry fails, it pastes the prompt through the clipboard. It then reads the text on Gemini's screen to find the completed reply, and it may press Gemini's copy button and read the reply from the clipboard.
- **Where the data goes.** The prompt goes to Google through your signed-in Gemini app, under Google's terms. If the bridge fails and a Gemini API key is also connected, The Aive sends the same prompt through the Gemini API instead.
- **Turning it off.** Turn the service off in Android Accessibility settings, or disconnect Gemini in The Aive.

Google Play builds do not include the accessibility service or its manifest entries. They use the Gemini API only.

## Crash and ANR reports (GitHub release flavor only)

The Android GitHub release flavor includes automatic crash and app-not-responding (ANR) reporting. The Google Play, desktop, and web builds have no crash reporting.

- **Off by default.** Reporting stays off until you turn on **Settings → Crash Reports**. This applies to updated installs too: a setting you never changed now reads as off. While it is off, nothing is recorded or sent.
- **Destination.** Reports are sent over HTTPS to `https://workflows.hereliesaz.workers.dev/crash-report/aive`. This is a relay operated by the project maintainer. It files or deduplicates issues on the public `HereLiesAz/aive` GitHub issue tracker, so **report contents become publicly visible**. The app holds no GitHub credential. The fixed key it sends to the relay is a spam filter, not a secret.
- **What is sent**, exactly:
  - package name
  - app version name and version code
  - Android version and API level
  - device brand and model
  - time of the crash or ANR (UTC)
  - stack trace, truncated to 38,000 characters
  - report kind (crash or ANR) and a 16-character hash that identifies the failure
- **Stack traces.** For a crash, this is the Java/Kotlin stack trace of the uncaught exception, including its causes. On Android 11 (API 30) and later, ANRs are also reported, using the ANR trace that Android recorded for the previous run. That trace covers the app's threads. ANRs from before you enabled reporting are not reported. Android versions before 11 get crash reports only.
- **What is not sent.** The app adds no workflow content, prompts, artifacts, credentials, account identifiers, or device identifiers. However, exception messages are part of a stack trace, so a message can contain a fragment of whatever data the app was handling when it failed. Before a report is stored, the app removes credential-shaped text (bearer tokens, API keys, token and password values), credentials and query strings in URLs, e-mail addresses and pairing codes; other text in a message is kept.
- **When.** A crash is written to app-private storage and sent on the next launch. Each distinct failure is sent at most once per app version. Reports that cannot be delivered are deleted after 7 days. After the first report is sent, the app tells you once and shows where to turn reporting off.
- **Turning it off.** Turn off **Settings → Crash Reports** at any time. Turning it off also deletes any pending reports that have not been sent.

Like any web request, a report reveals your IP address to the relay's host. The payload itself does not include it.

## Trace export (OpenTelemetry)

Trace export sends a trace of each finished run to an OpenTelemetry collector you choose, so you can watch your hives in your own observability tools. It exists in every build and is **off by default**: nothing is recorded or sent until you enter a collector address and turn on **Keeper's room › About and privacy › Trace export**. See [`architecture/OBSERVABILITY.md`](architecture/OBSERVABILITY.md).

- **Destination.** Only the collector address you enter, over HTTPS (plain HTTP only to a collector on the same device), as OTLP/HTTP JSON. The Aive has no default collector and operates none.
- **What is sent by default.** For each run that ends after you turned export on: the run, task and attempt structure; start and end times; the workflow definition, project, run, task and role ids; each executor's kind; each provider's id and session id; token counts, cost and cache-hit fraction; whether each step succeeded and a short error kind; artifact kinds and approval outcomes. Each run is sent once. Runs that ended before you turned export on are never sent.
- **What is not sent by default.** Prompts, the run's objective, artifact names and text, model outputs, and failure, approval and escalation reasons.
- **Prompts and outputs.** A second toggle, **Include prompts and outputs**, shown only while export is on and off by default, adds the run's objective, artifact names and text, and failure, approval and escalation reasons (each cut to 4,096 characters). These can contain code, secrets and personal data.
- **Headers.** Headers you enter (for example a collector's API key) are sent with every export. They are kept in the app's local settings, not in the platform credential store, and are not written to setup files.
- **Delivery.** Failed sends are retried a few times with backoff and then dropped. Nothing is kept for later.
- **Turning it off.** Turn off Trace export at any time; sending stops at once. Data already received by your collector is governed by that collector's operator.

## Refinement-ladder training data (off by default)

A workflow can be set to work a task in gated refinement levels (see [Compound inference](architecture/COMPOUND_INFERENCE.md#refinement-ladder)). If you switch dataset capture on (`ladder.dataset.capture.enabled`, the **Refinement data** switch on the Memory screen, which also shows what has been kept, copies it as JSONL and clears it), the app keeps on this device, per level, the level's objective, the level's answer, whether the answer fits the documented structure, and what its gate decided; and, per ladder, how it ended. Text is scrubbed of the common shapes of keys and cut to 6,000 characters, and the newest 200 records are kept. Nothing is sent anywhere: the records leave the device only when you export them, and clearing the app's data removes them. Because they hold the text of your tasks and their answers, treat an export like the work it came from.

## Execution receipts for procedural memory (off by default)

If you switch on procedural trace capture (`proceduralTraceCaptureEnabled`, the **Habits: learning / off** switch in the Habits panel of the Memory screen, which also needs the memory layer on), the app keeps in the workflow's memory a short receipt each time a system executor (a GitHub Action, deployment, repository operation, external service, script, test runner or nested workflow) is dispatched or finishes. A receipt holds the executor's kind, the task and attempt, whether it reported completed or failed, the kinds and number of artifacts, the executor's configured target **by name** when it is a plain name (for example `ci.yml` or `production`), the **repository's `owner/name`** when the project has one and it is a plain name (so a habit can say where it was done), and a few configured values when each is a number, a true/false flag, or one short word that is not key-shaped (for example a branch name or a build type), under a name that does not suggest a secret. It never holds command or script text, free-text input, output, artifact content, error messages or credentials, and anything that does not fit is dropped whole. A receipt records what the executor reported, not whether the work was checked. When a reviewing task later approves or turns back that work, one further receipt records only that verdict (approved or turned back), never the review's text. The app can add up the receipts of the same named operation across the runs of a project, read-only, to count how often it has been used and with which values; nothing is run or sent. If an agent task that works on a system executor's result writes a warning about it, the sentences of that warning are kept, as written (scrubbed of key shapes and cut to 400 characters), as a flag on the operation, with the agent's role and whether untrusted content was in front of it; so is a flag you add yourself. A written decision about an operation (that it stands, or that it is replaced), by you or by an agent, is kept the same way, as written and cut to 400 characters. An agent that prepared a run you made is read the same way as one that handled its results: its own sentences of caution about the run are kept as a flag, with the agent's role. An app-supplied clerk, if one is ever set, may label sentences the recognizer set aside; it never rewrites them. An agent states a decision by writing one line that begins `HABIT-DECISION:`; it is read only from a task that had no untrusted content in front of it, and is otherwise ignored. Flags and decisions are shown to later agents as quoted text and are never instructions. They stay on this device and are removed with the rest of the receipts. Receipts stay on this device and are removed by forgetting the memory they came from or clearing the memory layer. See [Procedural memory](architecture/PROCEDURAL_MEMORY_IMPLEMENTATION_PLAN.md).

## Remote access (Android)

Remote access lets IDEaz on another device of yours run prompts on your linked AI providers and start workflows on this device. It exists in both Android flavors and is **off by default**: nothing runs, connects or is created until you turn on **Settings → Remote Access**.

- **What turning it on does.** The Aive creates a random pairing (a room name, two relay tokens and an encryption key) and runs a foreground service, shown by a persistent notification, that keeps a connection to a relay open. The relay defaults to `https://aive-relay.hereliesaz.workers.dev`, operated by the project maintainer on Cloudflare; you can enter your own relay before turning it on.
- **What the relay sees.** Every message between The Aive and IDEaz is encrypted on your devices with a key that never leaves the pairing code, so the relay and Cloudflare see only ciphertext, its size and timing, the room name, and the IP addresses of the two devices. They cannot read your prompts, the replies or your workflows. The relay stores only hashes of the two tokens, and deletes a room 30 days after The Aive last connected to it.
- **What the pairing code allows.** The code is what you give IDEaz. Anyone who has it can run prompts on your linked AI providers (using your credentials and quota) and queue workflows on this device, until you revoke it. Treat it like a password. The code is shown only on this screen, never sent anywhere by The Aive, and does not contain the host token; the pairing, host token included, is stored encrypted under the Android Keystore and is excluded from backup and device transfer.
- **Where your data goes.** A prompt received this way goes to the provider IDEaz names or else the one you linked for planning, exactly as for the local [companion-app](#external-providers-and-executors) path; it is never sent to an unlinked keyless gateway.
- **Revoking.** Turn Remote access off (or press **Turn off** in the notification) to delete the room on the relay and forget the pairing at once; the old code then stops working. **Reset pairing** does the same and creates a new pairing, so IDEaz must be given the new code. If the relay cannot be reached when you revoke, the pairing is still forgotten on this device and the relay deletes the unused room after 30 days.

## Updates and downloads

- **Android GitHub release flavor.** The app periodically checks the public GitHub Releases API for `HereLiesAz/aive`, without authentication. When a newer version is available, it tells you and, only if you tap **Open GitHub release**, opens the release page in your browser. The app does not download or install the update itself.
- **Android Google Play flavor.** When Google Play installed the app, it queries Google Play's in-app update API for available updates, even with no provider connected, and does not contact GitHub for updates. A Play-flavor copy installed any other way makes no update check. If an update is available, **Open Play Store** opens this app's Play listing; updates are delivered by Google Play under its terms.
- **Azphalt package catalog.** When you open the Store, The Aive contacts the package repository (`https://azphalt.store` by default). It searches and downloads packages, checks revocations, and sends the IDs and versions of the packages you installed from that repository to check for updates.
- **Local models.** Optional on-device models, such as the local planner, local orchestration helpers and local memory models, are downloaded from GitHub Releases. Models installed from the Azphalt Store are downloaded over HTTPS from the host named in the signed package.

## Source repositories

Repository-backed work may require an external provider to access a repository or a source already connected to that provider. The Aive should request and transmit only the repository/context needed for the selected task.

The Aive itself is not intended to upload an entire repository to a separate analytics or data-collection service.

## Credentials and secrets

Provider API keys, OAuth tokens, passwords, keystore contents, service-account credentials, private signing material, and other secret values must not be stored in `WorkflowPersistence`.

A workflow may store the **name** of a required secret, but not its value.

Where credentials are stored today (a key for a provider the app no longer supports, such as Jules, is deleted the next time credentials are read):

- **Android.** Provider, repository, relay and MCP server credentials, and the Remote access pairing, are encrypted with AES-GCM under a non-exportable Android Keystore key. The ciphertext is kept in app-private SharedPreferences.
- **Desktop.** Credentials are kept in the macOS Keychain, the Linux Secret Service (`secret-tool`), or Windows DPAPI. If none of these is available, the credential is refused instead of being stored in plain text. A keyless link holds no secret: it is kept as a marker in the user's Java preferences, so free gateways work without a keychain.
- **Web.** Credentials are stored in the browser's localStorage for the site, encrypted with a non-extractable AES-GCM key kept in the site's IndexedDB. A copy of the storage alone does not reveal them, but code running on the page can still use the key. Browsers without Web Crypto or IndexedDB (for example, plain-http origins) store them unencrypted.

- **Setup files (`.aivesetup`).** Only when you choose **Settings → Export setup file**, The Aive writes a file to a location you pick. Its credentials (provider keys, repository tokens, the compute pool token and, on Android, the Remote access pairing including its host token) are encrypted with AES-256-GCM under a key derived from a passphrase you choose (PBKDF2-HMAC-SHA256, 310,000 iterations, random salt). The rest of the file — projects (with their linked repository and branch), workflow definitions, roles, appearance, memory-layer, compute, Store and add-on settings — is readable JSON, authenticated by the same key. Run history, artifacts, memory contents and drafts are not included. The file is not sent anywhere by The Aive; anyone who has both the file and the passphrase can read your credentials, so keep both private. On desktop, keys supplied through environment variables are not exported. Importing writes the credentials back into the platform store described above; a wrong passphrase imports nothing.

Repository secrets used by GitHub Actions remain within GitHub Actions and are not part of the app's workflow persistence.

## Analytics, advertising, and tracking

The current codebase contains no advertising SDK and no third-party product analytics or behavioral tracking SDK. The only diagnostics are the crash/ANR reports described above (opt-in), which exist only in the Android GitHub release flavor, and [trace export](#trace-export-opentelemetry) (opt-in), which sends only to a collector you name.

The public web build may still be subject to ordinary infrastructure logging performed by its hosting platform or network intermediaries. The repository's GitHub Pages hosting and GitHub itself are governed by GitHub's own policies.

If The Aive later adds other diagnostics or analytics, this policy must be updated before that behavior is treated as part of the product.

## Data retention and deletion

Local workflow data remains until The Aive removes it or you clear the application's data/storage. Downloaded models and installed Store packages stay in app-private storage until you remove them in the app, clear the app's data, or uninstall. Uninstalling deletes all local data, credentials and the Keystore key. On the web, clearing site data for The Aive removes the browser-local persistence and stored credentials.

Deleting local data does not automatically delete data already sent to an external provider. Provider sessions, repository data, logs, or artifacts held by an external service must be managed according to that service's controls and retention policy. This includes GitHub Actions runs and artifacts, pushed branches, and crash-report issues. The workflow's `aive-result` artifacts are kept for 7 days. Issues filed from crash reports stay on the public issue tracker until a maintainer edits or deletes them.

## Data sharing

The Aive shares task data only as needed to use services/executors you choose. If you enable crash reporting, crash reports go to the maintainer's relay and the public issue tracker. If you enable Remote access, encrypted messages pass through the relay you chose. If you enable trace export, run traces go to the collector you named. The current application does not sell personal data or share workflow content with advertisers.

## Children's privacy

The Aive is a software-development tool and is not directed to children under 13. It is not designed to knowingly collect personal information from children.

## Security

The project is designed to separate durable workflow state from credentials and secret material. No software can guarantee absolute security, and users should avoid placing unnecessary secrets in task prompts, artifacts, or workflow content that may be sent to external providers. See `docs/THREAT_MODEL.md` for known threats and open gaps.

## Changes to this policy

Material changes to The Aive's data handling should be accompanied by an update to this document and its effective date.

## Contact

The Aive is a source-available project (PolyForm Noncommercial 1.0.0) maintained by **HereLiesAz**. Privacy questions and reports can be raised through the repository at `HereLiesAz/aive` on GitHub.
