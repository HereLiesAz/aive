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
import com.hereliesaz.geministrator.workflow.PeerCollaborationEvidence
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
    var evidence by remember(project, sourceRun, provider) { mutableStateOf<List<PeerCollaborationEvidence>?>(null) }
    LaunchedEffect(host) {
        try { routes = host.peerRoutes() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = "Could not read consultation grants." }
    }
    ProviderSectionLabel("Specialist consultation and activation")
    Text("Cross-run messages (including expertise recruitment when no local live peer is eligible) and same-run dormant activation require separate exact directional grants. Cross-run replies need their own reverse grant; disclosure never grants memory-bank access. Enter the same run ID twice to permit one bounded, non-billed advisory activation of an idle receiving role. Messages may contain sensitive project information. No tool or execution authority is granted.",
        style = AzphaltType.body, color = azphaltOnSurface())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        routes.forEach { route ->
            Text("${if (route.activation) "Activation" else "Disclosure"} · ${route.project} · ${route.provider}\n${route.sourceRun} / ${route.sourceRole} → ${route.targetRun} / ${route.targetRole}",
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
        AzphaltPill("Inspect collaboration receipts for source run", "inspect-peer-collaboration", onClick = {
            if (!busy && project.isNotBlank() && sourceRun.isNotBlank() && provider.isNotBlank()) {
                val p = project.trim()
                val r = sourceRun.trim()
                val s = provider.trim()
                busy = true
                scope.launch {
                    try {
                        val result = host.peerCollaborationEvidence(p, r, s)
                        if (project.trim() == p && sourceRun.trim() == r && provider.trim() == s) evidence = result
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status = "Could not inspect collaboration receipts." }
                    finally { busy = false }
                }
            }
        })
        evidence?.let { rows ->
            Text("Retained same-run consultation observations (up to 24 hours; not expertise or task quality). " +
                "Missing replies are unknown, and objections count equally with support.",
                style = AzphaltType.body, color = azphaltOnSurface())
            if (rows.isEmpty()) Text("No attributable consultation receipts in this scope.",
                style = AzphaltType.body, color = azphaltOnSurface())
            rows.forEach { row ->
                Text("${row.senderRole} / ${row.senderSpecialist.take(12)} → ${row.recipientRole} / ${row.recipientSpecialist.take(12)}\n" +
                    "Accepted requests: ${row.accepted}; claimed: ${row.claimed}; rejected: ${row.rejected}; uncertain: ${row.uncertain}. " +
                    "Authored replies: ${row.replied}; accepted returns to original instance/session: ${row.returned}.",
                    style = AzphaltType.body, color = azphaltOnSurface())
            }
        }
        AzphaltPill("Review specialist grant", "review-peer-route", onClick = {
            if (!busy) pending = PeerRoute(project.trim(), provider.trim(), sourceRun.trim(), sourceRole.trim(),
                targetRun.trim(), targetRole.trim(), activation = sourceRun.trim() == targetRun.trim())
        })
        status.takeIf(String::isNotBlank)?.let {
            Text(it, style = AzphaltType.body, color = azphaltOnSurface())
        }
    }
    pending?.let { route ->
        AzphaltAlertDialog(
            title = if (route.activation) "Allow dormant specialist activation?" else "Allow cross-run consultation?",
            text = (if (route.activation) "Allow one tool-free, non-billed advisory invocation. " else "") + "Allow advisory messages from ${route.sourceRole} in ${route.sourceRun} to ${route.targetRole} in ${route.targetRun}, within ${route.project}, using ${route.provider}? Only this direction is authorized. Revoking cannot retrieve information already sent.",
            confirmLabel = if (route.activation) "Allow activation" else "Allow disclosure",
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
