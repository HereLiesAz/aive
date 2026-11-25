package com.hereliesaz.geministrator

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.hereliesaz.geministrator.queen.CollectiveConversation
import com.hereliesaz.geministrator.queen.SpecialistProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

/** Local Keeper conversation with explicit history selection and deletion. */
@Composable
internal fun CollectiveChatPart(host: WorkflowLibraryHost) {
    val scope = rememberCoroutineScope()
    var conversations by remember { mutableStateOf<List<CollectiveConversation>>(emptyList()) }
    var profiles by remember { mutableStateOf<List<SpecialistProfile>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf((com.hereliesaz.geministrator.queen.DEFAULT_DELIBERATION_MILLIS / 60_000L).toString()) }
    var pending by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var inspect by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var importing by remember(selected) { mutableStateOf(false) }
    suspend fun refresh(preferred: String? = selected) {
        conversations = checkNotNull(host.collectiveHistory).invoke()
        profiles = host.collectiveProfiles?.invoke().orEmpty()
        selected = preferred?.takeIf { id -> conversations.any { it.id == id } } ?: conversations.firstOrNull()?.id
    }
    LaunchedEffect(host.collectiveBackgroundRevision) {
        suspend fun refreshSafely() {
            try { if (!busy) refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "Conversation history unavailable." }
        }
        val revision = host.collectiveBackgroundRevision
        if (revision == null) refreshSafely() else revision.collectLatest { refreshSafely() }
    }
    ProviderSectionLabel("Ask The Aive")
    Text("Advisory conversation only. Specialists are selected by relevance and configured model availability. Evidence associated with this conversation, including local process results, Keeper reports and research excerpts, is included in its next model calls; raw test output is not sent. Completed turns are saved locally. Each actual reply is banked separately when memory is enabled; all collective memory participates in associative recall. Deleting chat history does not erase the memories derived from it.",
        style = AzphaltType.body, color = azphaltOnSurface())
    conversations.forEach { conversation ->
        val title = conversation.turns.firstOrNull()?.question?.take(45) ?: "Empty conversation"
        AzphaltPill((if (conversation.id == selected) "Selected: " else "") + title,
            "collective-history-${conversation.id}", onClick = {
                if (!busy) { selected = conversation.id; deleting = false }
            })
    }
    val destination = conversations.singleOrNull { it.id == selected }
    val autoEvidence = host.collectiveAutomaticEvidence
    if (destination != null && autoEvidence != null) {
        Text("Automatic evidence delivery searches the shared local ledger for new topic matches to earlier questions and sends bounded evidence to configured models on the next turn. Other issues may be included; topic similarity is not proof of relevance. Disable to stop automatic delivery.",
            style = AzphaltType.body, color = azphaltOnSurface())
        AzphaltPill(if (destination.automaticEvidence) "Automatic evidence: on" else "Automatic evidence: off",
            "collective-auto-evidence", onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            checkNotNull(autoEvidence(destination.id, !destination.automaticEvidence))
                            refresh(destination.id)
                            status = ""
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { status = "Evidence delivery preference could not be saved." }
                        finally { busy = false }
                    }
                }
            })
    }
    val backgroundEvidence = host.collectiveBackgroundEvidence
    if (destination?.automaticEvidence == true && backgroundEvidence != null) {
        Text("Unattended reconsideration while the app is open uses only configured free routes. It may send bounded topic-matched evidence without another question. Disabling cancels the current wake; returned replies already retained remain in the evidence ledger.",
            style = AzphaltType.body, color = azphaltOnSurface())
        AzphaltPill(if (destination.backgroundEvidence) "Unattended reconsideration: on" else "Unattended reconsideration: off",
            "collective-background-evidence", onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            checkNotNull(backgroundEvidence(destination.id, !destination.backgroundEvidence))
                            refresh(destination.id)
                            status = ""
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { status = "Unattended reconsideration preference could not be saved." }
                        finally { busy = false }
                    }
                }
            })
    }
    destination?.turns.orEmpty().forEach { turn ->
        val result = turn.result
        val author = if (turn.automaticReconsideration) "Automatic reconsideration of earlier question" else "You"
        Text("$author: ${turn.question}", style = AzphaltType.body, color = azphaltOnSurface())
        Text(result.answer?.let { "The Aive: $it" } ?: "The Aive: No synthesized answer was obtained.",
            style = AzphaltType.body, color = azphaltOnSurface())
        if (result.unavailable.isNotEmpty()) Text("Unavailable: ${result.unavailable.joinToString()}",
            style = AzphaltType.body, color = azphaltOnSurface())
        result.stop?.let { stop ->
            val explanation = when (stop) {
                com.hereliesaz.geministrator.queen.DeliberationStop.NoFurtherReviewRequested -> "No further conversation requested; agreement is not inferred."
                com.hereliesaz.geministrator.queen.DeliberationStop.RepeatedObjection -> "Repeated review request; no resolution inferred."
                com.hereliesaz.geministrator.queen.DeliberationStop.ReviewUnavailable -> "A requested response was unavailable."
                com.hereliesaz.geministrator.queen.DeliberationStop.TimeBudget -> "Deliberation time budget reached."
                com.hereliesaz.geministrator.queen.DeliberationStop.NoInitialAssessment -> "No initial assessment obtained."
            }
            Text(explanation, style = AzphaltType.body, color = azphaltOnSurface())
        }
        if (result.reviewRequested && result.assessments.none { it.crossExamination }) {
            Text("Further conversation requested but not obtained.", style = AzphaltType.body, color = azphaltOnSurface())
        }
        if (result.automaticEvidenceIds.isNotEmpty()) Text(
            "Automatically selected evidence: " + result.automaticEvidenceIds.joinToString(),
            style = AzphaltType.body, color = azphaltOnSurface())
        if (result.observationRecipientsMissing.isNotEmpty()) Text(
            "Previously involved participants without a returned evidence-bearing reply: " +
                result.observationRecipientsMissing.joinToString { it.take(12) },
            style = AzphaltType.body, color = azphaltOnSurface())
        if (result.unexaminedRequests.isNotEmpty()) Text(
            "Unexamined requests: " + result.unexaminedRequests.joinToString { "#$it" },
            style = AzphaltType.body, color = azphaltOnSurface())
        if (result.assessments.isNotEmpty()) {
            Text("Internal conversation (rhetoracracy)", style = AzphaltType.body, color = azphaltOnSurface())
            result.assessments.forEachIndexed { index, assessment ->
                val target = assessment.respondsTo?.let { " (responds to #$it)" }
                    ?: if (assessment.crossExamination) " (response)" else ""
                val persuasion = assessment.persuadedBy?.let { " (reports persuaded by #$it)" }.orEmpty()
                Text("#${index + 1} ${assessment.role}$target$persuasion: ${assessment.text}", style = AzphaltType.body, color = azphaltOnSurface())
                if (assessment.observationIds.isNotEmpty()) Text(
                    "Prompt evidence: " + assessment.observationIds.joinToString(),
                    style = AzphaltType.body, color = azphaltOnSurface())
                if (inspect) Text("Configured Source: ${assessment.sourceId ?: "unknown"} / model: ${assessment.modelId ?: "unknown"}; instance ${assessment.specialistId?.take(12) ?: "legacy"}",
                    style = AzphaltType.body, color = azphaltOnSurface())
            }
        }
    }
    AzphaltPill(if (inspect) "Hide specialist provenance" else "Inspect specialist provenance",
        "collective-inspect", onClick = { inspect = !inspect })
    if (inspect && profiles.isNotEmpty()) {
        Text("Observed specialist call outcomes (not answer quality)", style = AzphaltType.body, color = azphaltOnSurface())
        profiles.forEach { profile ->
            Text("${profile.roleId} · ${profile.id.take(12)}: ${profile.answered} answers, ${profile.empty} empty, ${profile.failed} failed, ${profile.timedOut} timed out, ${profile.managedSessions} managed sessions",
                style = AzphaltType.body, color = azphaltOnSurface())
        }
    }
    AzphaltTextField(value = question, onValueChange = { question = it.take(1500) }, label = "Ask a question")
    AzphaltTextField(value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
        label = "Deliberation time budget (minutes, 1–120)")
    AzphaltPill("Ask The Aive", "collective-ask", onClick = {
        val prompt = question.trim()
        val ask = host.collectiveChat
        val budget = minutes.toLongOrNull()?.takeIf { it in 1L..120L }?.times(60_000L)
        if (!busy && budget == null) status = "Enter a deliberation time budget of 1–120 minutes."
        if (!busy && prompt.isNotEmpty() && ask != null && budget != null) {
            busy = true
            status = ""
            pending = scope.launch {
                try {
                    val id = selected ?: checkNotNull(host.collectiveNew).invoke().id
                    selected = id
                    val result = ask(prompt, id, budget)
                    refresh(result.id)
                    question = ""
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status = "No answer was obtained or history could not be saved."
                } finally { busy = false }
            }
        }
    })
    if (busy) AzphaltPill("Cancel answer", "collective-cancel", onClick = { pending?.cancel() })
    AzphaltPill("New conversation", "collective-new", onClick = {
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    refresh(checkNotNull(host.collectiveNew).invoke().id)
                    question = ""
                    status = ""
                    deleting = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { status = "Could not create conversation." }
                finally { busy = false }
            }
        }
    })
    if (selected != null && !busy && host.collectiveImportHistory != null &&
        conversations.singleOrNull { it.id == selected }?.turns?.isNotEmpty() == true) {
        if (importing) Text("Import the retained questions and agent excerpts into this conversation's memory? " +
            "Previously banked or cleared conversations are refused. The excerpts may be incomplete; original dates are unknown.",
            style = AzphaltType.body, color = azphaltOnSurface())
        AzphaltPill(if (importing) "Confirm import into memory" else "Import retained history into memory",
            "collective-import-history", onClick = {
                if (!importing) importing = true
                else {
                    val id = selected
                    importing = false
                    if (id != null) {
                        busy = true
                        scope.launch {
                            try {
                                val count = checkNotNull(host.collectiveImportHistory).invoke(id)
                                status = count?.let { "Queued $it historical utterances for memory consolidation." }
                                    ?: "Import refused: memory is unavailable/disabled, or this conversation already has a memory bank."
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { status = "Historical memory import failed." }
                            finally { busy = false }
                        }
                    }
                }
            })
        if (importing) AzphaltPill("Cancel import", "collective-import-cancel", onClick = { importing = false })
    }
    if (selected != null && !busy) AzphaltPill(if (deleting) "Confirm delete conversation" else "Delete conversation",
        "collective-delete", onClick = {
            if (!deleting) deleting = true
            else {
                val id = selected
                deleting = false
                if (id != null) {
                    busy = true
                    scope.launch {
                        try {
                            checkNotNull(host.collectiveDelete).invoke(id)
                            refresh()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { status = "Could not delete conversation." }
                        finally { busy = false }
                    }
                }
            }
        })
    if (status.isNotEmpty()) Text(status, style = AzphaltType.body, color = azphaltOnSurface())
    host.evidenceLedger?.invoke()?.let { EvidenceLedgerPart(it, busy) }
}
