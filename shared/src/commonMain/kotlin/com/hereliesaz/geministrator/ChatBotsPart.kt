@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.chat.ChatBotHost
import com.hereliesaz.geministrator.chat.ChatBotStatus
import com.hereliesaz.geministrator.chat.ChatLink
import com.hereliesaz.geministrator.chat.ChatPlatform
import com.hereliesaz.geministrator.chat.LocalChatBotHost
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.chat_bots_code
import com.hereliesaz.geministrator.resources.chat_bots_code_note
import com.hereliesaz.geministrator.resources.chat_bots_counts
import com.hereliesaz.geministrator.resources.chat_bots_env
import com.hereliesaz.geministrator.resources.chat_bots_link_connected
import com.hereliesaz.geministrator.resources.chat_bots_link_connecting
import com.hereliesaz.geministrator.resources.chat_bots_link_failed
import com.hereliesaz.geministrator.resources.chat_bots_link_off
import com.hereliesaz.geministrator.resources.chat_bots_link_refused
import com.hereliesaz.geministrator.resources.chat_bots_note
import com.hereliesaz.geministrator.resources.chat_bots_off
import com.hereliesaz.geministrator.resources.chat_bots_on
import com.hereliesaz.geministrator.resources.chat_bots_pair_how
import com.hereliesaz.geministrator.resources.chat_bots_paired
import com.hereliesaz.geministrator.resources.chat_bots_token_clear
import com.hereliesaz.geministrator.resources.chat_bots_token_label
import com.hereliesaz.geministrator.resources.chat_bots_token_none
import com.hereliesaz.geministrator.resources.chat_bots_token_rejected
import com.hereliesaz.geministrator.resources.chat_bots_token_save
import com.hereliesaz.geministrator.resources.chat_bots_token_saved
import com.hereliesaz.geministrator.resources.chat_bots_unpair
import org.jetbrains.compose.resources.stringResource

/**
 * Chat bots (docs/Chat-Bots.md): a Telegram bot and a Discord bot through which the keeper's own paired account sees status,
 * is told what needs them, and can start or stop a run. Each is **off** until turned on and needs its own token, which is
 * typed here once, kept where the app keeps its other keys and never shown again. Pairing: send /pair to the bot, then type
 * the six digits shown here into that chat. Nothing in chat can approve anything. Only shown where the app can run it.
 */
@Composable
internal fun ChatBotsPart() {
    val host = LocalChatBotHost.current ?: return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.chat_bots_note), style = AzphaltType.body, color = azphaltOnSurface())
        if (!host.canStoreTokens) {
            Text(stringResource(Res.string.chat_bots_env), style = AzphaltType.body, color = azphaltOnSurface())
        }
        host.platforms.forEach { platform -> ChatBotCard(host, platform) }
    }
}

@Composable
private fun ChatBotCard(host: ChatBotHost, platform: ChatPlatform) {
    val status: ChatBotStatus by host.status(platform).collectAsState()
    var typed by remember(platform) { mutableStateOf("") }
    var rejected by remember(platform) { mutableStateOf(false) }
    val link = stringResource(
        when (status.link) {
            ChatLink.Off -> Res.string.chat_bots_link_off
            ChatLink.Connecting -> Res.string.chat_bots_link_connecting
            ChatLink.Connected -> Res.string.chat_bots_link_connected
            ChatLink.Failed -> Res.string.chat_bots_link_failed
            ChatLink.TokenRefused -> Res.string.chat_bots_link_refused
        },
    )
    val lines = buildList {
        add(stringResource(if (status.hasToken) Res.string.chat_bots_token_saved else Res.string.chat_bots_token_none))
        add(link)
        if (status.paired) {
            add(stringResource(Res.string.chat_bots_paired))
        } else if (status.enabled && status.hasToken) {
            add(stringResource(Res.string.chat_bots_pair_how))
        }
        status.pairingCode?.let { code ->
            add(stringResource(Res.string.chat_bots_code, code))
            add(stringResource(Res.string.chat_bots_code_note))
        }
        if (status.ignoredStrangers > 0 || status.rateLimited > 0) {
            add(stringResource(Res.string.chat_bots_counts, status.ignoredStrangers.toInt(), status.rateLimited.toInt()))
        }
    }
    AzphaltRecord(
        seed = "chat-bot-${platform.id}",
        eyebrow = if (status.enabled) stringResource(Res.string.chat_bots_on) else stringResource(Res.string.chat_bots_off),
        title = platform.displayName,
        body = lines.joinToString("\n"),
        well = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (host.canStoreTokens) {
                    AzphaltTextField(
                        value = typed,
                        onValueChange = {
                            typed = it
                            rejected = false
                        },
                        label = stringResource(Res.string.chat_bots_token_label),
                        secret = true,
                        errorMessage = if (rejected) stringResource(Res.string.chat_bots_token_rejected) else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (host.canStoreTokens) {
                        AzphaltPill(
                            label = stringResource(Res.string.chat_bots_token_save),
                            seed = "chat-bot-save-${platform.id}",
                            onClick = {
                                if (host.setToken(platform, typed)) {
                                    typed = ""
                                    rejected = false
                                } else {
                                    rejected = true
                                }
                            },
                        )
                        if (status.hasToken) {
                            AzphaltPill(
                                label = stringResource(Res.string.chat_bots_token_clear),
                                seed = "chat-bot-clear-${platform.id}",
                                onClick = { host.clearToken(platform) },
                            )
                        }
                    }
                    if (status.hasToken) {
                        AzphaltPill(
                            label = stringResource(if (status.enabled) Res.string.chat_bots_on else Res.string.chat_bots_off),
                            seed = "chat-bot-toggle-${platform.id}",
                            selected = status.enabled,
                            onClick = { host.setEnabled(platform, !status.enabled) },
                        )
                    }
                    if (status.paired) {
                        AzphaltPill(
                            label = stringResource(Res.string.chat_bots_unpair),
                            seed = "chat-bot-unpair-${platform.id}",
                            onClick = { host.unpair(platform) },
                        )
                    }
                }
            }
        },
    )
}
