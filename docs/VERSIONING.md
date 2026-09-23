# The Aive Versioning

The Aive uses four-part numeric versions:

`MAJOR.MINOR.PATCH.BUILD`

Examples: `0.9.6.412`, `0.9.6.413`, `0.9.7.414`, `1.0.0.900`.

The first three components describe the product release line. The fourth component identifies the
exact build. Every release build therefore has a unique immutable version even when several builds
belong to the same patch line.

The checked-in `app.versionName` uses build `0` (for example `0.9.6.0`) as the local/development
baseline. CI replaces only the fourth component with `github.run_number`. Re-running the same
workflow run keeps the same build identity; a new workflow run receives a new build number.

Maturity is encoded by the version number itself, not by prerelease suffixes:

- `0.x` — alpha-stage development.
- `1.0` through `1.3` — beta-stage stabilization and bug squashing.
- `1.4+` — stable line unless explicitly revised later.

Do not append `-alpha`, `-beta`, `-rc`, or similar labels to `app.versionName`, Git tags,
release titles, or installer names.

## Immutable build identity and grouped GitHub Releases

Every merge to `main` publishes its build twice:

- exact build tag and release: `<major>.<minor>.<patch>.<build>`, titled `The Aive <major>.<minor>.<patch>.<build>`
- grouped release tag: `<major>.<minor>`, titled `The Aive <major>.<minor>`

For example, `0.9.6.1722` gets its own release, and its APK, DEB, MSI, and DMG are also collected
under the single GitHub Release `0.9` alongside every other `0.9.x.x` build. The grouped release is
marked latest; per-build releases never are.

Asset filenames always include the full four-part version so several builds can coexist in one
release without clobbering each other. A grouped release may gain new build assets and refreshed
release notes, but an existing asset name may never be replaced with different bytes.

Legacy release migration is disabled in Aive's workflow, since it would fold and delete the
per-build releases. Tags are never moved or deleted.

## Centralized release policy

Version derivation and GitHub Release policy are owned by `HereLiesAz/workflows`, not by Aive-local
shell logic. The shared `four-part-version` action replaces only BUILD with the GitHub Actions run
number and exports the resulting version to build steps. The shared `patch-grouped-release` action
owns exact-build tags, minor grouping, legacy release migration, collision checks, prerelease state,
and idempotent asset publication.

Aive's workflow is responsible for producing and naming Aive-specific artifacts; it delegates
version/release semantics to the centralized workflow repository.

## Platform package metadata

Android `versionName` uses the full four-part version. Android `versionCode` remains a separate
monotonic integer required by Google Play.

Native desktop packaging tools impose stricter three-part numeric-version rules. Installer metadata
therefore uses `1.(MAJOR×100+MINOR).BUILD`, preserving a monotonically increasing package version
for every CI build while leaving the public Aive version untouched. For example, public
`0.9.6.412` packages as native version `1.9.412`. Downloadable filenames always use the full four-part public version. The GitHub Release container
uses the three-part patch version while the exact build remains visible in the filename and immutable
four-part Git tag.
