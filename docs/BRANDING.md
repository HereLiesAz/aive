# The Haive branding

The current Haive mark is defined by the source assets in `branding/`.

## Canonical assets

- `branding/haive_logo.png` — current color logo and icon/frame-0 source
- `branding/haive_monochrome.png` — monochrome logo source
- `branding/haive_splash.gif` — source motion for the reusable loading animation
- `branding/haive_animation1.mp4` / `branding/haive_animation2.mp4` — extended motion assets

Desktop `.png`, `.ico`, and `.icns` package icons and Web favicon/PWA sizes are generated deterministically from `haive_logo.png` during the build. Stale checked-in Desktop/Web icon derivatives are not retained.

The build also generates `haive_loader_frame0.png` and `haive_loader.gif` from `haive_logo.png` plus `haive_splash.gif`. Frame 0 is always regenerated from the current logo, later animation frames have the source background keyed transparent, and the output is capped for fast startup decoding. Android, Desktop, and Web all paint frame 0 immediately while the GIF is loading, then swap to the animation without a visible branding change.

Android's platform-owned launch splash cannot play a GIF, so it remains a neutral brand-color surface only until the app owns its window. The app-controlled splash then uses the generated loader. The same generated loader is intended for in-app loading states.

## Naming

User-facing product name: **The Haive**

Technical/project shorthand: **Haive** / `haive`

Android application ID and namespace: `com.hereliesaz.haive`
