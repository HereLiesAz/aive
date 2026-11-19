package com.hereliesaz.geministrator.chat

import com.hereliesaz.geministrator.control.ControlException
import com.hereliesaz.geministrator.control.ControlHost
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * What a command from the paired keeper does. It holds only a [ControlHost]: the same host the control API drives, with the
 * same validation, refusals and context-taint marking ([ControlHost.startRun] marks the objective as external content, so
 * a step that can change something still waits at a gate). The host has no member that decides a gate, so none can be
 * reached from here. A run started from chat stops at its plan approval, in the app.
 */
class ChatCommandHandler(
    private val host: ControlHost,
    private val clock: () -> Long,
    private val maxChars: Int,
    private val secrets: () -> List<String> = { emptyList() },
) {
    companion object {
        const val STOP_CONFIRM_MILLIS: Long = 60_000L
        private val finished = setOf("Completed", "Failed", "Cancelled")
        const val APPROVE_IN_APP = "Plans and approvals are decided in The Aive, not here."
    }

    private class PendingStop(val runId: String, val expiresAt: Long)

    private var pendingStop: PendingStop? = null

    suspend fun handle(command: ChatCommand): String {
        val reply = try {
        when (command) {
            ChatCommand.Help -> help()
            ChatCommand.Status -> status()
            ChatCommand.Hives -> hives()
            ChatCommand.Runs -> runs()
            is ChatCommand.Run -> startRun(command)
            ChatCommand.Stop -> askStop()
            ChatCommand.StopConfirm -> confirmStop()
            // Pairing is handled before commands reach here; a paired keeper sending it gets the help.
            ChatCommand.PairRequest, is ChatCommand.PairCode -> "This chat is already paired. Send /help."
            is ChatCommand.Unknown -> "I do not know that. Send /help. $APPROVE_IN_APP"
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ControlException) {
        "Refused: " + ChatText.safe(e.message, 300, secrets())
    } catch (e: Exception) {
        "That did not work."
        }
        return ChatText.safe(reply, maxChars, secrets())
    }

    private fun help(): String = listOf(
        "The Aive, from chat:",
        "/status  how things are",
        "/hives  the hives you can run",
        "/runs  recent runs",
        "/run <hive> <objective>  start a run (it still stops at its plan approval in the app)",
        "/stop  then /stop confirm within 60 seconds",
        APPROVE_IN_APP,
    ).joinToString("\n")

    private suspend fun status(): String {
        val s = host.status()
        if (s.bool("runtimeReady") != true) return "The Aive is still starting. Try again shortly."
        return listOf(
            "The Aive ${s.text("version") ?: ""}".trim(),
            "Hives: ${s.num("hives")}   Runs: ${s.num("runs")}   Live: ${s.num("liveRuns")}",
            "Waiting on you: ${s.num("pendingApprovals")}" + if ((s.int("pendingApprovals") ?: 0) > 0) " (open The Aive to approve)" else "",
        ).joinToString("\n")
    }

    private suspend fun hives(): String {
        val list = host.listHives().arrayOrEmpty()
        if (list.isEmpty()) return "No hives yet."
        return (listOf("Hives (use the id in /run):") + list.take(15).map { h ->
            val o = h.asObject()
            "${o?.text("id") ?: "?"}  ${o?.text("name") ?: ""}  (${o?.num("creatures") ?: "?"} creatures)"
        } + if (list.size > 15) listOf("and ${list.size - 15} more") else emptyList()).joinToString("\n")
    }

    private suspend fun runs(): String {
        val list = host.listRuns(null, 6).arrayOrEmpty()
        if (list.isEmpty()) return "No runs yet."
        return (listOf("Recent runs:") + list.map { r ->
            val o = r.asObject()
            "${o?.text("id") ?: "?"}  ${o?.text("status") ?: "?"}  ${o?.num("creaturesDone") ?: 0}/${o?.num("creatures") ?: 0}  ${ChatText.clip(o?.text("objective") ?: "", 60)}"
        }).joinToString("\n")
    }

    private suspend fun resolveHive(wanted: String): String? {
        val hives = host.listHives().arrayOrEmpty().mapNotNull { it.asObject() }
        hives.firstOrNull { it.text("id") == wanted }?.let { return wanted }
        val byName = hives.filter { it.text("name")?.equals(wanted, ignoreCase = true) == true }
        return if (byName.size == 1) byName[0].text("id") else null
    }

    private suspend fun startRun(c: ChatCommand.Run): String {
        val id = resolveHive(c.hive) ?: return "No hive with that id. Send /hives."
        val started = host.startRun(
            buildJsonObject {
                put("hiveId", id)
                put("objective", c.objective)
                put("projectName", "Chat: " + ChatText.clip(c.hive, 40))
            },
        )
        return "Started run ${started.text("id") ?: ""}. It waits at its plan approval: open The Aive to approve."
    }

    private suspend fun liveRun(): JsonObject? =
        host.listRuns(null, 10).arrayOrEmpty().mapNotNull { it.asObject() }.firstOrNull { it.text("status") !in finished }

    private suspend fun askStop(): String {
        val run = liveRun() ?: return "No run is live."
        val id = run.text("id") ?: return "No run is live."
        pendingStop = PendingStop(id, clock() + STOP_CONFIRM_MILLIS)
        return "Stop run $id (${ChatText.clip(run.text("objective") ?: "", 80)})? Send /stop confirm within 60 seconds."
    }

    private suspend fun confirmStop(): String {
        val pending = pendingStop
        pendingStop = null
        if (pending == null || clock() >= pending.expiresAt) return "Nothing to confirm. Send /stop first."
        // The run named in the question must still be the live one; anything else needs a fresh /stop.
        val live = liveRun()?.text("id")
        if (live != pending.runId) return "That run is no longer the live one. Send /stop again."
        host.stopRun(pending.runId)
        return "Stopped run ${pending.runId}."
    }

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement.arrayOrEmpty(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.num(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: "?"
}
