package com.hereliesaz.geministrator

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.hereliesaz.geministrator.queen.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Keeper inspection and explicit assessment of evidence across conversation issues. */
@Composable
internal fun EvidenceLedgerPart(ledger: CollectiveEvidenceLedger, parentBusy: Boolean) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var book by remember { mutableStateOf(EvidenceBook()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var related by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(EvidenceKind.Observation) }
    var question by remember { mutableStateOf("") }
    var statement by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    fun act(action: suspend () -> Unit) {
        if (busy || parentBusy) return
        busy = true
        scope.launch {
            try { action(); book = ledger.snapshot(); status = "" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "Evidence action could not be saved. Check the entries and source." }
            finally { busy = false }
        }
    }
    AzphaltPill(if (open) "Hide shared evidence" else "Shared evidence ledger", "collective-evidence", onClick = {
        if (!busy && !parentBusy) {
            open = !open
            if (open) act {}
        }
    })
    if (!open) return
    Text("Original statements and Keeper assessments remain visible. Agreement does not establish a fact. Linking a typed result or research excerpt to a conversation opinion, or recording a Keeper observation or source claim against its issue, shares bounded evidence with its next participants.",
        style = AzphaltType.body, color = azphaltOnSurface())
    AzphaltPill("Refresh evidence", "evidence-refresh", onClick = { act {} })
    val target = book.entries.singleOrNull { it.id == selected }
    val counterpart = book.entries.singleOrNull { it.id == related }
    val candidates = remember(book, selected) { selected?.let(book::reconsiderationCandidates).orEmpty() }
    if (candidates.isNotEmpty()) {
        Text("Possible connections, ranked by topic only. Linking exposes bounded evidence to this issue's participants on the next conversation turn; it asserts neither support nor contradiction.",
            style = AzphaltType.body, color = azphaltOnSurface())
        candidates.forEach { entry ->
            Text("${entry.kind.name}: ${entry.question}\n${entry.text.take(400)}",
                style = AzphaltType.body, color = azphaltOnSurface())
            AzphaltPill("Link for reconsideration", "evidence-reconsider-${entry.id}", onClick = {
                if (target != null) act {
                    ledger.review(target.id, entry.id, EvidenceRelation.Relevant,
                        "Keeper associated evidence for reconsideration.")
                }
            })
        }
    }
    var filter by remember { mutableStateOf("") }
    AzphaltTextField(value = filter, onValueChange = { filter = it }, label = "Find evidence")
    LaunchedEffect(parentBusy, open) {
        if (open && !parentBusy && !busy) act {}
    }
    val matching = book.entries.asReversed().filter { e ->
        filter.isBlank() || listOf(e.question, e.text, e.source, e.id).any { it.contains(filter, ignoreCase = true) }
    }
    var page by remember(filter) { mutableStateOf(0) }
    Text("${matching.size} matching entries · page ${page + 1}", style = AzphaltType.body, color = azphaltOnSurface())
    matching.drop(page * 20).take(20).forEach { entry ->
        Text("${entry.kind.name}: ${entry.question}\n${entry.text}\nSource: ${entry.source} · ${if (entry.recordedByKeeper) "Keeper" else entry.roleId.orEmpty()} ${entry.modelId.orEmpty()} · instance ${entry.specialistId ?: "not recorded"}",
            style = AzphaltType.body, color = azphaltOnSurface())
        entry.localTest?.let { receipt ->
            Text("Observed local process: exit ${receipt.exitCode}, started ${receipt.started}, timed out ${receipt.timedOut}.\n" +
                "Initial detached checkout: ${receipt.checkoutCommit ?: "not pinned"}. " +
                "This does not certify the tested work or acceptance criteria.",
                style = AzphaltType.body, color = azphaltOnSurface())
        }
        entry.researchCitation?.let { citation ->
            Text("Research citation ${citation.sourceNumber}: ${citation.sourceType} / ${citation.sourceName}\n" +
                "Source excerpt: ${citation.sourceExcerpt}\n" +
                if (citation.matchesSourceText) "Exact source-text match; truth not verified."
                else "Claim does not match the complete source excerpt; unverified hypothesis.",
                style = AzphaltType.body, color = azphaltOnSurface())
        }
        AzphaltPill(if (selected == entry.id) "Selected" else "Select entry", "evidence-select-${entry.id}", onClick = {
            if (!busy) { selected = entry.id; related = null; confirmForget = false }
        })
        if (target != null && target.id != entry.id) AzphaltPill(if (related == entry.id) "Related entry" else "Use as related entry",
            "evidence-related-${entry.id}", onClick = { if (!busy) related = entry.id })
        book.reviews.filter { it.targetId == entry.id }.forEach { review ->
            val ref = book.entries.singleOrNull { it.id == review.relatedId }
            Text("${review.relation.name}: ${review.reason}" + (ref?.let { "\nRelated: ${it.text}" } ?: ""),
                style = AzphaltType.body, color = azphaltOnSurface())
        }
    }
    if (page > 0) AzphaltPill("Previous", "evidence-prev", onClick = { page-- })
    if ((page + 1) * 20 < matching.size) AzphaltPill("Next", "evidence-next", onClick = { page++ })
    if (target != null) {
        Text("Selected: ${target.text}\nRelated: ${counterpart?.text ?: "None"}", style = AzphaltType.body, color = azphaltOnSurface())
        AzphaltTextField(value = reason, onValueChange = { reason = it }, label = "Assessment reason")
        EvidenceRelation.entries.forEach { relation ->
            AzphaltPill(relation.name, "evidence-review-${relation.name}", onClick = {
                if (reason.isNotBlank()) act {
                    ledger.review(target.id, if (relation == EvidenceRelation.RetractedByKeeper) null else counterpart?.id, relation, reason)
                    reason = ""
                }
            })
        }
        AzphaltPill(if (confirmForget) "Confirm erase evidence" else "Erase evidence", "evidence-erase", onClick = {
            if (!confirmForget) confirmForget = true
            else act { ledger.forget(target.id); selected = null; related = null; confirmForget = false }
        })
    }
    ProviderSectionLabel("Record evidence")
    EvidenceKind.entries.forEach { value ->
        AzphaltPill((if (kind == value) "Selected: " else "") + value.name, "evidence-kind-${value.name}",
            onClick = { if (!busy) kind = value })
    }
    AzphaltTextField(value = question, onValueChange = { question = it }, label = "Question or issue")
    AzphaltTextField(value = statement, onValueChange = { statement = it }, label = "Statement")
    AzphaltTextField(value = source, onValueChange = { source = it }, label = "Source or reference")
    AzphaltPill("Record", "evidence-record", onClick = {
        if (statement.isNotBlank() && source.isNotBlank() && (question.isNotBlank() || target != null)) act {
            ledger.recordKeeper(target?.issueId, target?.question ?: question, kind, statement, source)
            statement = ""; source = ""
        }
    })
    if (status.isNotBlank()) Text(status, style = AzphaltType.body, color = azphaltOnSurface())
}
