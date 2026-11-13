package com.hereliesaz.geministrator

import java.io.File
import java.util.prefs.Preferences

/** What a read of a stored secret found. [Unreadable] means the store could not answer (locked, timed out). */
internal sealed interface StoredSecret {
    data class Found(val value: String) : StoredSecret
    data object Absent : StoredSecret
    data object Unreadable : StoredSecret
}

/**
 * One distributed-compute secret in the OS credential store. The default holds the relay (pool)
 * token; [forNodeIdentity] holds this device's compute node signing key.
 */
internal class DesktopDistributedComputeCredentialStore(
    private val account: String = ACCOUNT,
    private val windowsFileName: String = WINDOWS_TOKEN_FILE_NAME,
    /** Only the pool token may also come from the environment. */
    private val readEnvironment: Boolean = true,
    /** Whether the keeper has allowed the plaintext preferences file when no OS store works. Off by default. */
    private val allowPlaintextFallback: () -> Boolean = { plaintextFallbackAllowed() },
) {
    private val fallback = Preferences.userRoot().node(PREFERENCES_NODE)

    /** The secret held only for this session when it could not be persisted. */
    @Volatile
    private var sessionSecret: String? = null

    /** One plain line for the keeper when the last write could not be persisted, else null. */
    @Volatile
    var notice: String? = null
        private set

    fun readToken(): String? = (readSecret() as? StoredSecret.Found)?.value

    /** Like [readToken], but tells an unreadable store apart from an empty one. */
    fun readSecret(): StoredSecret {
        (if (readEnvironment) environmentToken() else null)?.let { return StoredSecret.Found(it) }
        sessionSecret?.let { return StoredSecret.Found(it) }
        when (val stored = readFromKeychainState(account, windowsTokenFile(windowsFileName))) {
            is StoredSecret.Found, StoredSecret.Unreadable -> return stored
            StoredSecret.Absent -> Unit
        }
        return fallback.get(account, null)?.trim()?.takeIf(String::isNotEmpty)
            ?.let { StoredSecret.Found(it) } ?: StoredSecret.Absent
    }

    fun writeToken(token: String) {
        val clean = token.trim()
        require(clean.isNotEmpty()) { "Relay token is required" }
        val file = windowsTokenFile(windowsFileName)
        // Only drop the fallback copy once the OS store demonstrably returns the token.
        if (writeToKeychain(clean, account, file) && readFromKeychain(account, file) == clean) {
            fallback.remove(account)
            fallback.flush()
            sessionSecret = null
            notice = null
        } else if (allowPlaintextFallback()) {
            fallback.put(account, clean)
            fallback.flush()
            notice = null
        } else {
            sessionSecret = clean
            notice = "No system store is available, so this secret is kept for this session only."
        }
    }

    fun clear() {
        sessionSecret = null
        notice = null
        deleteFromKeychain(account, windowsTokenFile(windowsFileName))
        fallback.remove(account)
        fallback.flush()
    }

    internal companion object {
        const val PREFERENCES_NODE = "com/hereliesaz/haive/distributed-compute"
        const val KEYCHAIN_SERVICE = "com.hereliesaz.haive.distributed-compute"
        const val ACCOUNT = "relay-token"
        const val WINDOWS_TOKEN_FILE_NAME = "distributed-compute-relay-token.dpapi"

        fun forNodeIdentity() = DesktopDistributedComputeCredentialStore(
            account = "node-signing-key",
            windowsFileName = "distributed-compute-node-key.dpapi",
            readEnvironment = false,
        )

        /** The keeper's explicit opt-in (environment variable) to the plaintext preferences fallback. */
        fun plaintextFallbackAllowed(): Boolean =
            System.getenv("AIVE_ALLOW_PLAINTEXT_SECRETS")?.trim() == "1"

        /**
         * Reads the OS store, telling "nothing stored" from "could not be read". Only a clean
         * not-found answer is [StoredSecret.Absent]; a timeout or any other failure is [StoredSecret.Unreadable].
         */
        fun readFromKeychainState(account: String = ACCOUNT, tokenFile: File = windowsTokenFile()): StoredSecret = runCatching {
            val builder: ProcessBuilder = when {
                os.contains("mac") -> ProcessBuilder(
                    listOf("security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-a", account, "-w"),
                )
                os.contains("win") -> {
                    if (!tokenFile.isFile) return@runCatching StoredSecret.Absent
                    windowsPowerShell(WINDOWS_READ_SCRIPT, tokenFile)
                }
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> ProcessBuilder(
                    listOf("secret-tool", "lookup", "service", KEYCHAIN_SERVICE, "account", account),
                )
                else -> return@runCatching StoredSecret.Absent
            }
            val execution = CredentialTool.execute(builder)
            val result = execution.result
            when {
                execution.timedOut -> StoredSecret.Unreadable
                // The tool could not start: there is no store to read.
                result == null -> StoredSecret.Absent
                result.ok -> result.output.trim().takeIf(String::isNotEmpty)
                    ?.let { StoredSecret.Found(it) } ?: StoredSecret.Absent
                notFoundExitCode(result.exitCode) -> StoredSecret.Absent
                else -> StoredSecret.Unreadable
            }
        }.getOrElse { StoredSecret.Unreadable }

        private fun notFoundExitCode(code: Int): Boolean = when {
            os.contains("mac") -> code == 44
            os.contains("win") -> false
            else -> code == 1
        }

        fun environmentToken(): String? =
            System.getenv("AIVE_RELAY_TOKEN")?.trim()?.takeIf(String::isNotEmpty)
                ?: System.getenv("HAIVE_RELAY_TOKEN")?.trim()?.takeIf(String::isNotEmpty)

        /** Environment variable through which the Windows scripts receive the token file path. */
        const val WINDOWS_TOKEN_FILE_ENV = "AIVE_RELAY_TOKEN_FILE"

        /**
         * Windows: the token is encrypted with DPAPI for the current user
         * (`ConvertFrom-SecureString` without a key) into a per-user file. The scripts are
         * constants; the token travels only through stdin and stdout, the path through
         * [WINDOWS_TOKEN_FILE_ENV], so neither appears in a command line.
         */
        const val WINDOWS_WRITE_SCRIPT =
            "\$ErrorActionPreference = 'Stop'; " +
                "\$token = [Console]::In.ReadToEnd().Trim(); " +
                "\$secure = ConvertTo-SecureString -String \$token -AsPlainText -Force; " +
                "\$path = \$env:$WINDOWS_TOKEN_FILE_ENV; " +
                "New-Item -ItemType Directory -Force -Path (Split-Path -Parent \$path) | Out-Null; " +
                "ConvertFrom-SecureString -SecureString \$secure | Set-Content -LiteralPath \$path -Encoding ASCII"
        const val WINDOWS_READ_SCRIPT =
            "\$ErrorActionPreference = 'Stop'; " +
                "\$sealed = (Get-Content -LiteralPath \$env:$WINDOWS_TOKEN_FILE_ENV -Raw).Trim(); " +
                "\$secure = ConvertTo-SecureString -String \$sealed; " +
                "[Console]::Out.Write([System.Net.NetworkCredential]::new('', \$secure).Password)"

        fun windowsTokenFile(fileName: String = WINDOWS_TOKEN_FILE_NAME): File {
            val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
                ?: File(System.getProperty("user.home"), "AppData/Local").path
            return File(File(base, "Aive"), fileName)
        }

        fun windowsPowerShell(script: String, tokenFile: File): ProcessBuilder =
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).apply {
                environment()[WINDOWS_TOKEN_FILE_ENV] = tokenFile.absolutePath
            }

        val os: String get() = System.getProperty("os.name", "").lowercase()

        fun readFromKeychain(account: String = ACCOUNT, tokenFile: File = windowsTokenFile()): String? = runCatching {
            val result = when {
                os.contains("mac") -> CredentialTool.run(
                    listOf(
                        "security", "find-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", account,
                        "-w",
                    ),
                )
                os.contains("win") -> {
                    if (!tokenFile.isFile) return@runCatching null
                    CredentialTool.run(windowsPowerShell(WINDOWS_READ_SCRIPT, tokenFile))
                }
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> CredentialTool.run(
                    listOf(
                        "secret-tool", "lookup",
                        "service", KEYCHAIN_SERVICE,
                        "account", account,
                    ),
                )
                else -> null
            }
            result?.takeIf { it.ok }?.output?.trim()?.takeIf(String::isNotEmpty)
        }.getOrNull()

        fun writeToKeychain(token: String, account: String = ACCOUNT, tokenFile: File = windowsTokenFile()): Boolean = runCatching {
            when {
                os.contains("mac") -> {
                    CredentialTool.run(
                        listOf(
                            "security", "delete-generic-password",
                            "-s", KEYCHAIN_SERVICE,
                            "-a", account,
                        ),
                    )
                    MacKeychain.addGenericPassword(KEYCHAIN_SERVICE, account, token)
                }
                os.contains("win") ->
                    CredentialTool.run(windowsPowerShell(WINDOWS_WRITE_SCRIPT, tokenFile), stdin = token)?.ok == true
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> CredentialTool.run(
                    listOf(
                        "secret-tool", "store",
                        "--label", "Aive distributed compute relay",
                        "service", KEYCHAIN_SERVICE,
                        "account", account,
                    ),
                    stdin = token,
                )?.ok == true
                else -> false
            }
        }.getOrElse { false }

        fun deleteFromKeychain(account: String = ACCOUNT, tokenFile: File = windowsTokenFile()) = runCatching {
            when {
                os.contains("mac") -> CredentialTool.run(
                    listOf(
                        "security", "delete-generic-password",
                        "-s", KEYCHAIN_SERVICE,
                        "-a", account,
                    ),
                )
                os.contains("win") -> {
                    tokenFile.delete()
                    // Earlier builds wrote a Credential Manager entry that could never be read back.
                    CredentialTool.run(CredentialTool.windowsCredentialDeleteCommand(KEYCHAIN_SERVICE, account))
                }
                os.contains("nux") || os.contains("nix") || os.contains("bsd") -> CredentialTool.run(
                    listOf(
                        "secret-tool", "clear",
                        "service", KEYCHAIN_SERVICE,
                        "account", account,
                    ),
                )
                else -> null
            }
        }
    }
}
