# The Haive branding

The current Haive mark is defined by the source assets in `branding/`.

## Canonical assets

- `branding/haive_logo.png` — current color logo and Desktop/Web icon source
- `branding/haive_monochrome.png` — monochrome logo source
- `branding/haive_splash.gif` — animated startup/loading treatment
- `branding/haive_animation1.mp4` / `branding/haive_animation2.mp4` — extended motion assets

Desktop `.png`, `.ico`, and `.icns` package icons and Web favicon/PWA sizes are generated deterministically from `haive_logo.png` during the build. The checked-in legacy `.ico`/`.icns` files are not packaging inputs. Android branding is maintained separately and is intentionally outside this generation path.

The splash GIF is used directly during Desktop and Web startup and disappears as soon as the real application window/view mounts; it is not an artificial startup delay.

## Naming

User-facing product name: **The Haive**

Technical/project shorthand: **Haive** / `haive`

Android application ID and namespace: `com.hereliesaz.haive`
