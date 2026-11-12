package com.hereliesaz.geministrator.cli

import com.hereliesaz.geministrator.mcpbridge.ControlReply
import com.hereliesaz.geministrator.mcpbridge.ControlTransport
import com.hereliesaz.geministrator.mcpbridge.ControlUnreachable
import com.hereliesaz.geministrator.mcpbridge.Tools
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingControl : ControlTransport {
    val calls = mutableListOf<Triple<String, String, String?>>()
    var reply = ControlReply(200, """{"ok":true,"result":{"id":"run-42"}}""")
    var unreachable = false
    override fun send(method: String, path: String, body: String?): ControlReply {
        if (unreachable) throw ControlUnreachable("The Aive is not reachable")
        calls += Triple(method, path, body)
        return reply
    }
}

class AiveCliTest {
    private val backend = RecordingControl()
    private val output = StringBuilder()
    private val errors = StringBuilder()
    private val files = mutableMapOf<String, String>()
    private val cli = AiveCli(backend) { name -> files[name] ?: error("No such test file") }
    private fun run(vararg args: String): Int = cli.run(args.toList(), output, errors)

    @Test fun everyControlToolHasAUniqueTerminalCommand() {
        assertEquals(21, Commands.all.size)
        assertEquals(21, Commands.all.map { it.words }.distinct().size)
        assertEquals(Tools.all.map { it.name }.toSet(), Commands.all.map { it.toolName }.toSet())
    }

    @Test fun offlineHelpUsesProductNameAndMakesNoRequest() {
        assertEquals(0, run("--help"))
        assertTrue(output.contains("The Aive CLI"))
        assertTrue(output.contains("runs start"))
        assertTrue(output.contains("All approvals remain read-only"))
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun statusIsUnwrappedAndJsonIsMachineReadable() {
        backend.reply = ControlReply(200, """{"ok":true,"result":{"runtimeReady":true}}""")
        assertEquals(0, run("status", "--json"))
        assertEquals("{\"runtimeReady\":true}\n", output.toString())
        assertEquals(listOf(Triple<String, String, String?>("GET", "/status", null)), backend.calls)
    }

    @Test fun startRunSendsRealObjectiveWithoutForgingApproval() {
        assertEquals(0, run("runs", "start", "--hive-id", "docs", "--objective", "Write the docs", "--project-name", "manual"))
        val (method, path, body) = backend.calls.single()
        assertEquals("POST", method)
        assertEquals("/runs", path)
        assertEquals(
            Json.parseToJsonElement("""{"hiveId":"docs","objective":"Write the docs","projectName":"manual"}"""),
            Json.parseToJsonElement(body!!),
        )
    }

    @Test fun createHiveReadsNestedJsonObject() {
        files["spec.json"] = """{"name":"Docs","creatures":[{"id":"writer","name":"Writer","roleId":"researcher","dependsOn":[]}]}"""
        assertEquals(0, run("hives", "create", "--file", "spec.json"))
        val (method, path, body) = backend.calls.single()
        assertEquals("POST", method)
        assertEquals("/hives", path)
        assertEquals(Json.parseToJsonElement(files.getValue("spec.json")), Json.parseToJsonElement(body!!))
    }

    @Test fun readsEscapePathSegmentsAndEncodeQueryParameters() {
        assertEquals(0, run("hives", "get", "my hive/α"))
        assertEquals("/hives/my%20hive%2F%CE%B1", backend.calls.last().second)
        assertEquals(0, run("runs", "events", "r1", "--after", "42", "--limit", "5"))
        assertEquals("/runs/r1/events?after=42&limit=5", backend.calls.last().second)
    }

    @Test fun destructiveCommandsRequireExplicitConsent() {
        assertEquals(2, run("hives", "delete", "docs"))
        assertTrue(errors.contains("--yes"))
        assertTrue(backend.calls.isEmpty())
        assertEquals(0, run("hives", "delete", "docs", "--yes"))
        assertEquals(Triple<String, String, String?>("DELETE", "/hives/docs", null), backend.calls.single())
    }

    @Test fun roleProviderCanBeClearedWithJsonNull() {
        assertEquals(0, run("roles", "provider", "researcher", "--provider-id", "null"))
        assertEquals(Triple("PUT", "/roles/researcher/provider", """{"providerId":null}"""), backend.calls.single())
    }

    @Test fun approvalsAreReadOnlyAndNoOtherApprovalCommandExists() {
        assertEquals(0, run("approvals"))
        assertEquals(Triple<String, String, String?>("GET", "/approvals", null), backend.calls.single())
        assertEquals(2, run("approvals", "grant", "task"))
        assertEquals(1, backend.calls.size)
    }

    @Test fun invalidDataNeverReachesApp() {
        assertEquals(2, run("runs", "list", "--limit", "banana"))
        files["bad.json"] = """{"name":"Bad","creatures":"not an array"}"""
        assertEquals(2, run("hives", "create", "--file", "bad.json"))
        assertEquals(2, run("runs", "start", "--hive-id", "my-hive"))
        assertEquals(2, run("runs", "list", "--unexpected", "yes"))
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun httpRejectionAndUnreachableUseDifferentExitCodes() {
        backend.reply = ControlReply(409, """{"ok":false,"error":{"code":"conflict","message":"A run is already active"}}""")
        assertEquals(1, run("runs", "start", "--hive-id", "docs", "--objective", "Test"))
        assertTrue(errors.contains("conflict: A run is already active"))
        backend.unreachable = true
        assertEquals(3, run("status"))
        assertTrue(errors.contains("not reachable"))
    }
}
