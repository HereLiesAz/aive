package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.credential_hide
import com.hereliesaz.geministrator.resources.credential_show
import com.hereliesaz.geministrator.resources.settings_trace_export
import com.hereliesaz.geministrator.resources.settings_trace_export_content_off
import com.hereliesaz.geministrator.resources.settings_trace_export_content_on
import com.hereliesaz.geministrator.resources.settings_trace_export_content_warning
import com.hereliesaz.geministrator.resources.settings_trace_export_endpoint
import com.hereliesaz.geministrator.resources.settings_trace_export_endpoint_invalid
import com.hereliesaz.geministrator.resources.settings_trace_export_headers
import com.hereliesaz.geministrator.resources.settings_trace_export_headers_invalid
import com.hereliesaz.geministrator.resources.settings_trace_export_headers_note
import com.hereliesaz.geministrator.resources.settings_trace_export_intro
import com.hereliesaz.geministrator.resources.settings_trace_export_off
import com.hereliesaz.geministrator.resources.settings_trace_export_on
import com.hereliesaz.geministrator.telemetry.TraceExportSettings
import com.hereliesaz.geministrator.telemetry.TraceExportSettingsStore
import com.hereliesaz.geministrator.telemetry.WorkflowTraceExport
import com.hereliesaz.geministrator.telemetry.otlpTracesUrl
import com.hereliesaz.geministrator.telemetry.parseOtlpHeaders
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Keeper's room › About and privacy: OpenTelemetry trace export (docs/architecture/OBSERVABILITY.md).
 * Off by default. Turning it on needs a valid endpoint and headers; content is a separate toggle.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun TraceExportSettingsSection(
    store: TraceExportSettingsStore = WorkflowTraceExport.durable.store,
) {
    var settings by remember(store) { mutableStateOf(store.load()) }
    fun update(next: TraceExportSettings) {
        settings = next
        store.save(next)
    }
    val endpointValid = otlpTracesUrl(settings.endpoint) != null
    val headersValid = parseOtlpHeaders(settings.headers) != null

    ProviderSectionLabel(stringResource(Res.string.settings_trace_export))
    Text(stringResource(Res.string.settings_trace_export_intro), style = AzphaltType.body, color = azphaltOnSurface())
    AzphaltTextField(
        value = settings.endpoint,
        onValueChange = { update(settings.copy(endpoint = it)) },
        label = stringResource(Res.string.settings_trace_export_endpoint),
        placeholder = "https://collector.example.com:4318",
        errorMessage = if (settings.endpoint.isNotBlank() && !endpointValid) {
            stringResource(Res.string.settings_trace_export_endpoint_invalid)
        } else {
            null
        },
    )
    AzphaltTextField(
        value = settings.headers,
        onValueChange = { update(settings.copy(headers = it)) },
        label = stringResource(Res.string.settings_trace_export_headers),
        secret = true,
        showLabel = stringResource(Res.string.credential_show),
        hideLabel = stringResource(Res.string.credential_hide),
        errorMessage = if (!headersValid) stringResource(Res.string.settings_trace_export_headers_invalid) else null,
    )
    Text(stringResource(Res.string.settings_trace_export_headers_note), style = AzphaltType.body, color = azphaltOnSurface())
    AzphaltToggle(
        if (settings.enabled) stringResource(Res.string.settings_trace_export_on) else stringResource(Res.string.settings_trace_export_off),
        "trace-export-toggle",
        selected = settings.enabled,
        onClick = {
            when {
                settings.enabled -> update(settings.copy(enabled = false))
                endpointValid && headersValid -> update(
                    settings.copy(enabled = true, enabledAtEpochMillis = Clock.System.now().toEpochMilliseconds()),
                )
                else -> Unit
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (settings.enabled) {
        AzphaltToggle(
            if (settings.includeContent) {
                stringResource(Res.string.settings_trace_export_content_on)
            } else {
                stringResource(Res.string.settings_trace_export_content_off)
            },
            "trace-export-content-toggle",
            selected = settings.includeContent,
            onClick = { update(settings.copy(includeContent = !settings.includeContent)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(Res.string.settings_trace_export_content_warning), style = AzphaltType.body, color = azphaltOnSurface())
    }
}
