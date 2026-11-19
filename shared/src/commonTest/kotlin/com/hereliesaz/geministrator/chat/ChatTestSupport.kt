package com.hereliesaz.geministrator.chat

import com.hereliesaz.geministrator.control.ControlErrorCode
import com.hereliesaz.geministrator.control.ControlException
import com.hereliesaz.geministrator.control.ControlHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A host that answers a fixed picture of the app and records every member called. */
class RecordingHost(
    var liveRunId: String? = "run-1",
    var startRefusal: String? = null,
) : ControlHost {
    val calls = mutableListOf<String>()
    var lastStart: JsonObject? = null

    override suspend fun status(): JsonObject = buildJsonObject {
        calls += "status"
        put("version", "1.2.3")
        put("runtimeReady", true)
        put("hives", 2)
        put("runs", 3)
        put("liveRuns", if (liveRunId != null) 1 else 0)
        put("pendingApprovals", 1)
    }

    override suspend fun listHives(): JsonElement {
        calls += "listHives"
        return buildJsonArray {
            add(buildJsonObject { put("id", "hive-a"); put("name", "Docs"); put("creatures", 3) })
            add(buildJsonObject { put("id", "hive-b"); put("name", "Release"); put("creatures", 5) })
        }
    }

    override suspend fun listRuns(hiveId: String?, limit: Int): JsonElement {
        calls += "listRuns"
        return buildJsonArray {
            liveRunId?.let { id ->
                add(buildJsonObject {
                    put("id", id); put("status", "Running"); put("objective", "write the docs")
                    put("creatures", 4); put("creaturesDone", 1)
                })
            }
            add(buildJsonObject {
                put("id", "run-0"); put("status", "Completed"); put("objective", "old")
                put("creatures", 2); put("creaturesDone", 2)
            })
        }
    }

    override suspend fun startRun(spec: JsonObject): JsonObject {
        calls += "startRun"
        startRefusal?.let { throw ControlException(ControlErrorCode.Conflict, it) }
        lastStart = spec
        return buildJsonObject { put("id", "run-new") }
    }

    override suspend fun stopRun(runId: String): JsonObject {
        calls += "stopRun:$runId"
        liveRunId = null
        return buildJsonObject { put("stopped", runId) }
    }

    override suspend fun getHive(id: String): JsonObject = unused("getHive")
    override suspend fun listRoles(): JsonElement = unused("listRoles")
    override suspend fun getRole(id: String): JsonObject = unused("getRole")
    override suspend fun getRun(id: String): JsonObject = unused("getRun")
    override suspend fun listEvents(runId: String, afterMillis: Long?, limit: Int): JsonElement = unused("listEvents")
    override suspend fun listApprovals(): JsonElement = unused("listApprovals")
    override suspend fun listProviders(): JsonObject = unused("listProviders")
    override suspend fun settings(): JsonObject = unused("settings")
    override suspend fun createHive(spec: JsonObject): JsonObject = unused("createHive")
    override suspend fun updateHive(id: String, patch: JsonObject): JsonObject = unused("updateHive")
    override suspend fun deleteHive(id: String): JsonObject = unused("deleteHive")
    override suspend fun addCreature(hiveId: String, spec: JsonObject): JsonObject = unused("addCreature")
    override suspend fun updateCreature(hiveId: String, creatureId: String, patch: JsonObject): JsonObject = unused("updateCreature")
    override suspend fun removeCreature(hiveId: String, creatureId: String): JsonObject = unused("removeCreature")
    override suspend fun draftHive(prompt: String): JsonObject = unused("draftHive")
    override suspend fun setRoleProvider(roleId: String, providerId: String?): JsonObject = unused("setRoleProvider")

    private fun unused(name: String): Nothing {
        calls += name
        throw ControlException(ControlErrorCode.PolicyDenied, "not offered to chat")
    }
}

/** A transport that never touches a network: tests push messages in and read what was sent. */
class FakeTransport(override val platform: ChatPlatform = ChatPlatform.Telegram) : ChatTransport {
    private val state = MutableStateFlow(ChatLink.Connected)
    override val link: StateFlow<ChatLink> = state
    val sent = mutableListOf<Pair<String, String>>()
    var accept = true

    override suspend fun run(onInbound: suspend (ChatInbound) -> Unit) = Unit

    override suspend fun send(chatId: String, text: String): Boolean {
        sent += chatId to text
        return accept
    }
}

internal class TestClock(var now: Long = 1_000_000L) {
    fun read(): Long = now
}

internal fun dm(
    user: String,
    text: String,
    chat: String = user,
    platform: ChatPlatform = ChatPlatform.Telegram,
    direct: Boolean = true,
    bot: Boolean = false,
) = ChatInbound(platform, ChatIdentity(user, chat), direct, bot, text)
