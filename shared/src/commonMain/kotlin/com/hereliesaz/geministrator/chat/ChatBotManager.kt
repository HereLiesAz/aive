package com.hereliesaz.geministrator.chat

import androidx.compose.runtime.compositionLocalOf
import com.hereliesaz.geministrator.ApplicationRuntime
import com.hereliesaz.geministrator.QueenLevelStore
import com.hereliesaz.geministrator.control.ControlHost
import com.hereliesaz.geministrator.currentEpochMillis
import com.hereliesaz.geministrator.queen.QueenLevel
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Provided by the desktop app (and not by a phone, which cannot hold the connection): the Keeper's room shows Chat bots only when it is set. */
val LocalChatBotHost = compositionLocalOf<ChatBotHost?> { null }

/** The non-secret choices kept on this device: each platform's switch and who the keeper is. */
internal class ChatBotSettings(private val settings: Settings) {
    private fun key(p: ChatPlatform, name: String) = "aive.chat.${p.id}.$name"

    fun enabled(p: ChatPlatform): Boolean = settings.getBoolean(key(p, "enabled"), false)

    fun setEnabled(p: ChatPlatform, on: Boolean) {
        if (on) settings.putBoolean(key(p, "enabled"), true) else settings.remove(key(p, "enabled"))
    }

    fun paired(p: ChatPlatform): ChatIdentity? {
        val user = settings.getStringOrNull(key(p, "user"))
        val chat = settings.getStringOrNull(key(p, "chat"))
        return if (user.isNullOrBlank() || chat.isNullOrBlank()) null else ChatIdentity(user, chat)
    }

    fun setPaired(p: ChatPlatform, who: ChatIdentity?) {
        if (who == null) {
            settings.remove(key(p, "user"))
            settings.remove(key(p, "chat"))
        } else {
            settings.putString(key(p, "user"), who.userId)
            settings.putString(key(p, "chat"), who.chatId)
        }
    }
}

/**
 * Runs the chat bots that are switched on (docs/Chat-Bots.md) and is what the Keeper's room talks to. Off by default; each
 * platform has its own switch and needs its own key. Used by the desktop app and by `aive serve`, never on Android.
 */
class ChatBotManager internal constructor(
    private val controlHost: ControlHost,
    private val attention: ChatAttentionSource,
    private val secretStore: ChatSecretStore,
    private val settings: ChatBotSettings,
    private val level: () -> QueenLevel,
    private val scope: CoroutineScope,
    private val transports: (ChatPlatform, () -> String?) -> ChatTransport,
    private val clock: () -> Long,
    /** Where a switch is set from outside (the headless engine's environment); null leaves it to the stored switch. */
    private val enabledOverride: (ChatPlatform) -> Boolean?,
    /** Told the code when a pairing starts, for a host with no screen (the headless engine prints it). */
    private val onCode: (ChatPlatform, String) -> Unit,
) : ChatBotHost {
    companion object {
        /** The manager over the app's own runtime. [secrets] holds the bot keys; [scope] owns the connections. */
        fun create(
            controlHost: ControlHost,
            runtime: () -> ApplicationRuntime?,
            secrets: ChatSecretStore,
            scope: CoroutineScope,
            enabledOverride: (ChatPlatform) -> Boolean? = { null },
            onCode: (ChatPlatform, String) -> Unit = { _, _ -> },
        ): ChatBotManager {
            // Created only when a platform is switched on, so nothing opens a connection while the bots are off.
            val rest = lazy {
                HttpClient {
                    followRedirects = false
                    install(HttpTimeout) {
                        requestTimeoutMillis = 50_000L
                        connectTimeoutMillis = 10_000L
                    }
                }
            }
            val socket = lazy {
                HttpClient {
                    followRedirects = false
                    install(WebSockets)
                }
            }
            return ChatBotManager(
                controlHost = controlHost,
                attention = RuntimeAttentionSource(runtime),
                secretStore = secrets,
                settings = ChatBotSettings(Settings()),
                level = { QueenLevelStore(Settings()).level },
                scope = scope,
                transports = { platform, key ->
                    when (platform) {
                        ChatPlatform.Telegram -> TelegramTransport(rest.value, key)
                        ChatPlatform.Discord -> DiscordTransport(socket.value, key)
                    }
                },
                clock = ::currentEpochMillis,
                enabledOverride = enabledOverride,
                onCode = onCode,
            )
        }
    }

    private val tokens = MutableStateFlow<Map<ChatPlatform, String>>(emptyMap())
    private val running = MutableStateFlow<Map<ChatPlatform, ChatBotService>>(emptyMap())
    private val flows = ChatPlatform.entries.associateWith { MutableStateFlow(ChatBotStatus()) }
    private val announced = HashMap<ChatPlatform, String>()
    private var started = false

    override val canStoreTokens: Boolean get() = secretStore.canWrite

    override fun status(platform: ChatPlatform): StateFlow<ChatBotStatus> = flows.getValue(platform)

    private fun isEnabled(p: ChatPlatform): Boolean = enabledOverride(p) ?: settings.enabled(p)

    /** Reads the stored keys once and starts what is switched on. Safe to call again. */
    fun start() {
        if (started) return
        started = true
        tokens.value = ChatPlatform.entries.mapNotNull { p -> secretStore.read(p)?.takeIf { it.isNotBlank() }?.let { p to it } }.toMap()
        ChatPlatform.entries.forEach { reconcile(it) }
        scope.launch {
            while (isActive) {
                ChatPlatform.entries.forEach { publish(it) }
                delay(1_000L)
            }
        }
    }

    fun stop() {
        running.value.values.forEach { it.stop() }
        running.value = emptyMap()
        ChatPlatform.entries.forEach { publish(it) }
    }

    private fun reconcile(p: ChatPlatform) {
        val want = isEnabled(p) && tokens.value[p] != null
        val current = running.value[p]
        if (want && current == null) {
            val service = ChatBotService(
                platform = p,
                transport = transports(p) { tokens.value[p] },
                pairing = ChatPairing(clock, initiallyPaired = settings.paired(p)),
                handler = ChatCommandHandler(controlHost, clock, p.safeChars) { listOfNotNull(tokens.value[p]) },
                notifier = ChatNotifier(level, clock),
                attention = attention,
                clock = clock,
                secrets = { listOfNotNull(tokens.value[p]) },
                onPairedChanged = { who -> settings.setPaired(p, who) },
            )
            running.value = running.value + (p to service)
            service.start(scope)
        } else if (!want && current != null) {
            current.stop()
            running.value = running.value - p
        }
        publish(p)
    }

    private fun publish(p: ChatPlatform) {
        val service = running.value[p]
        val pending = service?.pairing?.pending()
        if (pending != null && announced[p] != pending.code) {
            announced[p] = pending.code
            onCode(p, pending.code)
        } else if (pending == null) {
            announced.remove(p)
        }
        flows.getValue(p).value = ChatBotStatus(
            enabled = isEnabled(p),
            hasToken = tokens.value[p] != null,
            link = service?.link?.value ?: ChatLink.Off,
            paired = (service?.pairing?.paired ?: settings.paired(p)) != null,
            pairingCode = pending?.code,
            pairingExpiresAtEpochMillis = pending?.expiresAtEpochMillis,
            ignoredStrangers = service?.ignored ?: 0,
            rateLimited = service?.rateLimited ?: 0,
        )
    }

    override fun setToken(platform: ChatPlatform, token: String): Boolean {
        val clean = token.trim()
        if (clean.length !in 20..300 || clean.any { it.isWhitespace() || it.code < 33 }) return false
        if (!secretStore.write(platform, clean)) return false
        tokens.value = tokens.value + (platform to clean)
        // A new key may be a different bot: whoever was paired to the old one is not the keeper of this one.
        unpair(platform)
        reconcile(platform)
        return true
    }

    override fun clearToken(platform: ChatPlatform) {
        secretStore.clear(platform)
        tokens.value = tokens.value - platform
        unpair(platform)
        reconcile(platform)
    }

    override fun setEnabled(platform: ChatPlatform, enabled: Boolean) {
        settings.setEnabled(platform, enabled)
        reconcile(platform)
    }

    override fun unpair(platform: ChatPlatform) {
        val service = running.value[platform]
        if (service != null) service.unpair() else settings.setPaired(platform, null)
        publish(platform)
    }

    /**
     * Sends [text] to the keeper's paired chat on each platform that is on and paired, and to no other chat. Returns how many
     * platforms took it. The text is scrubbed, link-free and bounded again on the way out.
     */
    suspend fun postToKeeper(text: String): Int = running.value.values.count { it.postToKeeper(text) }
}
