# Privacy Policy for The Aive

**Effective date: September 7, 2026**

This policy describes the current open-source builds of **The Aive**, application ID `com.hereliesaz.aive`.

The Aive is a workflow orchestration application. It can store workflow information locally and, when you choose to use an external executor or provider, send the information needed to perform that work to the service you selected.

## Summary

- The current source does **not** include advertising SDKs.
- The current source does **not** include third-party analytics, behavioral tracking, Crashlytics, Sentry, Mixpanel, Amplitude, or similar telemetry SDKs.
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

The current persistence backend uses platform-local storage:

- Android: SharedPreferences
- Desktop/JVM: Java Preferences
- Web: browser localStorage

This data is used to restore workflows and continue runs after the application or browser restarts.

## External providers and executors

The Aive is designed to work with external services: AI providers, coding agents (Jules, OpenCode on GitHub Actions), GitHub Actions, deployment systems, test runners, and other executors.

Free tiers often pay for themselves with your data. OpenCode Zen's free models and Kilo Gateway's free routing may log prompts and use them to improve models; keep confidential material out of tasks sent to them.

When you assign work to an external service, The Aive may send information required for that task, such as:

- task objective and instructions
- repository identity or source reference
- acceptance criteria
- approved specifications or architecture
- relevant upstream artifacts
- retry/failure context
- messages or approval responses you choose to send

The external service receives that information because you asked it to perform work. Its handling of the data is governed by that service's own terms and privacy policy.

## Source repositories

Repository-backed work may require an external provider to access a repository or a source already connected to that provider. The Aive should request and transmit only the repository/context needed for the selected task.

The Aive itself is not intended to upload an entire repository to a separate analytics or data-collection service.

## Credentials and secrets

Provider API keys, OAuth tokens, passwords, keystore contents, service-account credentials, private signing material, and other secret values must not be stored in `WorkflowPersistence`.

A workflow may store the **name** of a required secret, but not its value.

Where credentials are required, they should be handled through platform-secure storage or an appropriate external/server-side credential boundary. Repository secrets used by GitHub Actions remain within GitHub Actions and are not part of the app's workflow persistence.

## Analytics, advertising, and tracking

The current codebase contains no advertising SDK and no third-party product analytics or behavioral tracking SDK.

The public web build may still be subject to ordinary infrastructure logging performed by its hosting platform or network intermediaries. The repository's GitHub Pages hosting and GitHub itself are governed by GitHub's own policies.

If The Aive later adds optional diagnostics or analytics, this policy must be updated before that behavior is treated as part of the product.

## Data retention and deletion

Local workflow data remains until The Aive removes it or you clear the application's data/storage. On the web, clearing site data for The Aive removes the browser-local persistence used by the current implementation.

Deleting local data does not automatically delete data already sent to an external provider. Provider sessions, repository data, logs, or artifacts held by an external service must be managed according to that service's controls and retention policy.

## Data sharing

The Aive shares task data only as needed to use services/executors you choose. The current application does not sell personal data or share workflow content with advertisers.

## Children's privacy

The Aive is a software-development tool and is not directed to children under 13. It is not designed to knowingly collect personal information from children.

## Security

The project is designed to separate durable workflow state from credentials and secret material. No software can guarantee absolute security, and users should avoid placing unnecessary secrets in task prompts, artifacts, or workflow content that may be sent to external providers.

## Changes to this policy

Material changes to The Aive's data handling should be accompanied by an update to this document and its effective date.

## Contact

The Aive is an open-source project maintained by **HereLiesAz**. Privacy questions and reports can be raised through the repository at `HereLiesAz/aive` on GitHub.
