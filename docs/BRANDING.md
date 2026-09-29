# The Aive branding

The current Aive mark is defined by the source assets in `branding/`.

## Canonical assets

- `branding/haive_logo.png` — current color logo, icon and splash source
- `branding/haive_monochrome.png` — monochrome logo source
- `branding/haive_animation1.mp4` / `branding/haive_animation2.mp4` — extended motion assets

Desktop `.png`, `.ico`, and `.icns` package icons and Web favicon/PWA sizes are generated deterministically from `haive_logo.png` during the build. Stale checked-in Desktop/Web icon derivatives are not retained.

The build also generates `haive_splash_logo.png` from `haive_logo.png`: the static logo Android, Desktop and Web show while the app starts. There is no loading animation.

Android's platform-owned launch splash stays a neutral brand-color surface until the app owns its window; the app's splash then shows the logo until startup is ready.

## Naming

User-facing product name: **The Aive**

Technical/project shorthand: **Aive** / `aive`

Android application ID and namespace: `com.hereliesaz.aive`
