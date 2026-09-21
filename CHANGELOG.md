# Changelog

## 0.9.6 — 2026-09-19

- Fixed Android upgrade continuity by restoring the GitHub APK lineage to its original `com.hereliesaz.haive` application ID while keeping Google Play on `com.hereliesaz.aive`.
- Added durable write-through persistence for user drafts, navigation, appearance, workflow composition, company-role editing, project setup, artifact browsing, agent messages, compute settings, and add-on navigation.
- Added portable `.ive` project save/load with detected-file lists and manual file selection on Android and Desktop.
- Made saved Android provider keys, repository tokens, and compute relay tokens synchronously durable.
- Added GitHub-flavor automatic update checks/downloads with Android installer handoff.
- Added Play-flavor update notifications that open Google Play without requesting sideload permissions.
- Added resumable, SHA-verified Android downloads for large local memory/orchestration release assets, including retained partial files and HTTP Range resume.
- Added Azphalt catalog recovery when an app-scoped workflow/role search incorrectly returns empty, with local `targetApps` filtering for current and legacy Aive host IDs.
- Exposed Azphalt model assets alongside workflows and roles, including a dedicated Models filter while keeping model packages out of the workflow/role installer.
- Added full Android Azphalt model installation for ONNX, TFLite, LiteRT, MediaPipe/TFLite task bundles, Sherpa-ONNX bundles, Vosk bundles, and generic model assets, with verified/resumable remote members, app-private persistence, durable inference-registry paths, updates with rollback, uninstall, and direct .azp import support.
- Grouped all `0.9.6.x` build assets under one `v0.9.6` GitHub Release while preserving immutable four-part build tags.
- Moved four-part build-version derivation and patch-grouped GitHub Release policy into `HereLiesAz/workflows`.


All notable changes to **The Aive** are documented here from the current product line forward.

## Unreleased

### Added

- Compose Multiplatform applications for Android, Desktop, JavaScript, and WebAssembly.
- Provider-neutral workflow domain and runtime.
- Jules provider integration.
- Governed company roles for planning, implementation, testing, review, recovery, and release.
- Durable workflow runs, retries, escalation, approval gates, artifacts, and progress.
- Animated H2G2 workflow mindmap with role personality motion, inherited branch motion, engagement wobble, and node-fill progress.
- Live workflow-to-mindmap projection and technical inspector data.
- Android/Desktop/Web CI and GitHub Pages deployment.
- The Aive brand and production icon system.
- Privacy policy documenting current local storage, provider data flow, credential handling, and tracking posture.

### Changed

- Product renamed from Geministrator to **The Aive**.
- Repository renamed to `aive`.
- Android application ID and namespace changed to `com.hereliesaz.aive`.
- Progress is modeled as task-run execution data rather than agent-specific state.
- `main` is the canonical product branch.
- GitHub Release containers are patch-scoped; exact build identity remains in immutable four-part tags and artifact filenames.

### Removed

- Legacy IDE-era source trees and historical reconstruction scaffolding.
- Transitional version-two naming and branch references.
- Repository-generated text backup snapshots and backup workflow.
