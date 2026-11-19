# Containment: what belongs to something is drawn inside it

The Aive speaks HG2Gui's motion through conveyance-h2g2 (`Nest.kt`, `Motion.kt`). A thing that belongs to something else opens inside it, not somewhere else on the screen. A pill grows into a card that holds its options. A picked option grows into a card inside that first card, and its siblings collapse into rods along the card's edge. Back closes the innermost open card first. Each level of depth gets its own surface: `recordTile`, then `note`, then `well`.

## Where the Aive does this

| Surface | How it opens now |
|---|---|
| Every `AzphaltRecord` well (settings, store, memory, library, inbox, creature artifacts) | `AzphaltChildBand`: grows from its record's top-start corner over h2g2's `NEST_MORPH_MS`. The band's contents are one nest level deeper, so a well inside a well takes the next surface down. |
| Pill-toggled forms (Continue, Re-run from here, error details, template groups, hive-card import, lineage modes) | `AzphaltChildBand` under the pill that opens them. Before, the form appeared with no motion. |
| Field offers (repository autocomplete, prompt suggestions) | `AzphaltOfferBand`: the offers sit inside the field's band, not in a popup. The field keeps focus. |
| Add-on option chooser | `H2g2Nest`: the pill opens into a card holding the options. |
| Role preview | Grows out of the role tray, inside it. It used to be a bottom sheet. |
| Dialogs (`AzphaltAlertDialog`, `AzphaltConfirm`, MCP consent/elicitation), the hive actions menu, the docked creature panel | `rememberGrowFromPill`: the card grows out of a capsule, so it reads as the pressed pill opening. |
| Keeper's room on a phone | `AzphaltNestSwap`: the part grows out of the list from its top-start corner and folds back into it on Back. |
| Stack entrances | `AzphaltEntrance` is h2g2's `StackEntrance`, with h2g2's capped stagger. |

When motion is reduced, the end state shows at once or with a short fade. `App.kt` passes `AzphaltMotion.reduceMotion` to conveyance's `LocalReducedMotion` and to `H2g2Motion`.

## Not converted, and why

- **The compact creature sheet** (`ControlRoomUi.kt`) and **`CardFlipSheetHost`** remain bottom sheets. A phone has no room to open the creature panel inside a creature on the field, and the sheet's drag-to-dismiss is the gesture people expect there. Everything inside the sheet nests: its wells use the child band. This is an open decision.
- **Android update and crash prompts** (`androidApp`) use `AzphaltAlertDialog`, so they grow from a pill too. Native desktop file pickers are outside Compose.
