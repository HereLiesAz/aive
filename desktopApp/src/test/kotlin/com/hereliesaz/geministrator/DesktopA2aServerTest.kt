package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.a2a.A2aCodec
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Binds only to loopback on a free port, so it needs no network and cannot clash with a running Aive. */
class DesktopA2aServerTest {
    private class MemoryTokenStore : DesktopA2aServerTokenStore() {
        var stored: String? = null
        var writes = 0

        override fun read(): String? = stored

        override fun write(token: String): Boolean {
            writes++
            stored = token
            return true
        }
    }

    private val started = mutableListOf<DesktopA2aServer>()

    private fun server(store: DesktopA2aServerTokenStore = MemoryTokenStore(), port: Int = 0) =
        DesktopA2aServer(store, port, preferencesNode = { error("tests never read the switch") }).also { started += it }

    @AfterTest
    fun stopAll() = started.forEach { it.stop() }

    private fun request(url: String, method: String = "GET", token: String? = null): Int {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (method == "POST") {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"jsonrpc":"2.0","id":1,"method":"GetTask","params":{"id":"none"}}""".toByteArray()) }
        }
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun startsOnLoopbackOnlyAndServesTheCardAndRefusesCallsWithoutTheToken() {
        val a2a = server()
        val status = a2a.startForStatus()
        assertIs<A2aServerStatus.Listening>(status)
        assertTrue(status.address.startsWith("http://127.0.0.1:"))
        val address = assertNotNull(a2a.listeningAddress)
        assertTrue(address.address.isLoopbackAddress, "bound to $address")

        assertEquals(200, request(a2a.baseUrl + A2aCodec.WELL_KNOWN_CARD_PATH))
        assertEquals(401, request(a2a.baseUrl + "/a2a", method = "POST"))
        assertEquals(401, request(a2a.baseUrl + "/a2a", method = "POST", token = "wrong"))
        assertEquals(200, request(a2a.baseUrl + "/a2a", method = "POST", token = a2a.currentToken()))

        a2a.stop()
        assertNull(a2a.listeningAddress)
    }

    @Test
    fun theFirstStartCreatesTheTokenAndLaterStartsKeepIt() {
        val store = MemoryTokenStore()
        val first = server(store)
        assertIs<A2aServerStatus.Listening>(first.startForStatus())
        val token = assertNotNull(first.currentToken())
        assertEquals(token, store.stored)
        assertEquals(false, first.tokenIsTemporary)
        first.stop()

        val second = server(store)
        assertIs<A2aServerStatus.Listening>(second.startForStatus())
        assertEquals(token, second.currentToken())
        assertEquals(1, store.writes)
    }

    @Test
    fun aPortInUseIsReportedAndNothingThrows() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { taken ->
            val a2a = server(port = taken.localPort)
            assertEquals(A2aServerStatus.PortInUse(taken.localPort), a2a.startForStatus())
            assertNull(a2a.listeningAddress)
        }
    }

    @Test
    fun aTokenTheKeychainCannotKeepIsMarkedTemporary() {
        val store = object : DesktopA2aServerTokenStore() {
            override fun read(): String? = null
            override fun write(token: String): Boolean = false
        }
        val a2a = server(store)
        assertIs<A2aServerStatus.Listening>(a2a.startForStatus())
        assertTrue(a2a.tokenIsTemporary)
    }
}
