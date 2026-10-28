package com.hereliesaz.geministrator

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class EntireAgentPluginTest {
    private val sessions = AiveEntireSessions(Files.createTempDirectory("aive-entire-plugin").toFile())

    private fun call(vararg args: String, stdin: String = ""): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = runEntireAgentPlugin(args.toList(), ByteArrayInputStream(stdin.toByteArray()), out, ByteArrayOutputStream(), sessions)
        return code to out.toString()
    }

    private fun json(vararg args: String, stdin: String = ""): JsonObject =
        call(*args, stdin = stdin).also { assertEquals(0, it.first) }.second.let { Json.parseToJsonElement(it).jsonObject }

    private fun session(): String {
        sessions.begin("s-1", "/repo", "Claude")
        sessions.prompt("s-1", "Make it two")
        sessions.response("s-1", "diff")
        sessions.files("s-1", listOf("a.txt"), listOf("b.txt"), emptyList())
        sessions.usage("s-1", 10, 5)
        return sessions.file("s-1").absolutePath
    }

    @Test
    fun infoDeclaresProtocolOneWithoutHooks() {
        val info = json("info")
        assertEquals(1, info["protocol_version"]!!.jsonPrimitive.int)
        assertEquals("aive", info["name"]!!.jsonPrimitive.content)
        assertEquals("false", info["capabilities"]!!.jsonObject["hooks"]!!.jsonPrimitive.content)
    }

    @Test
    fun readsTheSessionItKept() {
        val path = session()
        assertEquals(path, json("resolve-session-file", "--session-dir", sessions.directory.path, "--session-id", "s-1")["session_file"]!!.jsonPrimitive.content)
        val read = json("read-session", stdin = """{"session_id":"s-1","session_ref":"$path"}""")
        assertEquals(listOf("a.txt"), read["modified_files"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("b.txt"), read["new_files"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Make it two"), json("extract-prompts", "--session-ref", path, "--offset", "0")["prompts"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("a.txt", "b.txt"), json("extract-modified-files", "--path", path, "--offset", "0")["files"]!!.jsonArray.map { it.jsonPrimitive.content })
        val tokens = json("calculate-tokens", "--offset", "0", stdin = java.io.File(path).readText())
        assertEquals(10, tokens["input_tokens"]!!.jsonPrimitive.long)
        assertEquals(5, tokens["output_tokens"]!!.jsonPrimitive.long)
    }

    @Test
    fun refusesTranscriptsOutsideItsFolder() {
        session()
        val (code, _) = call("read-transcript", "--session-ref", "/etc/passwd")
        assertEquals(1, code)
    }

    @Test
    fun chunksReassembleToTheTranscript() {
        val text = java.io.File(session()).readText()
        val chunks = json("chunk-transcript", "--max-size", "64", stdin = text)["chunks"]!!.jsonArray
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { Base64.getDecoder().decode(it.jsonPrimitive.content).size <= 64 })
        val (_, back) = call("reassemble-transcript", stdin = """{"chunks":$chunks}""")
        assertEquals(text, back)
    }

    @Test
    fun sessionIdsCannotLeaveTheFolder() {
        assertEquals("-etc-passwd", AiveEntireSessions.safeSessionId("../etc/passwd"))
    }
}
