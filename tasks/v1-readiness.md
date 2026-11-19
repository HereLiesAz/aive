# 1.0 readiness plan

Goal (the keeper): reach 1.0 before MINOR passes 99 (now 0.91), production-ready on **every** platform: Android, desktop (Linux, Windows, macOS) and the web app.
Budget: about nine feature merges. Fixes, tests, docs and CI work are patches and cost none. Anything that is a feature needs the keeper's OK first.

Source: five read-only audits on 2026-11-19. They were fast passes and have gaps (see "Not audited"). Every item is **agent-reported and unverified** unless marked **verified**. Check an item before building it.

## Done
- [x] **verified** `research.ResearchTest` failed on main: #878 made the queen skip research for tutorial goals and the test used "teach me this app". The objective is now non-tutorial; 18/18 pass. (A red `:shared:desktopTest` stops every merge's release build.)

## Needs the keeper (decisions and secrets)
- [ ] **Apple Developer ID and notarization** for the .dmg. Gatekeeper blocks an unsigned .dmg. Needs your certificate and Apple secrets in CI.
- [ ] **Windows code signing** for the .msi (SmartScreen "unknown publisher"). Needs a certificate or Azure Trusted Signing.
- [ ] **Play production track.** The central workflow only reaches the internal track (`publish_play`, default false). Confirm it can promote, or plan a manual promotion in the Play Console. Write the Data safety form.
- [ ] **CI on pull requests.** The CHANGELOG records "CI builds only on a merge to main" as deliberate. Auditors recommend a `pull_request` build and test run. Your call.
- [ ] **Keyless free sources on by default** (`KeylessDefaults.kt`): they may log or train on prompts. Keep default-on, or require a switch or a "confidential" flag.
- [ ] **Desktop A2A server** serves plain HTTP when opened to the network. Keep it out of 1.0, or add TLS.
- [ ] **Compact creature sheet and `CardFlipSheetHost`** stay bottom sheets (docs/Containment.md). Accept for 1.0?
- [ ] **Localization:** English only (about 2,281 strings). Accept for 1.0, or add a locale?
- [ ] **Web app hosting:** deploy to GitHub Pages (or elsewhere), and where.

## Blockers
Release
- [ ] Publish the web app: `deploy-pages` (`multiplatform.yml:678-736`) ships puppet-rig and privacy only; the Wasm production bundle is built and never uploaded. Keep `/aive/privacy`.
- [ ] Web app offline and install: no service worker; check the manifest's icons exist (`webApp/src/webMain/resources`).
- [ ] macOS signing, notarization, hardened runtime and entitlements (secrets above); Windows signing step after `packageMsi`.
- [ ] MSI version guard (`desktopApp/build.gradle.kts:113-121`): tighten so `1.(MAJOR*100+MINOR).BUILD` stays within the installer's field limits (255) when MAJOR is 1; BUILD (the GitHub run number) must stay at or below 65535.

Tests and CI
- [ ] Desktop installer legs (`multiplatform.yml:414-442`) run no tests on Windows or macOS.

Data safety
- [ ] The 0.x to 1.0 memory-store split has only been run on web (`TODO.md:148-149`). Run it on an Android device and a desktop install holding an old store before release.

## Major
Correctness
- [ ] Events and Log > Run events always read the active run, not the one being viewed (`ApplicationRuntime.kt:1915`, called from `App.kt:673,1368`). Pass the viewed run. Size S.
- [ ] Run blockers are half-built (`queen/Blockers.kt`, `tasks/todo.md:94-98`): finish or hide.
- [ ] Desktop code that throws where a user can reach it: `LocalTestRunnerClient.kt:91`, `DesktopOrchestrationAgentRuntime.kt:56`, `DesktopOrtMemoryRuntime.kt:308` (unknown OS or arch crashes on start).
- [ ] Android Play flavor: confirm the stubbed `InstalledGeminiTextGenerationApi` Source is hidden, not a dead option.

Robustness
- [ ] No `CoroutineExceptionHandler`; 132 `.launch {` calls. Add one on the UI scope that logs and shows an error.
- [ ] Raw exception text shown to users (`App.kt:500`, `RepositoryServiceScreen.kt:223`, `SetupFileSection.kt:172`, `HallMonitorActions.kt:161`): route through `UserFacingError`.
- [ ] Silent catches (`App.kt:316,336,360`, `MemoryHabitsSection.kt:118`, `RepositoryServiceScreen.kt:133,173`, `WorkspaceAgentMode.kt:25`).
- [ ] Startup failure screen: confirm it offers retry, export and reset (`App.kt:271`). Corrupt store: keep the blob aside and say so (`SettingsWorkflowPersistence.kt:391`).
- [ ] First run with no reachable Source: show a Connect-a-Source call to action; link launch errors to Keeper's room > Connections.
- [ ] No desktop or web crash reporting; Android has opt-in. Add a local crash log at least.
- [ ] No desktop auto-update.

Security (from THREAT_MODEL.md open items)
- [ ] CI files (`.github/workflows/`, `.gitlab-ci.yml`) not blocked in workspace-agent change sets. Add a path denylist. S.
- [ ] Workspace agents write branches without a `HumanApproval` task (invariant 4). Make plan approval the default.
- [ ] Stored secrets fall back to plaintext on desktop (relay node keys) and on web (`WebCredentialStore.kt:25`, http origins). Refuse or warn.
- [ ] Android `allowBackup="true"`: confirm backup and extraction rules exclude secrets and the run store.
- [ ] Trace-export headers stored as plain settings (`TraceExportSettings.kt:18`): move to the secret store.
- [ ] Connector CORS on `/register` and `/token` (`connector/src/worker.js:94`): check the allowed origin.
- [ ] Imported `.ive` bundles: re-validate on restore and mark imported approvals unverified.
- [ ] computeRelay: one shared enrolment token, any node can revoke others, envelopes not end-to-end encrypted. Fix or keep computeRelay out of 1.0.

Tests and CI
- [ ] No web tests of any kind (`wasmJsBrowserTest`, a bundle smoke test, offline and manifest checks).
- [ ] No emulator smoke test for Android; JVM unit tests only.
- [ ] Rust and cargo-ndk installs are `continue-on-error`; `cliApp`, `mcpBridge` and `relay` tests are not run in the release workflow; `cancel-in-progress` on main can leave a commit unverified; release is not gated on lint.

Accessibility and UX
- [ ] 18 of 60 `.clickable` calls set a role; audit icon-only buttons; check 48dp targets and 200% font scale.
- [ ] 34 hard-coded `Text("...")` literals (worst: `CompanyCustomizationScreen.kt`, `AddonHostScreen.kt`).
- [ ] `CreatureRigGallery.kt:106` ignores reduced motion; desktop has no OS reduced-motion signal.

## Minor and docs
- versionCode scheme: confirm the central Play run matches `multiplatform.yml:153` and record it in VERSIONING.md. 0.x releases are GitHub prereleases and 1.0 is not, so check the Android update check.
- Release runbook (who bumps MAJOR, Play promotion, store assets, secret rotation); record the Windows `upgradeUuid` as never-change.
- `includeAllModules = true` bloats installers; trim after 1.0.
- Add-on Select with no options is a dead button (`AddonHostScreen.kt:174`); a role hatched after a run is created switched off with no hint (`Hatchery.kt:36`).
- Step token allowance and the MCP "package" field are shown but not enforced or filled (`StepContract.kt:96`, `McpServers.kt:25`); `aive agents install|test` is unbuilt.
- `tasks/todo.md:35` is stale (timeline rows are already per creature).
- Possible: unbounded `verticalScroll` lists (logs, inbox, memory); main-thread store reads.
- Pin workflows and vendored checkouts to SHAs.

## Not audited (do next)
Shell and process execution (CLI agent specs, the npm Install button, local git argv); whether PRIVACY.md matches what is sent; mcpBridge auth; secrets in logs; constant-time token compare in the relay; Play build steps in `HereLiesAz/workflows`; the `github` flavor's accessibility service; the desktop crash path.

## Order
1. The keeper's decisions and secrets above.
2. Blockers, release first (they need the secrets and the longest lead).
3. Robustness and correctness majors (small, safe, patches).
4. Security majors; tests and CI.
5. Run every platform for real: Android device, desktop installers on each OS, the web app in a browser with touch. Record the result in this file.
6. Bump MAJOR to 1 (owner-managed).
