# Google Play Data safety answers

Answers for the Play Console **App content → Data safety** form, for the Google Play flavor (`com.hereliesaz.aive`). They follow [`docs/PRIVACY.md`](../../docs/PRIVACY.md); change both together.

Basis: the developer runs no server for the Play build and receives no user data, with one optional exception: **Remote access** (off by default) connects through a relay Worker the developer operates, which only ever carries end-to-end encrypted messages (see the judgment call below). Everything below leaves the device only when the user connects a service and starts work that uses it. Google counts any transmission off the device as **collection**, even when only a third party receives it. Transfers the user starts and expects are exempt from **sharing**. Google Play's own in-app update check is Google's, not the app's, and is not declared.

## Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes (all endpoints are HTTPS; Store, repository and spreadsheet URLs are required to be HTTPS) |
| Do you provide a way for users to request that their data is deleted? | Yes: in-app deletion controls (Settings data deletion, Memory screen), clearing app storage, or uninstalling |
| Account creation | The app has no accounts; no deletion URL is required |

## Data types

| Data type | Collected | Shared | Ephemeral | Required or optional | Purpose |
|---|---|---|---|---|---|
| App activity → Other user-generated content (objectives, task text, role instructions, artifacts and memory excerpts sent to the chosen AI provider) | Yes | No (user-initiated transfer) | No | Optional | App functionality |
| Files and docs (repository files and snapshots sent to the chosen AI provider, GitHub or GitLab) | Yes | No (user-initiated transfer) | No | Optional | App functionality |
| App info and performance → Other app performance data (IDs and versions of installed Store packages, sent to the Store to check for updates) | Yes | No | Yes | Optional | App functionality |

Not collected: location, personal info (name, email, user IDs, address, phone), financial info, health and fitness, messages, photos and videos, audio, contacts, calendar, web browsing, device or other IDs, crash logs, diagnostics. Crash reporting exists only in the GitHub release build.

## Judgment calls

- **Repository credentials.** A GitHub or GitLab token identifies the user's account to GitHub or GitLab, but the app never reads or sends the user's identity anywhere else. It is not declared as Personal info → User IDs. Declare it if a reviewer asks.
- **IP addresses.** Keyless gateways and the Store see the device's IP address as part of any HTTPS request. The app does not collect or store it, so it is not declared.
- **Distributed compute** is not available in the Play build, so the device descriptors it would share are not declared.
- **Remote access relay.** Off by default; turning it on creates a pairing and keeps a connection to the developer's relay Worker (Cloudflare) so IDEaz on another device can reach this one. Message contents are encrypted on the devices with a key the relay never has, so the relay cannot read them: that is not collection under Google's end-to-end-encryption exemption. The relay and Cloudflare do see the device's IP address, the random room name, hashes of the two relay tokens, and message sizes and timing; these are processed to deliver the connection, not stored for any other purpose (the room record, holding only token hashes, is deleted when Remote access is turned off or after 30 days without the host). They are not declared. Declare Device or other IDs and IP-related data if a reviewer asks, and keep this section and `docs/PRIVACY.md` (Remote access) in step with `relay/`.
