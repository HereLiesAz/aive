package com.hereliesaz.geministrator

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.hereliesaz.geministrator.queen.CollectiveAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Local Keeper conversation; closing this panel discards its bounded in-memory transcript. */
@Composable
internal fun CollectiveChatPart(host: WorkflowLibraryHost) {
    val scope = rememberCoroutineScope()
    var conversation by remember { mutableStateOf("collective:${currentEpochMillis()}:${Random.nextLong()}") }
    var question by remember { mutableStateOf("") }
    var turns by remember { mutableStateOf<List<Pair<String, CollectiveAnswer>>>(emptyList()) }
    var pending by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var inspect by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    ProviderSectionLabel("Ask The Aive")
    Text("One advisory voice, informed by independent assessments. No workflow starts. Only role memory is consulted; these turns are kept in this panel.",
        style = AzphaltType.body, color = azphaltOnSurface())
    turns.forEach { (prompt, result) ->
        Text("You: $prompt", style = AzphaltType.body, color = azphaltOnSurface())
        Text(result.answer?.let { "The Aive: $it" } ?: "The Aive: No synthesized answer was obtained.",
            style = AzphaltType.body, color = azphaltOnSurface())
        if (result.unavailable.isNotEmpty()) Text("Unavailable: ${result.unavailable.joinToString()}",
            style = AzphaltType.body, color = azphaltOnSurface())
        if (inspect) result.assessments.forEach { assessment ->
            Text("${assessment.role}: ${assessment.text}", style = AzphaltType.body, color = azphaltOnSurface())
        }
    }
    AzphaltPill(if (inspect) "Hide specialist assessments" else "Inspect specialist assessments",
        "collective-inspect", onClick = { inspect = !inspect })
    AzphaltTextField(value = question, onValueChange = { question = it.take(1500) }, label = "Ask a question")
    AzphaltPill("Ask The Aive", "collective-ask", onClick = {
        val prompt = question.trim()
        val ask = host.collectiveChat
        if (!busy && prompt.isNotEmpty() && ask != null) {
            val history = turns.joinToString("\n") { (q, a) -> "Keeper: $q\nAive: ${a.answer.orEmpty()}" }.takeLast(1800)
            busy = true
            status = ""
            pending = scope.launch {
                try {
                    val result = ask(prompt, history, conversation)
                    turns = (turns + (prompt to result)).takeLast(8)
                    question = ""
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status = "No answer was obtained."
                } finally {
                    busy = false
                }
            }
        }
    })
    if (busy) AzphaltPill("Cancel answer", "collective-cancel", onClick = { pending?.cancel() })
    AzphaltPill("New conversation", "collective-new", onClick = {
        if (!busy) {
            conversation = "collective:${currentEpochMillis()}:${Random.nextLong()}"
            turns = emptyList()
            question = ""
            status = ""
        }
    })
    if (status.isNotEmpty()) Text(status, style = AzphaltType.body, color = azphaltOnSurface())
}
