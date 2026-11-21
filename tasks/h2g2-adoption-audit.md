# Aive design kit vs conveyance-h2g2: adoption audit

Read-only audit. No source was changed; this file is the only write.

Library revision read: `/home/user/conveyance-h2g2` at `b85c269` (main, HEAD). `vendor/conveyance-h2g2` in aive is the same
revision. Aive revision: working tree of `/home/user/aive` (no git history consulted).

## 0. How to read this, and what was not verified

- Counts are call sites in non-test Kotlin under `/home/user/aive` (excluding `build/`, `vendor/`, `.claude/worktrees/`,
  `*Test` dirs), excluding the kit files themselves (`AzphaltUi/Controls/Dialog/Motion/ScreenKit/Contrast.kt`,
  `PillStack.kt`) and import/comment lines. They include the other modules (`androidApp`, `desktopApp`, `webApp`). "files"
  = distinct files containing a site. Grep counts, not a call graph: a wrapper that forwards counts once at its own site.
- Not verified: nothing was compiled or run. No test (`AzphaltContrastTest` included) was executed; statements about what it
  would do are inferences from its description in `docs/BRANDING.md`. APK size effect of a second font copy not measured.
  `androidApp`/`desktopApp` window and inset handling not read (the only inset grep covered `shared/src` and found none).
  Library screens (Files, Editor, AI chat, Settings family) were read at signature level, not run.
- The `entire brain` CLI is not installed in this sandbox (`entire: No such file or directory`), so no Brain recall/brief
  was possible. Fell back to direct source inspection for every question.
- **The HG2Gui rules you listed are not in `docs/design/DESIGN.md`.** DESIGN.md section 5 says icons are "not prohibited by a global
  doctrine" and section 10 specifies a chip with "a visible outline". The rules ("no icons, no borders, no shadows, no
  blur, no spinner, no hover/press states") are in the HTML style guides: `docs/design/HG2Gui Style Guide.html:387`,
  `HG2Gui Menu Style Guide.html:387`, `HG2Gui File Manager.html:392`, `HG2Gui Motion Sheet.html:388`. The library itself breaks
  the strict form (see section 3.6), so "match the library" and "obey the strict rules" are not the same goal.

## 1. Symbol map

Verdict key: **drop-in** (swap with near-zero change), **adapter** (usable behind a thin wrapper, behaviour differs),
**keep** (no library equivalent), **h2g2 better** (library does it more completely).

### 1.1 Tokens and theme

| Aive symbol | Nearest h2g2 symbol | Aive sites | Verdict |
|---|---|---|---|
| `Azphalt.Ink / Yellow / White` | `H2g2.ink / yellow / white` | `Azphalt.Ink` 466 lines/117 files, `Azphalt.Yellow` 171/66 | drop-in (hex values identical, checked) |
| `Azphalt.hues / caps` | `H2g2.hues / caps` | `Azphalt.hue(` ~68 lines/25 files, `Azphalt.cap(` 12/6 | drop-in. All 14 hex values identical (diffed by script). `Azphalt.hueIndex` equals `H2g2.hueOf` (`hashCode().mod(14)`). `H2g2.indexOf(seed)` first matches colour names ("red"), so use `hueOf` to stay identical |
| `Azphalt.grounds` / `Azphalt.Ground` | `grounds` / `Ground` | `Azphalt.currentGround` 47 sites/24 files | drop-in for the data (name, page, fold colours and weights identical, diffed). Not for the state, see 2.5 |
| `Azphalt.currentGround`, `setGround`, `reloadGround` | `GroundState`, `LocalH2g2Ground` | `setGround` 6/3 files | adapter. `GroundState.current` has a private setter; only `reroll()` (max 2) mutates it |
| `AzphaltType` (7 steps) | `H2g2Type` / `currentH2g2Type()` (12 steps) | `AzphaltType.` 1696 sites/227 files | adapter. Same names, different values, see 2.2 |
| `AzphaltFonts`, `LoadAzphaltFonts()` | `azrienochFontFamily()`, `rememberH2g2Type()` | `LoadAzphaltFonts(` 11 sites/11 files | adapter. Aive ships one variable TTF; the library ships static 400/600/800/900 instances |
| `AzphaltSpacing` | none | 80/27 files | keep (`H2g2Dimens` has only `MinTouch`, `ChipHeight`, `ContentMaxWidth`) |
| `AzphaltShapes.dialogCorner` (28dp) | `H2g2Surface` (26/20/18/capsule) | 6 | no equal; 28dp is not in the library's four radii |
| `AzphaltMinTouchTarget` (48dp) | `H2g2Dimens.MinTouch`, `minTouch()` | 125 lines/64 files | drop-in for the constant. The library's `Modifier.minTouch()` is better: hit box grows, drawn size stays, `minTouchSize()` compensates for UI scale below 100% |
| `AzphaltPillHeight` (32dp) | `H2g2Dimens.ChipHeight`, `PillMetrics.Height` | 2 | drop-in |
| `azphaltLiveRegion()` | `liveRegionPolite()` | 136 sites/73 files | adapter. Aive merges descendants (`mergeDescendants = true`); the library does not |
| `AzphaltHeading` (public, `section` 28sp) | `H2g2Heading` (`heading` 22sp, uppercases, heading role) | 8/8 files | adapter. Size differs, 2.2 |

### 1.2 Contrast and surfaces

| Aive symbol | Nearest h2g2 symbol | Aive sites | Verdict |
|---|---|---|---|
| `contrastRatio` | `contrastRatio` (`Primitives.kt`) | `contrastRatio` ~105 lines/18 files | drop-in (same WCAG formula; library ignores alpha, same as aive) |
| `Color.onOpposite()`, `oppositeOf()`, `contrastingText` | `Color.contrastingText()` (in `H2g2`) | `.onOpposite(` 184 sites/76 files; `contrastingText` 11/8 | **not drop-in**: black/white vs ink/white, no alpha compositing. Regressions in 2.1 |
| `mutedOnOpposite()`, `secondaryTextOn()`, `azphaltMutedOnSurface()` | `statusColor(StatusKind.Muted)`, `readableOn()` | `mutedOnOpposite` 20/16; `azphaltMutedOnSurface` ~228 lines/61 files | adapter at best. Different algorithm (aive blends toward the surface to a 4.5:1 floor; library is `onPage` at alpha 0.72 then `readableOn`) |
| `azphaltOnSurface()`, `azphaltSurface()`, `AzphaltSurface()`, `AzphaltThemedSurface()`, `LocalAzphaltSurface` | `LocalH2g2Ground`, `LocalH2g2ContentColor` | `AzphaltSurface(` 91/49; `AzphaltThemedSurface(` 89/47; `azphaltOnSurface` ~1381 lines/218 files | keep. The library only knows the page ground; it has no per-surface publishing. Aive's nested-surface model (Ink wells, lifted Ink, Material scheme per surface) is the stronger design here |
| `GeministratorColors`, `azphaltColorScheme` | none (library has no Material) | many | keep |
| `StatusKind`/`statusColor` | none in aive | 0 | h2g2 better as an addition: aive has no status-colour helper (uses `AzphaltDanger` and plain on-surface text) |
| `Modifier.screenInsets()`, `maxContentWidth()` | none in aive `shared` (no `WindowInsets` use found in `shared/src`) | 0 | h2g2 better as an addition (unverified for `androidApp`) |

### 1.3 Controls

| Aive symbol | Nearest h2g2 symbol | Aive sites | Verdict |
|---|---|---|---|
| `AzphaltPill` | `H2g2Chip` (`tint = hue`, `selected`, `role`, `contentDescription`) | 1163 sites/158 files | adapter. Library has no `endCap`, `tone`, `stateDescription` or `description` param; selected is `tint` fill, aive's selected is Ink (Yellow on Ink). Hue-per-seed must be passed as `tint`. Label is 12sp/14dp pad vs aive 13sp/16dp pad. Library chip's unselected state is a wash plus a 1.5dp outline (`Primitives.kt:398`) |
| `AzphaltTabs`/`AzphaltTabItem` | `H2g2Chip(role = Role.Tab)` in your own row | 20 sites/17 files | adapter you write: no library tab row exists publicly (the terminal's tab strip is internal) |
| `AzphaltButton` (Material `Button`, Ink pill) | `H2g2Chip` (default tint ink + yellow text) | 19/7 files | adapter. Aive picks Ink or White container by contrast to the surface; library chip is Ink with Yellow text |
| `AzphaltOutlinedButton` (2dp border) | `H2g2Chip(selected = false)` | 4/4 files | adapter, and the library's version is also outlined (1.5dp) |
| `AzphaltPrimaryButton/SecondaryButton/Paragraph/Screen` (public, for platform shells) | `H2g2Chip`, `Text`, `H2g2Theme`+`screenInsets` | 8/14/14/2 | adapter; `AzphaltScreen` also installs Material theme and scroll |
| `AzphaltTextField`, `azphaltTextFieldColors`, raw `OutlinedTextField` | none public (library's fields are `private`: `SettingTextField`, `HistorySearchBox`) | `AzphaltTextField(` 75/35 files; raw `OutlinedTextField(` ~70 | keep. Library fields are borderless capsules but cannot be called |
| `AzphaltRecord` | `Templates` `RecordTile` / `RecordTileWithWell` / `CappedRecordTile` (`h2g2.tile.record*`) | 198 sites/72 files | adapter, heavy. Library tiles take a Conveyance `ComposableRequest`, not parameters. Radius 26dp vs aive 22 to 30 animated. Aive has selected state, `onClick`, rich body, well band, which the library tiles lack |
| `AzphaltNote` | `Templates` `NoteTile` (`h2g2.tile.note`, 20dp) | 65/23 files | adapter. Aive uses 16dp and an optional link chevron |
| `AzphaltChildBand`, `AzphaltOfferBand`, `AzphaltNestSwap` | `NEST_MORPH_MS`, `LocalNestDepth`, `nestSurface`, `NestMorphShape` | 57/37, 8/8, 5/5 | keep (already built on the library's nest clock and surfaces) |
| `rememberGrowFromPill` | `NestMorphShape`, `NEST_MORPH_MS` | 16 sites/16 files | keep (already built on the library) |
| `azphaltEntrance`, `AzphaltEntrance` | `StackEntrance`, `staggerDelayMs`, `stackInterval` | 5 sites; `AzphaltEntrance` is a typealias | keep (already the library's enum; aive implements the eleven visuals) |
| `azphaltSelectedTransform`, `animatedRecordRadius`, `AzphaltPlaceTransition` | none | 9, 4, 5 | keep |
| `PillStack` | `H2g2PillMenu` + `MenuNode` + `PillMenuPlacement.TopLeft/TopRight` | 1 site (`ControlRoomUi.kt:1091`) | adapter; see 4.8 |
| `AzphaltAlertDialog` | `H2g2ConfirmDialog` | 73 sites/47 files (many are info prompts, not confirms) | adapter |
| `AzphaltConfirm` | `H2g2ConfirmDialog` | 20/11 files | adapter, closest fit |
| `McpElicitationDialog`, `McpConsentDialog` (raw Material `AlertDialog`) | `H2g2ConfirmDialog(extraContent = ...)` | 1 each | keep |
| `AzphaltMotion.reduceMotion`, `shouldReduceMotion`, `effectiveReduceMotion` | `H2g2Motion.durationScale/reduced`, `rememberReducedMotion()` | `.reduceMotion` 48/23 files | keep. Already bridged one-way at `App.kt:1563` |
| `AzphaltStoreScreen` | `H2g2AzpStoreScreen` | 1 site (`ControlRoomUi.kt:1618`) | keep. Aive's store handles LLMs, roles, workflows, host permissions, model installs; the library's lists `.azp` shell packages |
| `azphalt/*` (store models, verifier, repository client, runtime) | none | not UI | keep. Note the name collision: aive's "Azphalt" package format vs the design-system name the library ports from |
| `RepositoryProgressRecord` | `Templates` `RecordTileWithWell` | 6 sites | keep (domain composable on top of `AzphaltRecord`) |
| `RoundGlyphButton`, `HexagonShape`, `HiveGlyph` | none (`H2g2Chip` is the sanctioned label-only control) | `RoundGlyphButton(` 25 sites/3 files | keep, but violates the rules, see 3.1 |
| `HiveControlsBar`, `ModeSegment`, `ShellRail`/`ShellGlyph`, `AzphaltSwitchTrack` | none | 7/6, -, -, - | keep |
| `ProviderCredentialSetup`, `ProviderSettingsScreen` | `H2g2AiSettingsScreen` (partial overlap) | 1 / 1 | keep |

## 2. Behaviour differences that would cause regressions

### 2.1 Contrast function

Aive (`AzphaltContrast.kt:41-44`, documented as a rule in `docs/BRANDING.md:26-35`): pure `Color.Black` or `Color.White`,
whichever has higher WCAG contrast, with a translucent background first composited over the surface beneath (`under`
parameter, default the page ground).

Library (`H2g2.kt`, `contrastingText`): `H2g2.ink` (#1E1A17) or white, higher contrast wins, **no alpha compositing**, no
`under` parameter.

Computed with the library's own formula (script in scratchpad), aive on-colour vs library on-colour:

~~~
Mustard E8C81E   black 12.71   ink 10.46
Pink    D4728F   black  6.60   ink  5.43
Yellow  F0D42A   black 14.16   ink 11.65
hue1..hue5,hue10,hue12 (cyan..orange, gray, tan): ink gives 5.18 to 7.52, black gives 6.29 to 9.14
cap4    A2760F   black  5.13   ink  4.22   <-- library below 4.5:1
cap12   96762F   black  4.93   white 4.26  <-- library below 4.5:1 AND a different text colour chosen
~~~

Consequences:
- Every light surface's text changes from `#000` to `#1E1A17` (visible, not a bug, but the BRANDING.md rule is "pure black").
- `AzphaltPill` end caps (`cap.onOpposite()`, `AzphaltUi.kt`) on caps 4 and 12 drop under 4.5:1 with the library function.
  Inference: `AzphaltContrastTest` (`shared/src/commonTest/.../AzphaltContrastTest.kt`, enumerates every token) would fail if
  `onOpposite()` were redirected to `contrastingText()`. Not run.
- Translucent fills (alpha backgrounds; `azphaltColorScheme`, `AzphaltSurface`) would be judged on the uncomposited colour.
- Recommendation: do **not** replace `oppositeOf`. If you want ink instead of black, change the constant in aive and fix the
  two caps, instead of adopting an unmeasured function.

Also: `statusColorOn(Muted)` (`Primitives.kt`) is `onPage` at alpha 0.72 pushed through `readableOn`; aive's muted colour is a
blend from the full opposite toward the surface that stops at 4.5:1. Same intent, different outputs, and aive's is
computed per surface, not per ground.

### 2.2 Type scale

| Step | Aive `AzphaltType` | Library `H2g2Type` |
|---|---|---|
| hero | 46sp / 50, tracking -0.045em, Black | 58sp / 49, -0.03em, Black |
| section | 28 / 32, -0.035em, Black | 40 / 40, -0.02em, Black |
| lead | 19 / 24, SemiBold | 25 / 32, SemiBold |
| body | 15 / 21, SemiBold | 17 / 25.5, SemiBold |
| capsule | 13 / 16, 0.09em, ExtraBold | 15 / 18, 0.09em (library `pill` is 13 / 16, the aive value) |
| eyebrow | 11 / 14, 0.22em | 11 / 14, 0.28em |
| endCap | 11 / 14, 0.10em | 11 / 14, 0.10em (same) |
| (none) | - | `heading` 22/26, `pillCap` 11/14 0.06em, `chip` 12/16, `micro` 11/14 0.18em |

Every aive screen is 20 to 25% smaller than the library's scale. Swapping `AzphaltType` for `H2g2Type` wholesale would
reflow all 227 files that use it; aive's `AzphaltType.capsule` corresponds to the library's `pill`, not `capsule`.
Weights: the library bundles only wght 400/600/800/900 (`Type.kt:azrienochFontFamily`), aive's variable font serves any
weight. Aive calls `FontWeight.Medium` and `Bold` (e.g. `HiveWorkspace.kt:409,856`, `ShellRail.kt:348`), which would snap to a
neighbouring static instance under the library's family. Both bundles also ship Azrienoch twice if both are linked
(size not measured).

### 2.3 Surface radii

Library: capsule (percent 50), 26 (`recordTile`), 20 (`note`), 18 (`well`). Aive in code: 999, **22 to 30 animated** for
records (`animatedRecordRadius`, `AzphaltMotion.kt`), 16 (`AzphaltNote`, `AzphaltUi.kt:567`), 28 (`dialogCorner`), plus 24 (8 sites),
19 (10, e.g. `StableScreens.kt:239`, `HiveWorkspace.kt:125`), 14, 12 (`CardFlipUi.kt:102`, `KeeperAppearance.kt:264`), 10, 6, 5 and others.
The nest depth surfaces already come from the library (`nestSurface`), so wells and notes inside bands do use 20/18.

### 2.4 Motion constants and easing

- `HouseEase` is the library's `H2g2Motion.Ease` (`AzphaltMotion.kt:38`). Same curve.
- Aive's own durations: entrance tween 320ms, selected scale 220ms to 1.018, record radius 220ms. The library's menu uses
  `SLIDE_MS 140`, `DROP_MS 140`, `SWING_MS 173`, `UNFOLD_MS 420`; aive's stagger uses the library's `staggerDelayMs`/`stackInterval(34, n)`
  (capped at 600ms total).
- `PillStack.kt` slides with `tween(180)`/`tween(150)` and the default easing, i.e. neither linear nor the house ease. The
  library's `PillMenu.kt` uses `LinearEasing` 46 times and a no-bounce spring once. If "all menu motion linear" is binding,
  `PillStack` violates it today.
- `LiveStepsFeed.kt:~187` animates a blur radius with `Motion.spec(Weight.Medium)` (conveyance core), not a house value.

### 2.5 Reduced motion

Aive: one switch, `AzphaltMotion.reduceMotion = platformReduceMotion || keeperReduceMotion` (`AzphaltReduceMotion.kt`), pushed
into the library at `App.kt:1563` (`H2g2Motion.updateDurationScale(if (reduce) 0f else 1f)` in a `LaunchedEffect(reduce)`) and
into conveyance's `LocalReducedMotion`. Reduced motion in aive means end state at once or a 60 to 80ms fade (`AzphaltChildBand`,
`AzphaltNestSwap`), never a slower scale.

Library: `H2g2Motion.durationScale <= 0` means reduced; `scaledDelayMs` multiplies delays by the scale. On Android,
`H2g2Theme` -> `H2g2PlatformEffects` (`Theme.android.kt:25-31`) calls `H2g2Motion.updateDurationScale(systemAnimatorScale)` inside
a `SideEffect`.

**Regression if you adopt `H2g2Theme` on Android:** that side effect writes the system scale (1f unless the user removed
animations) over the value `App.kt:1563` set, so the keeper's own "Reduce motion" switch (Keeper's room, Appearance) would
silently stop reaching library motion until the next toggle. Fix by never letting the theme write it (do not use
`H2g2Theme`; provide only `LocalH2g2Ground`), or by re-applying from a `SideEffect` placed after the theme.
Also: aive only ever sets 0f or 1f, so a system animator scale of 0.5 or 2 would not stretch the library's `scaledDelayMs` in aive
as it would in the library's own screens.

### 2.6 Ground and theme model

- Aive: process-global `Azphalt.currentGround` (mutable state, persisted under `aive.appearance.ground.v1` via `Settings`),
  chosen by the keeper (a named ground, no reroll), read at 47 sites/24 files, re-read after a setup import (`reloadGround`).
- Library: `GroundState` instance, `GroundState()` starts with `randomGround()` (Mustard weighted 6x), `current` has a private setter,
  only `reroll()` (at most 2) changes it. There is no `setGround(name)`.
- Adapter options: (a) `H2g2Theme(groundState = remember(Azphalt.currentGround.name) { GroundState(libGround) })`, which recreates
  state each change; (b) skip `H2g2Theme` and wrap library screens in
  `CompositionLocalProvider(LocalH2g2Ground provides grounds.first { it.name == Azphalt.currentGround.name })`, which keeps
  `Azphalt` as the owner and avoids the reduced-motion clobber in 2.5. (b) is lower risk.
- `H2g2Theme` also paints the full-screen background, rescales `LocalDensity` by `scale` (aive has no Text-size setting, so scale 1),
  and on Android restyles the window background and status-bar icons by ground; aive's own window handling in `androidApp`
  was not read, so a conflict there is possible and unchecked.

### 2.7 Content colour assumes the page ground

Library components read `LocalH2g2Ground.current.onPage` / `.page` (`H2g2Chip`, `H2g2JobProgressBar`, `statusColor`, settings
rows). Aive nests content on Ink wells and lifted Ink. A library chip placed inside an `AzphaltRecord` well on Mustard would draw
unselected text and outline in `onPage` (ink) on an ink well: dark on dark. Aive's `AzphaltSurface` publishes the real
surface; the library has nothing equivalent. Anything adopted into a well needs a local `LocalH2g2Ground provides` of a
`Ground` whose `page` is the well colour (a fake ground), or must stay out of wells.

### 2.8 Dialog behaviour

`H2g2ConfirmDialog` (`components/ConfirmDialog.kt`): Ink panel at 26dp, plain Compose `Dialog`, confirm pill defaults to red
`H2g2.hues[6]` (parameter `confirmTint`), cancel is an outlined pill (border `CANCEL_BORDER` 1.5dp, line 162), `Elevated` tone adds a
3dp Yellow border (line 116), no grow-from-pill entrance. `AzphaltAlertDialog`: Material `AlertDialog`, Ink container, Yellow confirm,
text-button cancel, grows from a pill (`rememberGrowFromPill`). Cancel label default `"CANCEL"` is a literal English string in the
library (aive localises through `Res.string`).

### 2.9 Smaller points

- Library `H2g2Chip` has no `endCap` and no `enabled`-alpha parity issue, but its press feedback is `indication(interaction,
  LocalIndication.current)` (`Primitives.kt`, chip body); under aive's `MaterialTheme` that resolves to the Material ripple.
- Library hues and caps are ordered identically to aive's, so seeded colours stay stable if `hueOf` is used.
- `PillStack` (aive) has an `anchorKey` hung under a breadcrumb step, `initialPath`, `selectedKey` and tab semantics; `H2g2PillMenu` has none of these.
- `H2g2Nest` (already used once, `AddonHostScreen.kt:177`) installs its own `BackHandler` and reads conveyance's `LocalReducedMotion`; aive already provides that local (`App.kt:1564`).

## 3. Places aive violates the HG2Gui rules

Rule source: HTML guides listed in section 0. Line numbers are as read; `S/` = `shared/src/commonMain/kotlin/com/hereliesaz/geministrator/`.

### 3.1 Hexagon controls with glyphs and a border (the case you named)

- `S/HiveWorkspace.kt:805-815` `HexagonShape`; `:820-863` `RoundGlyphButton`: 34dp hexagon, **1dp border** at `:848`, a stroked
  glyph at `:887` (`Glyph`), plus a caption `Text`. 25 call sites in 3 files. `HiveControlsBar` (`:359-436`) puts up to nine of them in a row.
- `S/HiveWorkspace.kt:867-880` `HiveGlyph`: Undo, Redo, ZoomIn, ZoomOut, Target, Close, Play, Pause, Stop, Timeline, Share, Edit, Swarm
  as SVG path strings: icons.
- The 48dp rule is met (48dp `defaultMinSize` around the 34dp drawn hexagon, `:833`) and the caption is 11sp (`:856`), so the control
  fails the icon and border rules, not the size rules.

### 3.2 Other borders

| File:line | What |
|---|---|
| `S/AzphaltUi.kt:484` | 2dp border on a selected `AzphaltRecord` (affects every record, 198 sites) |
| `S/AzphaltControls.kt:68-81` | `AzphaltOutlinedButton`, `BorderStroke(2.dp, ...)` |
| `S/AzphaltControls.kt:113` + ~70 raw sites | `OutlinedTextField` (border built in); e.g. `CompanyCustomizationScreen.kt` has ~30, `MemoryLayerScreen.kt` 6, `LiveWorkflowLibraryScreen.kt` 6, `MindMapRunScreen.kt` 5 |
| `S/HiveWorkspace.kt:522` | 3dp Yellow frame around the whole tissue in Build mode |
| `S/CardFlipUi.kt:104` | 1dp or 3dp border on flowchart cards |
| `S/MemorySimpleScreen.kt:377` | 2dp border on `MemoryNoteCard` |
| `S/StableScreens.kt:520` | 3dp border on a selected card |
| `S/NewHiveRoles.kt:170`, `S/StoreWide.kt:164` | 2dp Yellow border on selected tiles |
| `S/KeeperAppearance.kt:201-205, 271, 277` | ground swatches: 2dp/3dp/1dp circle and card borders |
| `S/AzphaltStoreScreen.kt:1193, 1292`, `S/addons/AddonHostScreen.kt:196` | Material `Checkbox` (outlined box plus check glyph) |

Total: 91 lines matching `.border(`, `BorderStroke(`, `OutlinedButton(`, `OutlinedTextField(`.

### 3.3 Icons and glyphs

- `S/ShellRail.kt:142-153` `ShellIcon` (Hive, Inbox, Swarm, Stable, Memory, Store, Settings line icons), drawn by `ShellGlyph`
  (`:155-166`); used in the rail at `:295`.
- `S/InboxBadge.kt:~250-310` `InboxGlyph`, a 24dp stroked tray drawn on a canvas.
- `S/AzphaltUi.kt:587` the "›" chevron on a linked `AzphaltNote`.
- `S/HiveJobEditor.kt:168` ("✕") and `:194` ("＋") text glyphs as buttons via `JobRowButton` (`:430-441`).
- `S/PillStack.kt` end cap "+" on a host pill (text, labelled by the pill itself).
- `S/NewHiveRoles.kt:216` a canvas-drawn plus on a 28dp circle (inside a 48dp target).
- Android notification small icon `AiveRelayHostService.kt:121` is a platform requirement, not a screen.

### 3.4 Shadows, blur, scrims

- No `.shadow(`, `shadowElevation`, `tonalElevation` or `elevation =` found anywhere in non-test Kotlin.
- **Blur:** `S/LiveStepsFeed.kt:~187-191` `launchBackdrop` applies `blur(14.dp)` to the whole app while a launch is in flight, with a 55%
  black scrim (`:233`, `LAUNCH_SCRIM`).
- Radial glow brushes (soft light, blur-like): `S/puppet/CompiledPuppetRig.kt:327,339`, `S/MascotNodeTerrarium.kt:821,1002`,
  `S/CreatureEgg.kt:71`, `S/TissueRendering.kt:34`, `S/DistrictScreen.kt:120`. These are artwork, not UI chrome; call it a judgement
  call whether the rule reaches them.

### 3.5 Spinners, press states, sizes

- **Spinners:** no `CircularProgressIndicator`/`LinearProgressIndicator` in non-test Kotlin. Infinite loops exist but are decorative
  artwork, not progress: `S/tour/TourOverlay.kt:289` (tour ring), `S/MascotNodeTerrarium.kt:228`, `S/CreatureRigGallery.kt:106`.
- **Press states:** zero uses of `indication = null` or `LocalIndication` in `shared`. All 35 `clickable(` plus `selectable`/`toggleable`
  sites, `AzphaltPill` included, inherit Material's ripple under `MaterialTheme`. That is a press state on every control.
  Material `Button`/`TextButton`/`Checkbox` (`AzphaltDialog.kt:44,55`, `McpElicitationDialog.kt`, `McpServerConnections.kt:367`) add more.
  Selection also scales the selected pill and record to 1.018x (`azphaltSelectedTransform`, 9 sites), which is a selected state, not a press
  state, and is skipped under reduced motion.
- **Sub-11sp text:**
  - `S/CardFlipUi.kt:109, 111` 10sp
  - `S/HiveWorkspace.kt:736` 10sp (`StateChip`)
  - `S/MermaidFlowchartPreview.kt:123` 10sp
  - `S/ShellRail.kt:252` 9sp (the wordmark "THE"), `:348` 9sp (keeper name)
  - `S/HiveWorkspace.kt:720` is not below 11sp (it only zeroes letter-spacing); not a violation.
- **Sub-48dp targets:** none found that are interactive. Drawn sizes below 48 that are non-interactive or sit inside a 48dp hit box:
  `S/ShellRail.kt:343` 36dp mark (inside `semantics`, no click), `S/InboxBadge.kt:255,303` 24dp glyph, `S/HiveWorkspace.kt:846` 34dp
  hexagon (hit box 48). `S/KeeperAppearance.kt:348` switch track 43x24dp is inside a `toggleable` row with `heightIn(min = 48dp)`, so it passes.
  `S/KeeperAppearance.kt:199-205` swatch is exactly 48dp.
- **Radii outside 999/26/20/18:** see 2.3 (record 22-30, note 16, dialog 28, many 24/19/14/12/10).

### 3.6 The library breaks the strict rules too

`Primitives.kt:398` (chip outline, 1.5dp), `ConfirmDialog.kt:116,162` (3dp and 1.5dp borders), `H2g2Chip` ripple via `LocalIndication`, and
`DESIGN.md` section 10's own chip spec. Adopting the library does not by itself make aive compliant with the stricter HTML-guide rules.

### 3.7 Docs drift inside aive (found while auditing)

`docs/BRANDING.md:55` says a `Quiet` pill is "outlined in the surface's content colour" and `:62` says "No control takes its colour from a
hash of its name". The code does the opposite: `AzphaltUi.kt:~237` fills a Quiet pill with `Azphalt.hue(seed)`, a hash of the seed. The
doc is stale or the code drifted; decide before adopting a library chip whose default is also filled.

## 4. Library surfaces aive does not use, and where they would fit

Aive imports from the library only: workflow/terrarium data types (`H2g2WorkflowState` 63 refs, `H2g2Terrarium*`, swarm adornments),
`H2g2Nest` + `Option` (1 file), `StackEntrance`, `staggerDelayMs`, `stackInterval`, `NEST_MORPH_MS`, `NestMorphShape`, `nestSurface`,
`LocalNestDepth`, `H2g2Motion.Ease`, `H2g2Motion.updateDurationScale`. It never calls `H2g2Theme`, `GroundState`, `H2g2Chip`, any screen,
or any menu.

| # | Library surface | Aive screen it could replace | Fit | Host callbacks it needs |
|---|---|---|---|---|
| 4.1 | `H2g2ConfirmDialog` (+ `ConfirmTone.Elevated`, `badge`, `extraContent`) | `AzphaltConfirm` (20 sites/11 files) and the confirm-style `AzphaltAlertDialog` uses | Good for the confirm cases; poor for info prompts | `title, message, confirmLabel, onConfirm, onDismiss`; pass localised `cancelLabel` (library default is English); `confirmTint` to keep non-red confirms. Loses `rememberGrowFromPill` entrance |
| 4.2 | `H2g2JobProgressBar` + `JobProgressBarState` | Model/package install progress in `AzphaltStoreScreen` (`PreparedAzphaltModelInstallPanel` ~`:1067`); `LiveStepsFeed` launch overlay | Plausible, no current progress bar exists in aive (no `ProgressIndicator` use anywhere) | A `JobProgressBarState` driven by a coroutine calling `advanceTo/complete/fail` from the install flow. Sets itself at the top of the window and pads for system bars |
| 4.3 | `H2g2PillMenu` + `MenuNode` + `PillMenuPlacement.TopRight/TopLeft` | `PillStack` at `ControlRoomUi.kt:1091` (the one call site) | Medium: adds slot-reel scrolling, entrance lottery, shingled trail that `PillStack` lacks (no scroll) | `roots: List<MenuNode>` (map `PillStackItem.key` -> `MenuNode.id`, `endCap` -> `cap`), `onRun(tokens, isTerminal)` mapped to `onSelect(key)` using `MenuNode.value = key`. Needs **extra** work: the library cannot hang under a breadcrumb step (`anchorKey`), restore `initialPath`, or highlight `selectedKey`. The keeper-requested downward, breadcrumb-anchored layout is aive-specific; `TopLeft` is the nearest placement |
| 4.4 | `H2g2Nest` / `NestScope.Option` / `Nest` | Add-on option chooser already uses it; candidates: role tray, error-details toggles, template groups (currently `AzphaltChildBand` under a pill) | Good where a pill opens options inside a card | `label`, `hue`, `expanded/onExpandedChange`, `onPick`; it registers its own `BackHandler`, so aive's `PlatformBackHandler` order must be checked |
| 4.5 | `H2g2SettingsScreen` family | Keeper's room (`KeeperRoom.kt`, `ProviderManagementScreens.kt:297`) | Poor. The library's `SettingsState` is Termux settings (fullscreen, PTY, shell, update check). `SettingsPage`, `SettingRow`, `SegmentedToggle` are `internal`, so the reusable parts are not callable | none usable as is. If wanted, ask the library to make `SettingRow`/`SettingsPage` public |
| 4.5a | `H2g2AiSettingsScreen` | `ProviderCredentialSetup` (`ProviderCredentialUi.kt:57`) and the provider rows | Partial: provider, model, base URL, API key with reveal | `AiSettingsState`, `AiSettingsDraft`, `onDraftChange`, `onSave(AiConfig, key)`. Aive additionally has OAuth, device-code sign-in, keyless confirm; the library has none |
| 4.5b | `H2g2McpServerScreen` | `McpServerConnections.kt:103` | No. The library screen controls a locally hosted MCP server (start/stop, token, shell exec); aive's is the client-side connections list | - |
| 4.5c | `H2g2HistoryScreen`, `H2g2EnvironmentScreen`, `H2g2IsolationAuditScreen` | none (Termux-specific: command history, env vars, isolation audit) | No | - |
| 4.6 | `H2g2FilesScreen` (+ `H2g2FolderPicker`, `H2g2PathPickerScreen`, `H2g2StorageScreen`, `H2g2MarkdownPreview`) | Aive has no file-manager screen. Nearest: `ArtifactBrowse.kt` `HiveMadeSection` (artifact list), folder/path selection around `RepositoryServiceScreen.kt`/`RoleSurfaceFilePicker` | Weak for artifacts (they are not a filesystem); possible for a repository or folder picker. Unverified | `listDir`, `search`, `storageStats`, `previewFile`, `onOpenFile`, create/delete/rename/move/copy/share callbacks, `BackStepState` |
| 4.7 | `H2g2EditorScreen` | No dedicated text editor. Closest: inline text areas in `CompanyCustomizationScreen.kt`, `HiveJobEditor.kt`, role instruction editing | Weak, speculative | `fileName, content, dirty, error, onContentChange, onSave, onBack, wrap` |
| 4.8 | `H2g2AiChatScreen` + `AiMessage` | Aive has no chat screen as a Compose surface (`chat/` is Telegram/Discord transport; the Queen is driven from the hive feed bar, `HiveWorkspace.kt:~590-650`) | Poor fit: the library's chat is an "ask for a shell command" panel with `onUseCommand` | `messages, busy, apiKeyConfigured, onAsk, onUseCommand, onOpenSettings` |
| 4.9 | `H2g2Splash` | Android uses the platform `SplashScreen` theme (`androidApp/.../themes.xml`, `MainActivity.kt`); no Compose splash | Poor: the hand counting four then two is HG2Gui's mark, not Aive's | `onFinished`, optional `wordmark = null` |
| 4.10 | `H2g2Chip` | `AzphaltPill` and `AzphaltTabs` | See 1.3 | `label, onClick, tint, selected, role, contentDescription` |
| 4.11 | `statusColor`, `readableOn`, `minTouch`, `screenInsets`, `maxContentWidth` | Error text, link text, inset policy, wide-window columns | Additive, low risk | none |
| 4.12 | `H2g2WorkflowMap`, `H2g2Terrarium`, `H2g2PerimeterReveal`, `H2g2FilesReveal`, `BreakFreeState`, `SlotReel` | Aive projects into the library's terrarium data types but draws its own hive (`WorkflowTerrarium.kt`, `HiveWorkspace.kt`); a perimeter reveal could open the Keeper's room or a creature panel from its pill | Low, optional | `H2g2PerimeterReveal(visible, origin rect, onClosed)` |

## 5. Recommended adoption order

Each step is independently mergeable. Order is by increasing risk. Steps 1 and 2 change nothing visible.

| # | Step | Files touched | Risk |
|---|---|---|---|
| 1 | **Pin the dependency.** Change `shared/build.gradle.kts:76` from `main-SNAPSHOT` to the `0.6.0` tag (see 5.1). Re-run the Gradle build, `vendor/` path and CI. | `shared/build.gradle.kts`, possibly `settings.gradle.kts` (the `includeBuild` substitution at `:32-36` is unaffected) | Low. `0.6.0` is code-identical to HEAD (checked) |
| 2 | **De-duplicate identical tokens.** Make `Azphalt.Ink/Yellow/White`, `hues`, `caps`, `grounds`, `groundNamed` delegate to `H2g2.*` and `grounds`. Keep `Azphalt.Ground` or typealias to `Ground`; keep `Azphalt.currentGround` and its `Settings` persistence. Add a unit test asserting the equality (it is true today, so the test pins it). | `S/AzphaltUi.kt` (object `Azphalt`) | Low. Hex values are identical; `Ground` has `weight` in both |
| 3 | **Add `statusColor`/`readableOn` for new error and link text only.** No call-site rewrite. Optionally `minTouch(minTouchSize())` in new code. | new call sites only | Low. Additive. Uses `LocalH2g2Ground`, so provide it first (step 4) or accept Mustard |
| 4 | **Bridge the ground into the library without `H2g2Theme`.** In `GeministratorTheme` (`App.kt:1558-1570`) add `CompositionLocalProvider(LocalH2g2Ground provides <library Ground named Azphalt.currentGround.name>)`. Do not add `H2g2Theme` (see 2.5, 2.6). | `S/App.kt` | Low. Only needed once step 3, 5 or 6 lands |
| 5 | **Replace `AzphaltConfirm` only** with `H2g2ConfirmDialog` (20 sites/11 files, one wrapper). Keep `AzphaltAlertDialog` for info prompts, `McpConsent/Elicitation`. Decide on the outlined cancel (3.6). Pass `cancelLabel` from `Res.string.confirm_cancel`. Re-apply the grow entrance or accept losing it. | `S/AzphaltDialog.kt` (wrapper body only) | Medium. Visual change to 11 files' dialogs; red confirm default must be overridden for non-destructive verbs |
| 6 | **Job progress bar for installs.** Add `H2g2JobProgressBar` in the AddOns install flow (`ControlRoomUi.kt:1618` -> `AzphaltStoreScreen`), driven from the existing install callbacks. | `S/AzphaltStoreScreen.kt`, maybe `S/ControlRoomUi.kt` | Low to medium. New UI, no replacement; verify it does not collide with insets handled by `androidApp` |
| 7 | **Retire the hexagon controls** (the rule-breaking item): replace `RoundGlyphButton` with a label-only `H2g2Chip`-style capsule (words: UNDO, REDO, CENTRE, +, -), delete `HexagonShape`, `HiveGlyph`, `Glyph`. Drop `HiveGlyph` icons first for the two glyph-only buttons (zoom, close). | `S/HiveWorkspace.kt:805-905` and its 3 call files | Medium. Real layout change: nine capsules need ~410dp+; the `HiveControlsOneLineFrom` threshold (`:~359-420`) will move. Captions already carry the words, so a11y does not regress |
| 8 | **Optional: PillStack -> `H2g2PillMenu`.** Only if the breadcrumb anchor (`anchorKey`), `initialPath` and `selectedKey` can be given up or added to the library. | `S/PillStack.kt`, `S/ControlRoomUi.kt:1091` | High relative to value: behaviour the keeper explicitly asked for would change |
| 9 | **Do not adopt:** `contrastingText`, `H2g2Type` wholesale, `H2g2Theme`, `H2g2SettingsScreen` family, `H2g2AiChatScreen`, `H2g2Splash`. | - | Regressions above (2.1, 2.2, 2.5, 4.5, 4.8, 4.9) |

Rule fixes that need no library at all, any time: sub-11sp text (`CardFlipUi.kt:109,111`, `HiveWorkspace.kt:736`,
`MermaidFlowchartPreview.kt:123`, `ShellRail.kt:252,348`); the `Quiet` pill doc drift (3.7); `PillStack` easing (2.4); the ripple (3.5), which
would need `indication = null` or a custom `LocalIndication` for the "no press states" rule.

### 5.1 Does pinning to tag `0.6.0` instead of `main-SNAPSHOT` matter?

Facts (git, checked):
- Tag `0.6.0` points at `5e32873`. `main` is `b85c269`. Between them: `04fc4b3` (docs, README, KDoc) and the merge `b85c269`.
- `git diff 5e32873 HEAD -- src` filtered to non-comment lines is **empty**: the commit after the tag changed only KDoc and comments in
  13 files, no code. So `0.6.0` and `main` expose the same API.
- Tags `0.5.0` and `0.5.1` do **not** contain `H2g2Motion`, `StackEntrance`, `staggerDelayMs`, `stackInterval`, `NEST_MORPH_MS`,
  `NestMorphShape`, `nestSurface`, `LocalNestDepth`, `H2g2Nest`, `H2g2Theme`, `H2g2Chip` or `statusColor` (git grep per symbol). Aive already
  imports the first nine. So `0.5.1` is unusable; `0.6.0` has all of them. (The library README says `0.5.1` includes nest and
  motion; the tag contents say otherwise, and `README.md`/`CHANGELOG.md` do not mention `0.6.0` at all. CHANGELOG still says "Not yet tagged".)
- Every symbol aive uses today (listed in section 4) and every one this plan adds (`H2g2ConfirmDialog`, `H2g2JobProgressBar`,
  `statusColor`, `readableOn`, `minTouch`, `LocalH2g2Ground`, `grounds`, `H2g2.*`) exists at `0.6.0`.

Conclusion: for symbols aive needs, pinning to `0.6.0` is equivalent to `main-SNAPSHOT` and safer (reproducible; the library's own README warns a
floating snapshot "makes KMP metadata non-reproducible" and can resolve stale per-target publications through JitPack). Costs: you must
bump the pin manually for future library changes; `0.6.0`'s own build was not run here, and whether JitPack has built that tag was not
checked (the library's `jitpack.yml` exists). The library's own dependency on Conveyance is pinned to its `0.5.0` tag (per its README), so aive stays on that
transitive version either way.
