package com.hereliesaz.geministrator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliLocatorTest {
    private val shim = File("C:\\Users\\k\\AppData\\Roaming\\npm\\claude.cmd")

    @Test
    fun aWindowsCommandScriptIsNeverGivenAnArgumentCmdExeWouldObey() {
        listOf("a&calc", "a|b", "x>out", "<in", "50%", "^x", "\"quoted\"", "!x", "(x)", "a=b", "a,b", "a;b", "line\nbreak").forEach { bad ->
            assertFailsWith<IllegalArgumentException>(bad) { CliLocator.commandFor(shim, listOf("-p", bad)) }
        }
        val safe = listOf("-p", "--output-format", "stream-json", "-C", "C:\\Users\\k\\AppData\\Local\\Temp\\aive-cli-123\\tree")
        assertEquals(listOf(shim.absolutePath) + safe, CliLocator.commandFor(shim, safe))
        assertTrue(CliLocator.isWindowsShim(File("tool.BAT")))
    }

    @Test
    fun aShimInAFolderWithParenthesesIsFineButOneWithAnAmpersandIsNot() {
        assertEquals(2, CliLocator.commandFor(File("C:\\Program Files (x86)\\nodejs\\claude.cmd"), listOf("-p")).size)
        assertFailsWith<IllegalArgumentException> { CliLocator.commandFor(File("C:\\A&B\\claude.cmd"), listOf("-p")) }
    }

    @Test
    fun aRealProgramTakesItsArgumentsAsTheyAre() {
        val program = File("/usr/bin/claude")
        val odd = listOf("-p", "a&b|c%d", "\"x\"")
        assertEquals(listOf(program.absolutePath) + odd, CliLocator.commandFor(program, odd))
        assertFalse(CliLocator.isWindowsShim(program))
        assertEquals(listOf(File("C:\\x\\claude.exe").absolutePath, "a&b"), CliLocator.commandFor(File("C:\\x\\claude.exe"), listOf("a&b")))
    }
}
