# Haive Versioning

Haive uses plain numeric versions only. Maturity is encoded by the version number itself, not by prerelease suffixes or release-title labels.

- `0.x` — alpha-stage development.
- `1.0` through `1.3` — beta-stage stabilization and bug squashing.
- `1.4+` — stable line unless explicitly revised later.

Examples: `0.9.0`, `1.0.0`, `1.1.0`, `1.2.0`, `1.3.0`, `1.4.0`.

Do not append `-alpha`, `-beta`, `-rc`, or similar maturity labels to `app.versionName`, Git tags, release titles, installer names, or rolling-channel names.

The rolling release tag is `latest-v<major>.<minor>` (for example `latest-v0.9`). GitHub may still mark 0.x and 1.0–1.3 releases as prereleases in metadata; that metadata is not part of Haive's version or title.
