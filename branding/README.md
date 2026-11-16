# The Aive brand assets

This directory contains the canonical brand sources for The Aive.

## Canonical sources

- `aive_logo.png` — current color logo, icon source, and splash logo source
- `aive_monochrome.png` — current monochrome logo

Desktop/Web icon sizes are generated deterministically from `aive_logo.png` by the Gradle brand pipeline. The same pipeline derives `aive_splash_logo.png`, the static logo every platform shows while starting.

Generated platform derivatives live under each module's `build/generated/brand` tree and are not canonical source files. Android, Desktop, and Web consume those generated assets so the splash cannot drift away from the current logo.
