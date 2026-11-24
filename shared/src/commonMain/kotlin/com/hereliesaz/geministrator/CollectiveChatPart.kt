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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Local Keeper conversation with explicit history selection and deletion. */
@Composable
internal fun CollectiveChatPart(host: WorkflowLibraryHost) {
    val scope = rememberCoroutineScope()
    var conversations by remember { mutableStateOf<List<CollectiveConversation>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var inspect by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    suspend fun refresh(preferred: String? = selected) {
        conversations = checkNotNull(host.collectiveHistory).invoke()
        selected = preferred?.takeIf { id -> conversations.any { it.id == id } } ?: conversations.firstOrNull()?.id
    }
    LaunchedEffect(Unit) {
        try { refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = "Conversation history unavailable." }
    }
    ProviderSectionLabel("Ask The Aive")
    Text("Advisory conversation only. Specialists are selected by relevance and configured model availability. Completed turns are saved locally; only role memory is consulted.",
        style = AzphaltType.body, color = azphaltOnSurface())
    conversations.forEach { conversation ->
        val title = conversation.turns.firstOrNull()?.question?.take(45) ?: "Empty conversation"
        AzphaltPill((if (conversation.id == selected) "Selected: " else "") + title,
            "collective-history-${conversation.id}", onClick = {
                if (!busy) { selected = conversation.id; deleting = false }
            })
    }
    conversations.singleOrNull { it.id == selected }?.turns.orEmpty().forEach { turn ->
        val result = turn.result
        Text("You: ${turn.question}", style = AzphaltType.body, color = azphaltOnSurface())
        Text(result.answer?.let { "The Aive: $it" } ?: "The Aive: No synthesized answer was obtained.",
            style = AzphaltType.body, color = azphaltOnSurface())
        if (result.unavailable.isNotEmpty()) Text("Unavailable: ${result.unavailable.joinToString()}",
            style = AzphaltType.body, color = azphaltOnSurface())
        if (inspect) result.assessments.forEach { assessment ->
            Text("${assessment.role} [${assessment.sourceId ?: "unknown source"} / ${assessment.modelId ?: "unknown model"}]: ${assessment.text}",
                style = AzphaltType.body, color = azphaltOnSurface())
        }
    }
    AzphaltPill(if (inspect) "Hide specialist assessments" else "Inspect specialist assessments",
        "collective-inspect", onClick = { inspect = !inspect })
    AzphaltTextField(value = question, onValueChange = { question = it.take(1500) }, label = "Ask a question")
    AzphaltPill("Ask The Aive", "collective-ask", onClick = {
        val prompt = question.trim()
        val ask = host.collectiveChat
        if (!busy && prompt.isNotEmpty() && ask != null) {
            busy = true
            status = ""
            pending = scope.launch {
                try {
                    val id = selected ?: checkNotNull(host.collectiveNew).invoke().id
                    selected = id
                    val result = ask(prompt, id)
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
}
