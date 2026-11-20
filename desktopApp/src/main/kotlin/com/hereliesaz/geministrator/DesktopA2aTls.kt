package com.hereliesaz.geministrator

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The certificate the A2A server presents while it is open to the network (docs/A2A.md).
 *
 * The JDK cannot make a certificate through a public API, so the one `keytool` of this same runtime
 * makes is used: a self-signed P-256 key, kept in a PKCS12 file owned by the user, its password in the
 * OS keychain (the same store as the bearer token). Nothing but the keeper vouches for a self-signed
 * certificate, so the SHA-256 [Material.fingerprint] is shown in the Keeper's room for the other
 * computer to pin. Where the keychain cannot keep the password a new certificate is made on the next
 * start, and its fingerprint changes. If a certificate cannot be had, the server does not start open to
 * the network: it never falls back to plain HTTP.
 */
internal class DesktopA2aTls(
    private val directory: File = defaultDirectory(),
    private val passwordStore: DesktopA2aServerTokenStore = DesktopA2aServerTokenStore(account = "a2a-tls-keystore"),
    private val keytool: File = defaultKeytool(),
) {
    class Material(val context: SSLContext, val fingerprint: String)

    private val keystoreFile get() = File(directory, "a2a-tls.p12")

    /** The server's TLS material, made once and reused; [addresses] name this computer in the certificate. */
    @Synchronized
    fun load(addresses: Collection<String>): Material {
        val stored = passwordStore.read()
        if (stored != null && keystoreFile.isFile) {
            runCatching { return open(stored) }
        }
        return create(addresses)
    }

    private fun create(addresses: Collection<String>): Material {
        check(keytool.isFile) { "This runtime has no keytool, so it cannot make a certificate" }
        directory.mkdirs()
        keystoreFile.delete()
        val password = DesktopA2aServer.newToken()
        val san = (listOf("dns:localhost", "ip:127.0.0.1") + addresses.map { "ip:$it" }).joinToString(",")
        val process = ProcessBuilder(
            keytool.absolutePath, "-genkeypair", "-alias", ALIAS,
            "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
            "-validity", "3650", "-dname", "CN=aive-a2a", "-ext", "san=$san",
            "-keystore", keystoreFile.absolutePath, "-storetype", "PKCS12",
            "-storepass:env", PASSWORD_ENV, "-keypass:env", PASSWORD_ENV,
        ).redirectErrorStream(true).apply { environment()[PASSWORD_ENV] = password }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "keytool failed: ${output.take(200)}" }
        // Owner only where the file system has permissions.
        runCatching { Files.setPosixFilePermissions(keystoreFile.toPath(), PosixFilePermissions.fromString("rw-------")) }
        passwordStore.write(password)
        return open(password)
    }

    private fun open(password: String): Material {
        val chars = password.toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply { keystoreFile.inputStream().use { load(it, chars) } }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, chars) }
        val context = SSLContext.getInstance("TLSv1.3").apply { init(factory.keyManagers, null, null) }
        val certificate = keys.getCertificate(ALIAS) as X509Certificate
        return Material(context, fingerprintOf(certificate.encoded))
    }

    companion object {
        private const val ALIAS = "aive-a2a"
        private const val PASSWORD_ENV = "AIVE_A2A_TLS_PASSWORD"

        /** SHA-256 of a certificate's DER bytes, upper-case hex pairs joined by colons (what `openssl x509 -fingerprint` prints). */
        fun fingerprintOf(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

        private fun defaultDirectory(): File {
            val os = System.getProperty("os.name", "").lowercase()
            return when {
                os.contains("win") -> DesktopDistributedComputeCredentialStore.windowsTokenFile().parentFile
                os.contains("mac") -> File(System.getProperty("user.home"), "Library/Application Support/Aive")
                else -> File(System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank) ?: "${System.getProperty("user.home")}/.local/share", "aive")
            }
        }

        private fun defaultKeytool(): File {
            val exe = if (System.getProperty("os.name", "").lowercase().contains("win")) "keytool.exe" else "keytool"
            return File(File(System.getProperty("java.home"), "bin"), exe)
        }
    }
}
