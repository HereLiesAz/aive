package com.hereliesaz.geministrator.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One platform's bot, switched on: the transport, the pairing, the allowlist, the limits and the notifications
 * (docs/Chat-Bots.md). It is the only place a message from chat is acted on, and it acts in this order:
 *  1. anything that is not a one-to-one message from a person is dropped, silently and counted;
 *  2. once paired, anyone but the keeper's own chat is dropped, silently and counted (no reply, so nothing leaks);
 *  3. the keeper's messages are rate limited, parsed strictly, and handled through [ChatCommandHandler];
 *  4. before pairing, only `/pair` and its code are answered, and only to the chat that asked.
 */
internal class ChatBotService(
    val platform: ChatPlatform,
    private val transport: ChatTransport,
    val pairing: ChatPairing,
    private val handler: ChatCommandHandler,
    private val notifier: ChatNotifier,
    private val attention: ChatAttentionSource,
    private val clock: () -> Long,
    private val secrets: () -> List<String>,
    /** Called when the keeper's identity changes: stored, or cleared by unpairing. */
    private val onPairedChanged: (ChatIdentity?) -> Unit,
    private val pollMillis: Long = 15_000L,
) {
    private val keeperLimit = ChatRateLimiter(limit = 20, windowMillis = 60_000L, clock = clock)
    private val strangerLimit = ChatRateLimiter(limit = 6, windowMillis = 60_000L, clock = clock)
    private val sendLock = Mutex()
    private var supervisor: Job? = null
    private var notifications: Job? = null

    var ignored: Long = 0
        private set

    var rateLimited: Long = 0
        private set

    val link get() = transport.link

    fun start(scope: CoroutineScope) {
        if (supervisor != null) return
        supervisor = scope.launch {
            while (isActive) {
                try {
                    transport.run { message -> onInbound(message) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The transport reports its own state; here it is only started again after a pause.
                }
                delay(5_000L)
            }
        }
        notifications = scope.launch {
            while (isActive) {
                delay(pollMillis)
                try {
                    notifyOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The next look tries again.
                }
            }
        }
    }

    fun stop() {
        supervisor?.cancel()
        notifications?.cancel()
        supervisor = null
        notifications = null
    }

    private suspend fun reply(chatId: String, text: String) {
        val clean = ChatText.safe(text, platform.safeChars, secrets())
        sendLock.withLock { transport.send(chatId, clean) }
    }

    /** Looks at the runs and tells the paired chat what is new, paced. Visible for tests. */
    internal suspend fun notifyOnce() {
        val keeper = pairing.paired ?: return
        val runs = attention.snapshot() ?: return
        val message = notifier.poll(runs) ?: notifier.flush() ?: return
        reply(keeper.chatId, message)
    }

    /** Sends [text] to the keeper's paired chat and to no other. False when nobody is paired or the send failed. */
    suspend fun postToKeeper(text: String): Boolean {
        val keeper = pairing.paired ?: return false
        val clean = ChatText.safe(text, platform.safeChars, secrets())
        if (clean.isEmpty()) return false
        return sendLock.withLock { transport.send(keeper.chatId, clean) }
    }

    /** Handles one message from the platform. Visible for tests (a fake transport calls it). */
    internal suspend fun onInbound(message: ChatInbound) {
        if (message.platform != platform || !message.isDirect || message.fromBot) {
            ignored += 1
            return
        }
        val keeper = pairing.paired
        if (keeper != null) {
            if (message.identity != keeper) {
                if (strangerLimit.allow(message.identity.userId)) ignored += 1 else rateLimited += 1
                return
            }
            if (!keeperLimit.allow("keeper")) {
                rateLimited += 1
                return
            }
            reply(keeper.chatId, handler.handle(ChatCommandParser.parse(message.text)))
            return
        }
        if (!strangerLimit.allow(message.identity.userId)) {
            rateLimited += 1
            return
        }
        when (val command = ChatCommandParser.parse(message.text)) {
            ChatCommand.PairRequest -> when (pairing.request(message.identity)) {
                PairRequestOutcome.Started ->
                    reply(message.identity.chatId, "Open The Aive, Keeper's room, Chat bots, and type the six digits shown there into this chat within 5 minutes.")
                PairRequestOutcome.NoRandomSource ->
                    reply(message.identity.chatId, "This computer cannot make a safe code, so pairing is off.")
                PairRequestOutcome.Busy, PairRequestOutcome.AlreadyPaired -> ignored += 1
            }
            is ChatCommand.PairCode -> when (val outcome = pairing.redeem(message.identity, command.code)) {
                is PairRedeemOutcome.Paired -> {
                    onPairedChanged(outcome.identity)
                    reply(
                        outcome.identity.chatId,
                        "Paired. I will tell you what needs you, and answer /status, /hives, /runs, /run and /stop. " +
                            ChatCommandHandler.APPROVE_IN_APP,
                    )
                }
                PairRedeemOutcome.Wrong -> reply(message.identity.chatId, "That code is not right.")
                PairRedeemOutcome.Expired -> reply(message.identity.chatId, "That code ran out. Send /pair again.")
                PairRedeemOutcome.NoChallenge -> ignored += 1
            }
            // Before pairing, nothing else is answered: the bot does not say what it can do.
            else -> ignored += 1
        }
    }

    /** The keeper unpaired in the app. */
    fun unpair() {
        pairing.unpair()
        onPairedChanged(null)
    }
}
