package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.memory.ChatArchiveDocument
import com.hereliesaz.geministrator.memory.MemoryLayerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Explicit local import of provider history, separate from replacing an exported memory graph. */
@Composable
internal fun ChatArchiveImportSection(controller: MemoryLayerController, files: ProjectFileService?) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<List<ChatArchiveDocument>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    AzphaltPill(if (open) "Close chat history import" else "Import chat history", "memory-chat-import",
        onClick = { if (!busy) open = !open })
    if (open) {
        Text("Add external provider chat history to collective memory. JSON, JSON Lines, Markdown and plain text; ZIP on supported file pickers. Your selected history becomes ordinary collective memory and is condensed by the same memory clerks.", style = AzphaltType.body, color = azphaltOnSurface())
        if (files != null) AzphaltPill("Choose archive", "memory-chat-choose", onClick = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        files.chooseChatArchive()?.let { selected = it; text = "" }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { status = "Could not read archive: ${failure.message.orEmpty()}" }
                    finally { busy = false }
                }
            }
        })
        if (selected.isNotEmpty()) {
            Text("${selected.size} selected history file(s).", style = AzphaltType.body, color = azphaltOnSurface())
            AzphaltPill("Clear selection", "memory-chat-clear", onClick = { if (!busy) selected = emptyList() })
        }
        AzphaltWellField(value = text, onValueChange = { if (!busy) { text = it; selected = emptyList() } },
            label = { Text("Or paste exported JSON or transcript") }, modifier = Modifier.fillMaxWidth().height(140.dp))
        AzphaltPill(if (busy) "Working…" else "Confirm import into collective memory", "memory-chat-confirm", onClick = {
            if (!busy && (selected.isNotEmpty() || text.isNotBlank())) {
                busy = true
                val documents = selected.ifEmpty { listOf(ChatArchiveDocument("pasted-history", text)) }
                scope.launch {
                    try {
                        val result = controller.importChatArchives(documents)
                        status = "Queued ${result.added} messages; ${result.alreadyRetained} already retained."
                        selected = emptyList()
                        text = ""
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { status = "Import stopped: ${failure.message.orEmpty()}. Retry the same archive to continue." }
                    finally { busy = false }
                }
            }
        })
    }
    if (status.isNotBlank()) Text(status, style = AzphaltType.body, color = azphaltOnSurface())
}
