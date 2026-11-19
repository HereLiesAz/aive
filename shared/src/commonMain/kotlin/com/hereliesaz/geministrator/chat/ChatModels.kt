package com.hereliesaz.geministrator.chat

import kotlinx.coroutines.flow.StateFlow

/*
 * Chat bots (docs/Chat-Bots.md): the keeper reaches The Aive from Telegram or Discord direct messages.
 * Nothing in this package can approve, reject or release a gate (THREAT_MODEL, Chat bots, invariant 8):
 * commands reach the app only through [com.hereliesaz.geministrator.control.ControlHost], which has no such member.
 */

/** The two chat platforms. [maxChars] is the platform's message limit; [safeChars] leaves room under it. */
enum class ChatPlatform(val id: String, val displayName: String, val maxChars: Int) {
    Telegram("telegram", "Telegram", 4096),
    Discord("discord", "Discord", 2000),
    ;

    val safeChars: Int get() = maxChars - 96
}

/** Who wrote a message and where to answer. Both ids are compared exactly; a stranger matches neither. */
data class ChatIdentity(val userId: String, val chatId: String)

/** One message from a platform, already reduced to what the service needs. [text] is untrusted. */
data class ChatInbound(
    val platform: ChatPlatform,
    val identity: ChatIdentity,
    /** True only for a one-to-one chat with the bot; groups, channels and servers are never direct. */
    val isDirect: Boolean,
    val fromBot: Boolean,
    val text: String,
)

/** How the connection to the platform is. */
enum class ChatLink { Off, Connecting, Connected, Failed, TokenRefused }

/** A platform connection. [run] returns only by cancellation or by throwing; the service restarts it with a pause. */
interface ChatTransport {
    val platform: ChatPlatform
    val link: StateFlow<ChatLink>
    suspend fun run(onInbound: suspend (ChatInbound) -> Unit)

    /** Sends [text] (plain, already bounded) to [chatId]; true when the platform accepted it. */
    suspend fun send(chatId: String, text: String): Boolean
}

/** Where a platform's bot key is kept. It is never returned to the screen, logged or printed. */
interface ChatSecretStore {
    fun read(platform: ChatPlatform): String?
    fun write(platform: ChatPlatform, secret: String): Boolean
    fun clear(platform: ChatPlatform)

    /** False where the key can only come from outside (the headless engine reads the environment). */
    val canWrite: Boolean get() = true
}

/** What the Keeper's room shows for one platform. Nothing here is secret. */
data class ChatBotStatus(
    val enabled: Boolean = false,
    val hasToken: Boolean = false,
    val link: ChatLink = ChatLink.Off,
    val paired: Boolean = false,
    /** The one-time code to type in the chat, and when it stops being valid; null when none is pending. */
    val pairingCode: String? = null,
    val pairingExpiresAtEpochMillis: Long? = null,
    val ignoredStrangers: Long = 0,
    val rateLimited: Long = 0,
)

/** The Keeper's room's view of the bots. Null where the app cannot hold the connection (a phone). */
interface ChatBotHost {
    val platforms: List<ChatPlatform> get() = ChatPlatform.entries
    fun status(platform: ChatPlatform): StateFlow<ChatBotStatus>
    fun setToken(platform: ChatPlatform, token: String): Boolean
    fun clearToken(platform: ChatPlatform)
    fun setEnabled(platform: ChatPlatform, enabled: Boolean)
    fun unpair(platform: ChatPlatform)

    /** False where keys come from the environment, so the screen does not offer to type one. */
    val canStoreTokens: Boolean get() = true
}
