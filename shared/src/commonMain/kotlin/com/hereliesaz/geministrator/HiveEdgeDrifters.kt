package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.a2a.A2aAgentConnection
import com.hereliesaz.geministrator.a2a.A2aAgentConnections
import com.hereliesaz.geministrator.a2a.A2aDrifter
import com.hereliesaz.geministrator.a2a.A2aDrifters
import com.hereliesaz.geministrator.a2a.DrifterTrouble
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.confirm_cancel
import com.hereliesaz.geministrator.resources.district_import_agent
import com.hereliesaz.geministrator.resources.hive_edge_drifter_address
import com.hereliesaz.geministrator.resources.hive_edge_drifter_bad_address
import com.hereliesaz.geministrator.resources.hive_edge_drifter_explain
import com.hereliesaz.geministrator.resources.hive_edge_drifter_eyebrow
import com.hereliesaz.geministrator.resources.hive_edge_drifter_full
import com.hereliesaz.geministrator.resources.hive_edge_drifter_give_token
import com.hereliesaz.geministrator.resources.hive_edge_drifter_in_swarm
import com.hereliesaz.geministrator.resources.hive_edge_drifter_let_in
import com.hereliesaz.geministrator.resources.hive_edge_drifter_needs_token
import com.hereliesaz.geministrator.resources.hive_edge_drifter_no_card
import com.hereliesaz.geministrator.resources.hive_edge_drifter_no_jsonrpc
import com.hereliesaz.geministrator.resources.hive_edge_drifter_no_swarm
import com.hereliesaz.geministrator.resources.hive_edge_drifter_not_a_card
import com.hereliesaz.geministrator.resources.hive_edge_drifter_read
import com.hereliesaz.geministrator.resources.hive_edge_drifter_reading
import com.hereliesaz.geministrator.resources.hive_edge_drifter_send_away
import com.hereliesaz.geministrator.resources.hive_edge_drifter_send_away_body
import com.hereliesaz.geministrator.resources.hive_edge_drifter_send_away_title
import com.hereliesaz.geministrator.resources.hive_edge_drifter_skills
import com.hereliesaz.geministrator.resources.hive_edge_drifter_unreachable
import com.hereliesaz.geministrator.resources.hive_edge_drifter_wants_token
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val DrifterGround = Color(0xFF2A231E)

/**
 * Remote A2A agents at the hive's edge (docs/Swarm-OS.md, drifters; docs/A2A.md). "Bring in a
 * drifter" reads an Agent Card; the drifter waits at the edge as a creature with its name,
 * description and skills, and whatever went wrong reading it. "Let it in" (the one Heroic act here)
 * makes it an [A2aAgentConnection], the list the Keeper's room shows too, and a role in the swarm that
 * prefers its provider. Drifters already in follow, with a way to send them away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun A2aDrifterShelf(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    /** The active swarm; empty while no runtime is awake (nothing can be let in then). */
    swarmRoles: List<RoleDefinition>,
    connectedProviderIds: Set<String>,
    onSaveRoles: (List<RoleDefinition>) -> Unit,
    /** Links a keyless provider (no token), as the free providers are. */
    onConnectKeyless: (String) -> Unit,
    /** Opens the provider credential step, where a token is entered and kept in the platform store. */
    onGiveToken: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val waiting by A2aDrifters.waiting.collectAsState()
    val residents by A2aAgentConnections.connections.collectAsState()
    var addressDraft by remember { mutableStateOf("") }
    // A failure at "Let it in" lands on that drifter, by its card URL.
    var letInTrouble by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sendingAway by remember { mutableStateOf<A2aAgentConnection?>(null) }
    val noSwarm = stringResource(Res.string.hive_edge_drifter_no_swarm)
    val full = stringResource(Res.string.hive_edge_drifter_full)
    val residentUrls = residents.mapTo(hashSetOf()) { it.cardUrl }
    val atTheEdge = waiting.filterNot { it.cardUrl in residentUrls }
    // One Heroic act: letting in the newest drifter that can come in.
    val hero = atTheEdge.firstOrNull { it.canBeLetIn }?.cardUrl

    fun letIn(drifter: A2aDrifter) {
        val card = drifter.card ?: return
        if (swarmRoles.isEmpty()) {
            letInTrouble = letInTrouble + (drifter.cardUrl to noSwarm)
            return
        }
        val connection = try {
            A2aAgentConnections.add(drifter.cardUrl)
        } catch (_: IllegalArgumentException) {
            letInTrouble = letInTrouble + (drifter.cardUrl to full)
            return
        }
        A2aAgentConnections.rename(connection.providerId, drifter.name)
        // A keyless agent is linked now; one that asks for a token waits for it, in the swarm.
        if (!card.bearerAuth) onConnectKeyless(connection.providerId)
        onSaveRoles(A2aDrifters.swarmWith(swarmRoles, A2aDrifters.roleFor(connection, card)))
        A2aDrifters.sendAway(drifter.cardUrl)
        letInTrouble = letInTrouble - drifter.cardUrl
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill(
            label = stringResource(Res.string.district_import_agent),
            seed = "edge-bring-in-drifter",
            selected = open,
            onClick = { onOpenChange(!open) },
        )
        if (open) {
            Text(stringResource(Res.string.hive_edge_drifter_explain), style = AzphaltType.body, color = azphaltOnSurface())
            AzphaltTextField(
                value = addressDraft,
                onValueChange = { addressDraft = it },
                label = stringResource(Res.string.hive_edge_drifter_address),
                placeholder = "https://agent.example",
            )
            AzphaltPill(
                label = stringResource(Res.string.hive_edge_drifter_read),
                seed = "edge-read-card",
                tone = if (hero == null && addressDraft.isNotBlank()) PillTone.Primary else PillTone.Quiet,
                onClick = {
                    val address = addressDraft
                    if (address.isNotBlank()) {
                        addressDraft = ""
                        scope.launch { A2aDrifters.bringIn(address) }
                    }
                },
            )
        }
        atTheEdge.forEach { drifter ->
            DrifterRow(
                drifter = drifter,
                trouble = letInTrouble[drifter.cardUrl] ?: troubleText(drifter),
            ) {
                if (drifter.canBeLetIn) {
                    AzphaltPill(
                        label = stringResource(Res.string.hive_edge_drifter_let_in),
                        seed = "edge-let-in-${drifter.cardUrl}",
                        tone = if (drifter.cardUrl == hero) PillTone.Primary else PillTone.Quiet,
                        onClick = { letIn(drifter) },
                    )
                }
                AzphaltPill(
                    label = stringResource(Res.string.hive_edge_drifter_send_away),
                    seed = "edge-send-away-${drifter.cardUrl}",
                    onClick = {
                        A2aDrifters.sendAway(drifter.cardUrl)
                        letInTrouble = letInTrouble - drifter.cardUrl
                    },
                )
            }
        }
        residents.forEach { resident ->
            val linked = resident.providerId in connectedProviderIds
            ResidentRow(
                resident = resident,
                status = if (linked) {
                    stringResource(Res.string.hive_edge_drifter_in_swarm)
                } else {
                    stringResource(Res.string.hive_edge_drifter_needs_token)
                },
            ) {
                if (!linked) {
                    AzphaltPill(
                        label = stringResource(Res.string.hive_edge_drifter_give_token),
                        seed = "edge-give-token-${resident.providerId}",
                        onClick = { onGiveToken(resident.providerId) },
                    )
                }
                AzphaltPill(
                    label = stringResource(Res.string.hive_edge_drifter_send_away),
                    seed = "edge-send-away-${resident.providerId}",
                    tone = PillTone.Danger,
                    onClick = { sendingAway = resident },
                )
            }
        }
    }

    sendingAway?.let { resident ->
        AzphaltAlertDialog(
            title = stringResource(Res.string.hive_edge_drifter_send_away_title, resident.name),
            text = stringResource(Res.string.hive_edge_drifter_send_away_body),
            confirmLabel = stringResource(Res.string.hive_edge_drifter_send_away),
            onConfirm = {
                sendingAway = null
                if (resident.providerId in connectedProviderIds) onDisconnect(resident.providerId)
                A2aAgentConnections.remove(resident.providerId)
                if (swarmRoles.isNotEmpty()) onSaveRoles(A2aDrifters.swarmWithout(swarmRoles, resident.providerId))
            },
            onDismissRequest = { sendingAway = null },
            dismissLabel = stringResource(Res.string.confirm_cancel),
        )
    }
}

/** Why a drifter could not be read, plainly; null when nothing went wrong. */
@Composable
private fun troubleText(drifter: A2aDrifter): String? = when (drifter.trouble) {
    null -> null
    DrifterTrouble.BadAddress -> stringResource(Res.string.hive_edge_drifter_bad_address)
    DrifterTrouble.Unreachable -> stringResource(Res.string.hive_edge_drifter_unreachable, drifter.detail ?: A2aDrifters.hostOf(drifter.cardUrl))
    DrifterTrouble.NoCard -> stringResource(Res.string.hive_edge_drifter_no_card)
    DrifterTrouble.NotACard -> stringResource(Res.string.hive_edge_drifter_not_a_card)
    DrifterTrouble.NoJsonRpc -> stringResource(Res.string.hive_edge_drifter_no_jsonrpc)
}

/** A drifter waiting at the edge: its creature, what its card says, and what went wrong. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrifterRow(drifter: A2aDrifter, trouble: String?, actions: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(DrifterGround)
            .padding(8.dp),
    ) {
        StableCreature(drifter.creature, 80)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(Res.string.hive_edge_drifter_eyebrow, A2aDrifters.hostOf(drifter.cardUrl)),
                style = AzphaltType.eyebrow,
                color = Azphalt.White.copy(alpha = 0.7f),
            )
            Text(drifter.name, style = AzphaltType.body, color = Azphalt.White)
            if (drifter.looking) {
                Text(stringResource(Res.string.hive_edge_drifter_reading), style = AzphaltType.endCap, color = Azphalt.White.copy(alpha = 0.7f))
            }
            A2aDrifters.descriptionOf(drifter.card).takeIf(String::isNotEmpty)?.let { about ->
                Text(about, style = AzphaltType.endCap, color = Azphalt.White.copy(alpha = 0.85f))
            }
            A2aDrifters.skillsOf(drifter.card).takeIf { it.isNotEmpty() }?.let { skills ->
                Text(
                    stringResource(Res.string.hive_edge_drifter_skills, skills.joinToString(", ")),
                    style = AzphaltType.endCap,
                    color = Azphalt.White.copy(alpha = 0.7f),
                )
            }
            if (drifter.card?.bearerAuth == true && trouble == null) {
                Text(stringResource(Res.string.hive_edge_drifter_wants_token), style = AzphaltType.endCap, color = Azphalt.White.copy(alpha = 0.7f))
            }
            // The failure lands on the drifter it belongs to.
            trouble?.let { Text(it, style = AzphaltType.body, color = Azphalt.Yellow) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions()
            }
        }
    }
}

/** A drifter already in the swarm (an A2A connection), as the Keeper's room lists it too. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResidentRow(resident: A2aAgentConnection, status: String, actions: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(DrifterGround)
            .padding(8.dp),
    ) {
        StableCreature(A2aDrifters.creatureFor(resident.cardUrl), 56)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(resident.name, style = AzphaltType.body, color = Azphalt.White)
            Text(status, style = AzphaltType.endCap, color = Azphalt.White.copy(alpha = 0.7f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions()
            }
        }
    }
}
