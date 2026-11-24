package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.workflow.PeerRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Explicit, directional disclosure permission; never a workflow approval. */
@Composable
internal fun PeerConsentPart(host: WorkflowLibraryHost) {
    val scope = rememberCoroutineScope()
    var routes by remember { mutableStateOf<List<PeerRoute>>(emptyList()) }
    var project by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("") }
    var sourceRun by remember { mutableStateOf("") }
    var sourceRole by remember { mutableStateOf("") }
    var targetRun by remember { mutableStateOf("") }
    var targetRole by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PeerRoute?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(host) {
        try { routes = host.peerRoutes() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = "Could not read consultation grants." }
    }
    ProviderSectionLabel("Cross-run specialist consultation")
    Text("Off unless you grant an exact directional route. Messages may contain sensitive project information. This never grants tool or execution authority.",
        style = AzphaltType.body, color = azphaltOnSurface())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        routes.forEach { route ->
            Text("${route.project} · ${route.provider}\n${route.sourceRun} / ${route.sourceRole} → ${route.targetRun} / ${route.targetRole}",
                style = AzphaltType.body, color = azphaltOnSurface())
            AzphaltPill("Revoke route", "revoke-peer-${route.hashCode()}", onClick = {
                if (!busy) scope.launch {
                    busy = true
                    try {
                        host.revokePeerRoute(route)
                        routes = host.peerRoutes()
                        status = "Revocation saved. Undelivered notes under this grant cannot be replayed."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status = "Revocation failed." }
                    finally { busy = false }
                }
            })
        }
        AzphaltTextField(value = project, onValueChange = { project = it }, label = "Project ID")
        AzphaltTextField(value = provider, onValueChange = { provider = it }, label = "Provider ID")
        AzphaltTextField(value = sourceRun, onValueChange = { sourceRun = it }, label = "Source run ID")
        AzphaltTextField(value = sourceRole, onValueChange = { sourceRole = it }, label = "Sending role ID")
        AzphaltTextField(value = targetRun, onValueChange = { targetRun = it }, label = "Destination run ID")
        AzphaltTextField(value = targetRole, onValueChange = { targetRole = it }, label = "Receiving role ID")
        AzphaltPill("Review disclosure grant", "review-peer-route", onClick = {
            if (!busy) pending = PeerRoute(project.trim(), provider.trim(), sourceRun.trim(), sourceRole.trim(),
                targetRun.trim(), targetRole.trim())
        })
        status.takeIf(String::isNotBlank)?.let {
            Text(it, style = AzphaltType.body, color = azphaltOnSurface())
        }
    }
    pending?.let { route ->
        AzphaltAlertDialog(
            title = "Allow cross-run consultation?",
            text = "Allow advisory messages from ${route.sourceRole} in ${route.sourceRun} to ${route.targetRole} in ${route.targetRun}, within ${route.project}, using ${route.provider}? Only this direction is authorized. Revoking cannot retrieve information already sent.",
            confirmLabel = "Allow disclosure",
            dismissLabel = "Cancel",
            onDismissRequest = { pending = null },
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                if (!busy) scope.launch {
                    busy = true
                    try {
                        status = if (host.grantPeerRoute(route)) "Grant saved." else "Grant refused: check project, run, role and provider IDs."
                        routes = host.peerRoutes()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status = "Could not save grant." }
                    finally { busy = false }
                }
            },
        )
    }
}
