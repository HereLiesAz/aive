package com.hereliesaz.geministrator.mcp

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class McpStdioEnvironmentTest {
    @Test
    fun aLocalServerGetsTheAllowlistedVariablesNotEverythingTheAppHad() {
        val dir = Files.createTempDirectory("aive-mcp-vars-").toFile()
        try {
            val inherited = mapOf("PATH" to System.getenv("PATH").orEmpty(), "HOME" to dir.absolutePath, "OPENAI_API_KEY" to "leak-one", "GITHUB_TOKEN" to "leak-two")
            val transport = McpStdioTransport.start(listOf("sh", "-c", "printenv > vars.txt"), dir, inherited)
            val out = File(dir, "vars.txt")
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && !(out.isFile && out.length() > 0)) Thread.sleep(20)
            runBlocking { transport.close() }
            val text = out.readText()
            assertTrue("HOME=" in text, text)
            assertFalse("leak-one" in text || "leak-two" in text, "no key reached the server: $text")
        } finally {
            dir.deleteRecursively()
        }
    }
}
