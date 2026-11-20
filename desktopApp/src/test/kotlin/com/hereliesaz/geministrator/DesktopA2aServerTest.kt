package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.a2a.A2aCodec
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** A certificate kept in a temporary folder with its password in memory, so no test touches the keychain. */
    private fun testTls() = DesktopA2aTls(
        directory = java.nio.file.Files.createTempDirectory("aive-a2a-tls").toFile(),
        passwordStore = MemoryTokenStore(),
    )

    private fun server(
        store: DesktopA2aServerTokenStore = MemoryTokenStore(),
        port: Int = 0,
        networkAddresses: List<String> = emptyList(),
        tls: DesktopA2aTls = testTls(),
    ) = DesktopA2aServer(
        store,
        port,
        // Preferences are never touched: reads fall back to defaults and the allowlist stays in memory.
        preferencesNode = { error("tests never use preferences") },
        networkAddresses = { networkAddresses },
        tls = tls,
    ).also { started += it }

    /** A raw HTTP/1.1 GET over loopback with any Host header (HttpURLConnection will not set one). */
    private fun rawGet(port: Int, path: String, host: String, tls: Boolean = false): Pair<Int, String> =
        (if (tls) trustingSocket(port) else Socket(InetAddress.getLoopbackAddress(), port)).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val text = socket.getInputStream().readBytes().decodeToString()
            text.substringAfter(' ').substringBefore(' ').toInt() to text.substringAfter("\r\n\r\n")
        }

    private var seenFingerprint: String? = null

    /** A TLS socket that accepts any certificate and records the fingerprint it was shown. */
    private fun trustingSocket(port: Int): Socket {
        val trustAll = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                seenFingerprint = DesktopA2aTls.fingerprintOf(chain[0].encoded)
            }
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val context = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        return context.socketFactory.createSocket(InetAddress.getLoopbackAddress(), port).also {
            (it as javax.net.ssl.SSLSocket).startHandshake()
        }
    }

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

    /** Sends headers that promise [declared] body bytes, then no body at all; reads the answer (the socket times out at 5 s). */
    private fun rawPostPromisingABody(port: Int, host: String, token: String?, declared: Int): Int =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            val auth = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
            socket.getOutputStream().apply {
                write("POST /a2a HTTP/1.1\r\nHost: $host\r\n${auth}Content-Type: application/json\r\nContent-Length: $declared\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            socket.getInputStream().bufferedReader().readLine().substringAfter(' ').substringBefore(' ').toInt()
        }

    @Test
    fun aCallerThatWouldBeRefusedIsAnsweredBeforeItsBodyIsReadAndABodyOverTheCapIsRefusedUnread() {
        val a2a = server()
        assertIs<A2aServerStatus.Listening>(a2a.startForStatus())
        val port = a2a.boundPort
        // No token: refused at once, though it promised 900 KB it never sends (a read would block until the socket timed out).
        assertEquals(401, rawPostPromisingABody(port, "127.0.0.1:$port", token = null, declared = 1_500_000))
        // A foreign Host header: refused before any body.
        assertEquals(403, rawPostPromisingABody(port, "evil.example", token = a2a.currentToken(), declared = 1_500_000))
        // Authorised but declaring more than the cap: 413 without reading it.
        assertEquals(413, rawPostPromisingABody(port, "127.0.0.1:$port", token = a2a.currentToken(), declared = 1_500_000))
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

    @Test
    fun openToTheNetworkBindsEveryInterfaceAndAdvertisesTheNetworkAddress() {
        val a2a = server(networkAddresses = listOf("10.1.2.3"))
        assertIs<A2aServerStatus.Listening>(a2a.apply(enabled = true, lan = true, order = 1))
        assertTrue(a2a.openToNetwork)
        assertTrue(assertNotNull(a2a.listeningAddress).address.isAnyLocalAddress)
        val port = a2a.boundPort
        assertEquals(listOf("https://10.1.2.3:$port" + A2aCodec.WELL_KNOWN_CARD_PATH), a2a.lanAgentCardUrls())

        val (status, body) = rawGet(port, A2aCodec.WELL_KNOWN_CARD_PATH, "10.1.2.3:$port", tls = true)
        assertEquals(200, status)
        assertTrue("https://10.1.2.3:$port/a2a" in body, body)
        // The certificate the other computer is shown is the one the keeper is told to pin.
        assertEquals(a2a.tlsFingerprint, seenFingerprint)
        // This computer still reaches it on loopback (over TLS too), and is told the loopback address.
        assertTrue("https://127.0.0.1:$port/a2a" in rawGet(port, A2aCodec.WELL_KNOWN_CARD_PATH, "127.0.0.1:$port", tls = true).second)
        assertEquals(403, rawGet(port, A2aCodec.WELL_KNOWN_CARD_PATH, "10.9.9.9:$port", tls = true).first)
    }

    @Test
    fun openToTheNetworkRefusesPlainHttp() {
        val a2a = server(networkAddresses = listOf("10.1.2.3"))
        a2a.apply(enabled = true, lan = true, order = 1)
        // A plain request to the TLS port never gets an answer a client could read as HTTP 200.
        val plain = runCatching { rawGet(a2a.boundPort, A2aCodec.WELL_KNOWN_CARD_PATH, "127.0.0.1:${a2a.boundPort}") }
        assertTrue(plain.isFailure || plain.getOrThrow().first != 200, plain.toString())
    }

    @Test
    fun noCertificateMeansNoNetworkListener() {
        val broken = DesktopA2aTls(
            directory = java.nio.file.Files.createTempDirectory("aive-a2a-tls").toFile(),
            passwordStore = MemoryTokenStore(),
            keytool = java.io.File("/nonexistent/keytool"),
        )
        val a2a = server(tls = broken, networkAddresses = listOf("10.1.2.3"))
        assertIs<A2aServerStatus.Failed>(a2a.apply(enabled = true, lan = true, order = 1))
        assertNull(a2a.listeningAddress)
        assertFalse(a2a.openToNetwork)
    }

    @Test
    fun theCertificateIsKeptAcrossStartsWhileTheKeychainHoldsItsPassword() {
        val directory = java.nio.file.Files.createTempDirectory("aive-a2a-tls").toFile()
        val passwords = MemoryTokenStore()
        val first = DesktopA2aTls(directory, passwords).load(listOf("10.1.2.3")).fingerprint
        val second = DesktopA2aTls(directory, passwords).load(listOf("10.1.2.3")).fingerprint
        assertEquals(first, second)
    }

    @Test
    fun closingTheNetworkRebindsToLoopbackAndStopsAdvertisingIt() {
        val a2a = server(networkAddresses = listOf("10.1.2.3"))
        a2a.apply(enabled = true, lan = true, order = 1)
        assertIs<A2aServerStatus.Listening>(a2a.apply(enabled = true, lan = false, order = 2))
        assertFalse(a2a.openToNetwork)
        assertTrue(assertNotNull(a2a.listeningAddress).address.isLoopbackAddress)
        assertTrue(a2a.lanAgentCardUrls().isEmpty())
        assertEquals(403, rawGet(a2a.boundPort, A2aCodec.WELL_KNOWN_CARD_PATH, "10.1.2.3:${a2a.boundPort}").first)
    }

    @Test
    fun anOlderSwitchChangeCannotUndoANewerOne() {
        val a2a = server()
        assertIs<A2aServerStatus.Listening>(a2a.apply(enabled = true, lan = false, order = 10))
        assertIs<A2aServerStatus.Listening>(a2a.apply(enabled = false, lan = false, order = 5))
        assertNotNull(a2a.listeningAddress)
        assertEquals(A2aServerStatus.Off, a2a.apply(enabled = false, lan = false, order = 11))
        assertNull(a2a.listeningAddress)
    }

    @Test
    fun onlyAddressesCanBeAllowed() {
        val a2a = server()
        assertTrue(a2a.allowClient(" 10.0.0.9 "))
        assertFalse(a2a.allowClient("laptop.local"))
        assertEquals(setOf("10.0.0.9"), a2a.allowedClients())
        a2a.forgetClient("10.0.0.9")
        assertTrue(a2a.allowedClients().isEmpty())
    }
}
