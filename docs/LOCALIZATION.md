# Localization

The Aive ships in English only, but its user-facing UI text lives in string resources so another language can be added without touching Kotlin.

## Where strings live

| Code | Resource file | Accessed with |
| --- | --- | --- |
| Shared Compose UI (`shared/src/commonMain`) — Android, desktop and web | `shared/src/commonMain/composeResources/values/strings.xml` | `stringResource(Res.string.key)` in composables, `getString(Res.string.key)` in coroutines, `pluralStringResource(Res.plurals.key, count, count)` for counts |
| Android-only screens (`androidApp/src/main`) | `androidApp/src/main/res/values/strings.xml` | `androidx.compose.ui.res.stringResource(R.string.key)` |
| GitHub-flavor Android screens (`androidApp/src/github`) | `androidApp/src/github/res/values/strings.xml` | same as above |

`Res` is generated in `com.hereliesaz.geministrator.resources` (`compose.resources { packageOfResClass = ... }` in `shared/build.gradle.kts`). Each key is imported individually, e.g. `import com.hereliesaz.geministrator.resources.settings_save_role`.

## Key naming

`<screen>_<words of the English text>`, lowercase with underscores, for example `memory_forget_everything` or `settings_crash_reports`. The screen prefix is the file's area: `overview`, `runs`, `inbox`, `library`, `swarm`, `artifacts`, `repositories`, `compute`, `memory`, `settings`, `store`, `inspector`, `lineage`, `nav`, `app`, `addons`, `hall_monitor`, `provider_credential`, `repository_credential`, `repository_progress`, `terrarium`, `azphalt`. Text used by three or more screens uses `common_`. A numeric suffix (`_2`) separates different texts that would otherwise share a key.

Rules:

- Use positional arguments (`%1$s`, `%2$s`; `%1$d` in plurals) instead of string concatenation, so a translation can reorder them.
- Use `<plurals>` when text depends on a count.
- Compose resources process only `\n`, `\t`, `\uXXXX` and `\\` escapes; apostrophes and quotes are written literally. Android `res/values` files still need `\'` and `\"`.
- Keep wording identical when moving a string; tests and screenshots depend on it.

## Adding a language

1. Copy `shared/src/commonMain/composeResources/values/strings.xml` to `values-<lang>/strings.xml` (e.g. `values-es`) and translate the values; keep the keys and the `%N$s` placeholders.
2. Do the same for `androidApp/src/main/res/values/strings.xml` and `androidApp/src/github/res/values/strings.xml` (`values-<lang>/`).
3. Missing keys fall back to English, so a partial translation is safe.

## Intentionally not localized

- Prompts, system prompts and objectives sent to models, workflow/role definitions and demo workflow data (e.g. `TerrariumPreview`), the `⟦memory⟧` marker and memory-clerk text.
- Log messages (`platformDebugLog`), exception messages from `error(...)`/`require(...)`, protocol strings, metadata keys, IDs, file names, animation labels and composable seeds.
- Catalog display names that are data (`ProviderCatalog`, `RepositoryServiceCatalog`, Azphalt package metadata), the theme ground names (`Mustard`, `Navy`, ...; also used as a persisted key), and the app name `The Aive` in the desktop window title.
- Enum labels (`ControlRoomDestination`, store/library filters, `LaunchLineageMode`), mascot activity labels (covered by exact-text unit tests), and status messages set from plain click handlers (e.g. "Loaded …", validation messages in MindMap/RunLineage launch forms). These still need a follow-up to move to resources.
- Workflow engine text shown in the UI but produced outside the UI layer (task/artifact labels, run step messages in `workflow/` and `ApplicationRuntime*`).
- Unused legacy screens in `ControlRoomScreens.kt` / `ProviderManagementScreens.kt` (`CompanyScreen`, `WorkflowTemplateScreen`, `ArtifactFileManagerScreen`, `CompanyProviderScreen`).

## Locale-sensitive formatting

Byte sizes (`formatByteSize` in `AzphaltStoreScreen`, MiB/GiB in `ComputeDelegationScreen`) and other numbers use fixed English units and `toString()`; there are no hardcoded date patterns in the UI. Revisit these when a non-English locale ships.
