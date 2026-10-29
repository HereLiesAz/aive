# The Aive branding

The current Aive mark is defined by the source assets in `branding/`.

## Canonical assets

- `branding/haive_logo.png` — current color logo, icon and splash source
- `branding/haive_monochrome.png` — monochrome logo source

Desktop `.png`, `.ico`, and `.icns` package icons and Web favicon/PWA sizes are generated deterministically from `haive_logo.png` during the build. Stale checked-in Desktop/Web icon derivatives are not retained.

The build also generates `haive_splash_logo.png` from `haive_logo.png`: the static logo Android, Desktop and Web show while the app starts. There is no loading animation.

Android's platform-owned launch splash stays a neutral brand-color surface until the app owns its window; the app's splash then shows the logo until startup is ready.

## Naming

User-facing product name: **The Aive**

Technical/project shorthand: **Aive** / `aive`

Android application ID and namespace: `com.hereliesaz.aive` (Google Play). The GitHub-release flavor keeps the application ID `com.hereliesaz.haive` permanently so existing installs upgrade in place; the namespace is `com.hereliesaz.aive` for both.

## Colour rule: content colour is derived from its surface

Every piece of text, icon or glyph is drawn in the exact opposite of the surface it sits on: pure
black on a light surface, pure white on a dark one, whichever has the higher WCAG contrast. That
holds for every ground, pill, card, button, chip, well, sheet, dialog and text field, including
nested and translucent surfaces. Nothing hard-codes a text colour, and nothing relies on a theme
default that might not match the surface it lands on.

- One helper owns the rule: `oppositeOf(background)` / `Color.onOpposite()` in
  `shared/.../AzphaltContrast.kt`. It returns black or white, never the background or a near match,
  and is at least 4.58:1 for any opaque surface (so 4.5:1 for text and 3:1 for large or bold text,
  icons and glyphs). A translucent background is composited over the surface beneath it first.
- Disabled, placeholder and other de-emphasised content uses `mutedOnOpposite()`: the opposite
  blended toward the surface as far as it can go while staying at 3:1 or better.
- Every container that paints a background publishes it with `AzphaltSurface(background)`
  (`AzphaltThemedSurface` when Material components such as text fields, menus and checkboxes sit
  inside, which also swaps in a Material colour scheme built from that surface). Content then reads
  `azphaltOnSurface()` or `LocalContentColor`. A record's well is its own Ink surface, so text in it
  is white on every ground; it never takes the page's text colour.
- Where a Material component sets its own container colour, it must also be given the matching
  content colour (`contentColor`, or `azphaltTextFieldColors()` for text fields).
- Yellow is an accent for fills, never a foreground on a light surface (1.11:1 on Mustard); the
  label on a Yellow fill is black.
- `AzphaltContrastTest` enumerates every palette token (ground pages and folds, hues, caps, Ink,
  Yellow, White) and fails if any surface yields an on-colour below the thresholds.

## Controls: colour means something

Hues belong to living things: creatures, hives and the tissue they live in. Controls and cards are
calm, so colour on them always says something (`AzphaltUi.kt`):

- **Pills** (`AzphaltPill`, `tone`): `Quiet` (the default) is outlined in the surface's content
  colour; `Primary`, the one main action in view (Launch, Connect), and any selected pill are filled
  with the surface's strong colour (Ink, or Yellow on a dark surface); `Danger` is red, and only for
  the final step of something irreversible (delete everything, reject, remove a role). A pill that
  merely opens a confirmation stays `Quiet`; the confirmation is where it turns red.
- **Cards** (`AzphaltRecord`, `AzphaltNote`, `ConnectionRow`) are Ink, lifted Ink on an Ink surface,
  and Yellow outlined in the content colour when selected. Their wells are the other Ink.
- No control takes its colour from a hash of its name.

## Ground and the shell header

The page colour (the "ground": Mustard, Maroon, Navy, Cerulean, Teal, Pink) is chosen in
**Keeper's room > Appearance > Ground**, on every platform. It is persisted on the device and cycles to
the next ground on each press. The header and sidebar carry no ground control.

In its place the header (compact layout) and the top of the sidebar (wide layout) carry the inbox
button. At zero unread it is only the inbox glyph on the page, drawn in the page's opposite colour.
With unread items it is an Ink pill: white glyph and a Yellow count capsule with black text, capped
at "99+". Its accessible name is "Inbox, N unread" and it is a live region. "Unread" means a
pending plan approval, a failure escalation, or a Hall Monitor pause that the Inbox has not yet been
open for; opening the Inbox marks what it lists as read (`InboxReadLog`, persisted per device).
