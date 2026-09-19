# Changelog

## 0.9.6 — 2026-09-19

- Fixed Android upgrade continuity by restoring the GitHub APK lineage to its original `com.hereliesaz.haive` application ID while keeping Google Play on `com.hereliesaz.aive`.
- Added durable write-through persistence for user drafts, navigation, appearance, workflow composition, company-role editing, project setup, artifact browsing, agent messages, compute settings, and add-on navigation.
- Added portable `.ive` project save/load with detected-file lists and manual file selection on Android and Desktop.
- Made saved Android provider keys, repository tokens, and compute relay tokens synchronously durable.
- Added GitHub-flavor automatic update checks/downloads with Android installer handoff.
- Added Play-flavor update notifications that open Google Play without requesting sideload permissions.


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

### Removed

- Legacy IDE-era source trees and historical reconstruction scaffolding.
- Transitional version-two naming and branch references.
- Repository-generated text backup snapshots and backup workflow.
