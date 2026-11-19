package com.hereliesaz.geministrator.cli

import com.hereliesaz.geministrator.chat.ChatBotManager
import com.hereliesaz.geministrator.chat.ChatPlatform
import com.hereliesaz.geministrator.chat.ChatSecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The bots' keys for the headless engine come from the environment only; nothing here writes one anywhere. */
internal class EnvironmentChatSecretStore(private val env: Map<String, String>) : ChatSecretStore {
    override val canWrite: Boolean = false

    override fun read(platform: ChatPlatform): String? =
        env[variable(platform)]?.trim()?.takeIf(String::isNotEmpty)

    override fun write(platform: ChatPlatform, secret: String): Boolean = false

    override fun clear(platform: ChatPlatform) = Unit

    companion object {
        fun variable(platform: ChatPlatform): String = when (platform) {
            ChatPlatform.Telegram -> "AIVE_TELEGRAM_BOT_TOKEN"
            ChatPlatform.Discord -> "AIVE_DISCORD_BOT_TOKEN"
        }

        fun switchVariable(platform: ChatPlatform): String = when (platform) {
            ChatPlatform.Telegram -> "AIVE_CHAT_TELEGRAM"
            ChatPlatform.Discord -> "AIVE_CHAT_DISCORD"
        }
    }
}

/**
 * Starts the Telegram and Discord bots inside `aive serve` when the environment switches them on
 * (`AIVE_CHAT_TELEGRAM=on`, `AIVE_CHAT_DISCORD=on`; docs/Chat-Bots.md). Off by default. A pairing code is printed on the
 * daemon's own output, the headless counterpart of the code the desktop app shows in the Keeper's room.
 */
internal class HeadlessChatBots private constructor(
    private val manager: ChatBotManager,
    private val scope: CoroutineScope,
) : AutoCloseable {
    override fun close() {
        manager.stop()
        scope.cancel()
    }

    companion object {
        fun isOn(value: String?): Boolean = value?.trim()?.lowercase() in setOf("on", "1", "true", "yes")

        fun startIfEnabled(
            engine: HeadlessEngine,
            env: Map<String, String> = System.getenv(),
            say: (String) -> Unit = ::println,
        ): HeadlessChatBots? {
            val on = ChatPlatform.entries.associateWith { isOn(env[EnvironmentChatSecretStore.switchVariable(it)]) }
            if (on.values.none { it }) return null
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager = ChatBotManager.create(
                controlHost = engine.controlHost,
                runtime = { engine.runtime },
                secrets = EnvironmentChatSecretStore(env),
                scope = scope,
                // The environment decides here, never a switch stored by the desktop app.
                enabledOverride = { platform -> on[platform] == true },
                onCode = { platform, code -> say("${platform.displayName} pairing code: $code (valid 5 minutes; type it into the chat)") },
            )
            engine.chatPoster.set(manager)
            manager.start()
            say("Chat bots on: " + on.filter { it.value }.keys.joinToString { it.displayName })
            return HeadlessChatBots(manager, scope)
        }
    }
}
