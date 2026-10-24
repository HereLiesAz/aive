package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_cancel
import com.hereliesaz.geministrator.resources.settings_ok
import com.hereliesaz.geministrator.resources.settings_setup_choose_file
import com.hereliesaz.geministrator.resources.settings_setup_crypto_unavailable
import com.hereliesaz.geministrator.resources.settings_setup_export
import com.hereliesaz.geministrator.resources.settings_setup_export_failed
import com.hereliesaz.geministrator.resources.settings_setup_file
import com.hereliesaz.geministrator.resources.settings_setup_file_intro
import com.hereliesaz.geministrator.resources.settings_setup_import
import com.hereliesaz.geministrator.resources.settings_setup_import_failed
import com.hereliesaz.geministrator.resources.settings_setup_imported
import com.hereliesaz.geministrator.resources.settings_setup_imported_compute
import com.hereliesaz.geministrator.resources.settings_setup_imported_relay
import com.hereliesaz.geministrator.resources.settings_setup_imported_summary
import com.hereliesaz.geministrator.resources.settings_setup_no_credentials_here
import com.hereliesaz.geministrator.resources.settings_setup_not_a_setup_file
import com.hereliesaz.geministrator.resources.settings_setup_passphrase
import com.hereliesaz.geministrator.resources.settings_setup_passphrase_confirm
import com.hereliesaz.geministrator.resources.settings_setup_passphrase_mismatch
import com.hereliesaz.geministrator.resources.settings_setup_passphrase_too_short
import com.hereliesaz.geministrator.resources.settings_setup_save_file
import com.hereliesaz.geministrator.resources.settings_setup_saved
import com.hereliesaz.geministrator.resources.settings_setup_unsupported_version
import com.hereliesaz.geministrator.resources.settings_setup_working
import com.hereliesaz.geministrator.resources.settings_setup_wrong_passphrase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private enum class SetupFileMode { Closed, Export, Import }

/**
 * What the settings screen needs to export and import a `.aivesetup` file. [export] returns the
 * encoded file; [import] decodes, validates and writes it, throwing [SetupFileException] before
 * writing anything when the passphrase is wrong or the file is unusable.
 */
class SetupFileActions internal constructor(
    val credentialsIncluded: Boolean,
    val export: suspend (passphrase: String) -> String,
    val import: suspend (encoded: String, passphrase: String) -> SetupImportSummary,
)

/**
 * Settings section for exporting and importing a `.aivesetup` file. Passphrases live only in this
 * composable's memory (never in durable UI state) and are cleared when the section closes.
 */
@Composable
internal fun SetupFileSection(
    projectFileService: ProjectFileService,
    actions: SetupFileActions,
) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(SetupFileMode.Closed) }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var validation by remember { mutableStateOf<String?>(null) }

    fun close() {
        mode = SetupFileMode.Closed
        passphrase = ""
        confirmation = ""
        validation = null
    }

    ProviderSectionLabel(stringResource(Res.string.settings_setup_file))
    Text(
        stringResource(Res.string.settings_setup_file_intro) +
            if (actions.credentialsIncluded) "" else " " + stringResource(Res.string.settings_setup_no_credentials_here),
        style = AzphaltType.body,
        color = azphaltOnSurface(),
    )
    when (mode) {
        SetupFileMode.Closed -> {
            AzphaltPill(
                stringResource(Res.string.settings_setup_export),
                "setup-export-open",
                onClick = { mode = SetupFileMode.Export },
                modifier = Modifier.fillMaxWidth(),
            )
            AzphaltPill(
                stringResource(Res.string.settings_setup_import),
                "setup-import-open",
                onClick = { mode = SetupFileMode.Import },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SetupFileMode.Export, SetupFileMode.Import -> {
            val exporting = mode == SetupFileMode.Export
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it; validation = null },
                label = { Text(stringResource(Res.string.settings_setup_passphrase)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (exporting) {
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it; validation = null },
                    label = { Text(stringResource(Res.string.settings_setup_passphrase_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            validation?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(
                    when {
                        working -> stringResource(Res.string.settings_setup_working)
                        exporting -> stringResource(Res.string.settings_setup_save_file)
                        else -> stringResource(Res.string.settings_setup_choose_file)
                    },
                    if (exporting) "setup-export-confirm" else "setup-import-confirm",
                    onClick = click@{
                        if (working) return@click
                        scope.launch {
                            if (exporting) {
                                validation = when {
                                    passphrase.length < MIN_PASSPHRASE_LENGTH ->
                                        getString(Res.string.settings_setup_passphrase_too_short, MIN_PASSPHRASE_LENGTH)
                                    passphrase != confirmation -> getString(Res.string.settings_setup_passphrase_mismatch)
                                    else -> null
                                }
                                if (validation != null) return@launch
                            } else if (passphrase.isEmpty()) {
                                validation = getString(Res.string.settings_setup_passphrase_too_short, MIN_PASSPHRASE_LENGTH)
                                return@launch
                            }
                            working = true
                            try {
                                message = if (exporting) {
                                    val encoded = actions.export(passphrase)
                                    val saved = projectFileService.saveSetupFileAs("aive-setup", encoded)
                                        ?: return@launch
                                    getString(Res.string.settings_setup_saved, saved.displayName) to null
                                } else {
                                    val opened = projectFileService.chooseAndReadSetupFile() ?: return@launch
                                    val summary = actions.import(opened.content, passphrase)
                                    getString(Res.string.settings_setup_imported) to setupSummaryText(summary)
                                }
                                close()
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: SetupFileException) {
                                // The exception's own text says which check failed; it never carries credential values.
                                message = setupFailureText(failure.failure) to failure.message
                            } catch (failure: Exception) {
                                // Messages from the stores never contain credential values.
                                message = getString(
                                    if (exporting) Res.string.settings_setup_export_failed else Res.string.settings_setup_import_failed,
                                ) to failure.message
                            } finally {
                                working = false
                            }
                        }
                    },
                )
                AzphaltPill(stringResource(Res.string.common_cancel), "setup-cancel", onClick = {
                    if (!working) close()
                })
            }
        }
    }
    message?.let { (title, body) ->
        AzphaltRecord(
            seed = "setup-file-message",
            eyebrow = stringResource(Res.string.settings_setup_file),
            title = title,
            body = body.orEmpty(),
            endCap = stringResource(Res.string.settings_ok),
            onClick = { message = null },
        )
    }
}

private const val MIN_PASSPHRASE_LENGTH = 8

private suspend fun setupFailureText(failure: SetupFileFailure): String = getString(
    when (failure) {
        SetupFileFailure.WrongPassphrase -> Res.string.settings_setup_wrong_passphrase
        SetupFileFailure.NotASetupFile -> Res.string.settings_setup_not_a_setup_file
        SetupFileFailure.UnsupportedVersion -> Res.string.settings_setup_unsupported_version
        SetupFileFailure.CryptoUnavailable -> Res.string.settings_setup_crypto_unavailable
    },
)

private suspend fun setupSummaryText(summary: SetupImportSummary): String = buildString {
    append(
        getString(
            Res.string.settings_setup_imported_summary,
            summary.projects,
            summary.definitions,
            summary.roles,
            summary.settings,
            summary.providers,
            summary.repositories,
        ),
    )
    if (summary.computeToken) append("\n").append(getString(Res.string.settings_setup_imported_compute))
    if (summary.remoteRelay) append("\n").append(getString(Res.string.settings_setup_imported_relay))
}
