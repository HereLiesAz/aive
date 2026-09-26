# Privacy Policy for The Aive

**Effective date: September 26, 2026**

This policy describes the current open-source builds of **The Aive**, application ID `com.hereliesaz.aive`.

The Aive is a workflow orchestration application. It can store workflow information locally and, when you choose to use an external executor or provider, send the information needed to perform that work to the service you selected.

Android ships in two flavors. The **GitHub release** flavor (APKs published on GitHub Releases) adds optional crash reporting, an optional installed-Gemini bridge, and self-updates from GitHub Releases. The **Google Play** flavor contains none of these. Desktop and web builds contain none of them either.

## Summary

- The current source does **not** include advertising SDKs.
- The current source does **not** include third-party analytics, behavioral tracking, Crashlytics, Sentry, Mixpanel, Amplitude, or similar telemetry SDKs.
- The GitHub release flavor for Android has crash/ANR reporting that is **on by default while The Aive is pre-release**; you can turn it off in Settings. It becomes opt-in (off by default) with the production release. It is first-party code that posts to a first-party relay, not a telemetry SDK. See [Crash and ANR reports](#crash-and-anr-reports-github-release-flavor-only).
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
- artifacts and references to artifacts
- provider/executor run identifiers needed to resume work
- settings, such as distributed-compute configuration and whether crash reporting or the Gemini bridge is enabled
- on the Android GitHub release flavor, crash/ANR reports waiting to be sent (only while crash reporting is on)

The current persistence backend uses platform-local storage:

- Android: SharedPreferences
- Desktop/JVM: Java Preferences
- Web: browser localStorage

This data is used to restore workflows and continue runs after the application or browser restarts.

The Android app sets `allowBackup="true"`. If Android device backup is on, the system may include The Aive's app data in your device backup under Google's backup terms. Stored credentials are encrypted with an Android Keystore key, and that key is not included in the backup, so restored credential ciphertext cannot be decrypted. You have to reconnect providers after a restore.

## External providers and executors

The Aive works with external services only when you connect them and assign work to them.

- **Hosted LLM providers.** OpenAI, Anthropic (Claude), Google Gemini, xAI (Grok), DeepSeek, Groq, Cerebras, Mistral, Hugging Face Inference, OpenRouter, Together AI, Fireworks AI, Perplexity, Cohere, NVIDIA NIM, SambaNova, Ollama Cloud, Z.ai, and Cloudflare Workers AI. Each receives prompts through its own API using the key you supply.
- **Keyless free tiers.** Kilo Gateway, LLM7, and OVHcloud AI Endpoints can be linked without a key. Prompts then go to those gateways unauthenticated, and their rate limits are keyed by your IP address.
- **Jules** (`jules.googleapis.com`). This is Google's repository coding agent. It works on a repository already connected to your Jules account.
- **GitLab workspace agents** (gitlab.com). A linked LLM provider receives a bounded file tree and selected file contents from your GitLab project. The Aive then uses your GitLab token to commit the model's changes to a new `haive/...` branch.
- **Local Git workspace agents** (desktop only). A linked LLM provider receives a bounded snapshot of files from your local repository. Changes are committed to a new local `haive/...` branch. Nothing is pushed unless a separate repository operation pushes it.
- **GitHub Actions executors and script runners.** The Aive uses your GitHub token to dispatch workflows in your repository. The task context, and for script roles the script source, are sent as `workflow_dispatch` inputs.
- **OpenCode agent on GitHub Actions.** Before the first run, The Aive uses your GitHub token to install `.github/workflows/aive-opencode-agent.yml` on your repository's default branch. If that file later differs from the app's copy, The Aive overwrites it. Each run sends the prompt (role instructions, context, objective, acceptance criteria) as a `workflow_dispatch` input and runs the open-source OpenCode agent on a GitHub-hosted runner. By default it uses a free OpenCode Zen model. Changes are pushed to an `aive/opencode-...` branch in your repository, and the agent's step log is written to a check run on that repository.
- **Distributed compute relay.** Distributed compute is optional and has no default server. The relay URL, pool, and token are all yours. When you delegate a task to another device, The Aive sends a lease to the relay you configured. The lease contains the project, workflow definition, run state, task, and role. The relay forwards it to the device in your pool that claims it. Your device's name, platform, CPU/memory figures, network and power state, and supported executor kinds are shared with the other devices in the pool.
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

- **On by default during pre-release.** Until the production release, reporting is on unless you turn off **Settings → Crash Reports**; from the production release it will be off until you turn it on. While it is off, nothing is recorded or sent.
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
- **What is not sent.** The app adds no workflow content, prompts, artifacts, credentials, account identifiers, or device identifiers. However, exception messages are part of a stack trace, so a message can contain a fragment of whatever data the app was handling when it failed.
- **When.** A crash is written to app-private storage and sent on the next launch. Each distinct failure is sent at most once per app version. Reports that cannot be delivered are deleted after 7 days. After the first report is sent, the app tells you once and shows where to turn reporting off.
- **Turning it off.** Turn off **Settings → Crash Reports** at any time. Turning it off also deletes any pending reports that have not been sent.

Like any web request, a report reveals your IP address to the relay's host. The payload itself does not include it.

## Updates and downloads

- **Android GitHub release flavor.** The app periodically checks the public GitHub Releases API for `HereLiesAz/aive`, without authentication. When a newer APK is available, the app downloads it from GitHub Releases, verifies GitHub's published SHA-256 digest when one is present, and installs it only after you confirm.
- **Android Google Play flavor.** Updates go through Google Play's in-app update API under Google Play's terms.
- **Azphalt package catalog.** When you open the Store, The Aive contacts the package repository (`https://azphalt.store` by default). It searches and downloads packages, checks revocations, and sends the IDs and versions of the packages you installed from that repository to check for updates.
- **Local models.** Optional on-device models, such as the local planner and local memory models, are downloaded from GitHub Releases.

## Source repositories

Repository-backed work may require an external provider to access a repository or a source already connected to that provider. The Aive should request and transmit only the repository/context needed for the selected task.

The Aive itself is not intended to upload an entire repository to a separate analytics or data-collection service.

## Credentials and secrets

Provider API keys, OAuth tokens, passwords, keystore contents, service-account credentials, private signing material, and other secret values must not be stored in `WorkflowPersistence`.

A workflow may store the **name** of a required secret, but not its value.

Where credentials are stored today:

- **Android.** Provider, Jules, repository, and relay credentials are encrypted with AES-GCM under a non-exportable Android Keystore key. The ciphertext is kept in app-private SharedPreferences.
- **Desktop.** Credentials are kept in the macOS Keychain, the Linux Secret Service (`secret-tool`), or Windows DPAPI. If none of these is available, the credential is refused instead of being stored in plain text.
- **Web.** Credentials are stored in the browser's localStorage for the site, encrypted with a non-extractable AES-GCM key kept in the site's IndexedDB. A copy of the storage alone does not reveal them, but code running on the page can still use the key. Browsers without Web Crypto or IndexedDB (for example, plain-http origins) store them unencrypted.

Repository secrets used by GitHub Actions remain within GitHub Actions and are not part of the app's workflow persistence.

## Analytics, advertising, and tracking

The current codebase contains no advertising SDK and no third-party product analytics or behavioral tracking SDK. The only diagnostics are the crash/ANR reports described above (on by default during pre-release, opt-in from the production release), and they exist only in the Android GitHub release flavor.

The public web build may still be subject to ordinary infrastructure logging performed by its hosting platform or network intermediaries. The repository's GitHub Pages hosting and GitHub itself are governed by GitHub's own policies.

If The Aive later adds other diagnostics or analytics, this policy must be updated before that behavior is treated as part of the product.

## Data retention and deletion

Local workflow data remains until The Aive removes it or you clear the application's data/storage. On the web, clearing site data for The Aive removes the browser-local persistence and stored credentials.

Deleting local data does not automatically delete data already sent to an external provider. Provider sessions, repository data, logs, or artifacts held by an external service must be managed according to that service's controls and retention policy. This includes GitHub Actions runs and artifacts, pushed branches, and crash-report issues. The workflow's `aive-result` artifacts are kept for 7 days. Issues filed from crash reports stay on the public issue tracker until a maintainer edits or deletes them.

## Data sharing

The Aive shares task data only as needed to use services/executors you choose. If you enable crash reporting, crash reports go to the maintainer's relay and the public issue tracker. The current application does not sell personal data or share workflow content with advertisers.

## Children's privacy

The Aive is a software-development tool and is not directed to children under 13. It is not designed to knowingly collect personal information from children.

## Security

The project is designed to separate durable workflow state from credentials and secret material. No software can guarantee absolute security, and users should avoid placing unnecessary secrets in task prompts, artifacts, or workflow content that may be sent to external providers. See `docs/THREAT_MODEL.md` for known threats and open gaps.

## Changes to this policy

Material changes to The Aive's data handling should be accompanied by an update to this document and its effective date.

## Contact

The Aive is an open-source project maintained by **HereLiesAz**. Privacy questions and reports can be raised through the repository at `HereLiesAz/aive` on GitHub.
