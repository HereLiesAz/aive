# The Aive brand assets

This directory contains the canonical brand sources for The Aive.

## Canonical sources

- `haive_logo.png` — current color logo, icon source, and required loader frame 0
- `haive_monochrome.png` — current monochrome logo
- `haive_splash.gif` — source motion for the reusable transparent loader
- `haive_animation1.mp4` / `haive_animation2.mp4` — extended motion assets

Desktop/Web icon sizes are generated deterministically from `haive_logo.png` by the Gradle brand pipeline. The same pipeline derives `haive_loader_frame0.png` and `haive_loader.gif` from `haive_logo.png` plus `haive_splash.gif`: frame 0 is always the current logo and later frames have the source background keyed transparent.

Generated platform derivatives live under each module's `build/generated/brand` tree and are not canonical source files. Android, Desktop, and Web consume those generated assets so the splash/loading treatment cannot drift away from the current logo.
