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

## Immutable releases

Every published build gets its own permanent Git tag and GitHub release:

- tag: `v<major>.<minor>.<patch>.<build>`
- title: `The Aive <major>.<minor>.<patch>.<build>`

Examples: `v0.9.6.412`, `v0.9.6.413`.

Release tags are never moved. Existing releases are never edited into newer builds, their assets
are never deleted or replaced, and uploads never use clobber semantics.

## Platform package metadata

Android `versionName` uses the full four-part version. Android `versionCode` remains a separate
monotonic integer required by Google Play.

Native desktop packaging tools impose stricter three-part numeric-version rules. Installer metadata
therefore uses `1.(MAJOR×100+MINOR).BUILD`, preserving a monotonically increasing package version
for every CI build while leaving the public Aive version untouched. For example, public
`0.9.6.412` packages as native version `1.9.412`. Downloadable filenames and GitHub releases
always use the full four-part public version.
