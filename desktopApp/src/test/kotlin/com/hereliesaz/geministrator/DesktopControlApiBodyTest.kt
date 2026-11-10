package com.hereliesaz.geministrator

import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The control API judges who is asking from the headers, before it reads any body. Loopback only, free port. */
class DesktopControlApiBodyTest {
    private val dir = Files.createTempDirectory("aive-control-body-").toFile()
    private val api = DesktopControlApi(dir = dir, port = 0, preferencesNode = { error("tests never use preferences") })

    @AfterTest
    fun cleanUp() {
        api.stop()
        dir.deleteRecursively()
    }

    private fun postPromisingABody(host: String, token: String?, declared: Int): Int =
        Socket(InetAddress.getLoopbackAddress(), api.boundPort).use { socket ->
            socket.soTimeout = 5_000
            val auth = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
            socket.getOutputStream().apply {
                write("POST /control/v1/hives HTTP/1.1\r\nHost: $host\r\n${auth}Content-Type: application/json\r\nContent-Length: $declared\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            socket.getInputStream().bufferedReader().readLine().substringAfter(' ').substringBefore(' ').toInt()
        }

    @Test
    fun aRefusedCallerIsAnsweredAtOnceAndAnOversizeBodyIsRefusedUnread() {
        api.start()
        val token = api.tokenFile.readText().trim()
        // Promising 900 KB and sending none: a body read first would block until the socket timed out.
        assertEquals(401, postPromisingABody("127.0.0.1:${api.boundPort}", token = null, declared = 900_000))
        assertEquals(403, postPromisingABody("evil.example", token = token, declared = 900_000))
        assertEquals(413, postPromisingABody("127.0.0.1:${api.boundPort}", token = token, declared = 900_000))
    }
}
