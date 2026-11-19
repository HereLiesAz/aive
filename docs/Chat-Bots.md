# Chat bots (Telegram and Discord)

The keeper can reach The Aive from a direct message to a Telegram bot or a Discord bot. There are three uses, and one thing that is never offered.

1. **Notifications.** What needs the keeper (the Messages items that wait on them) and how a run ended are sent to the keeper's paired chat as short plain text.
2. **Status and control.** `/status`, `/hives`, `/runs`, `/run <hive> <objective>`, `/stop` then `/stop confirm`, `/help`.
3. **A creature that talks to chat.** The step **Post to my chat** sends a bounded, scrubbed text from an earlier step to the paired chat, after the keeper allows it in the app.

**Never offered: approving from chat.** No message, command or button in a chat approves, rejects or releases a plan, a gate or a spend stop. A run started from chat stops at its plan approval, in the app. Every reply that could suggest otherwise says "Open The Aive to approve." ([Threat model](THREAT_MODEL.md), invariant 8).

## Where it runs

The desktop app, and `aive serve` (the headless engine). Not on Android: a phone cannot hold the connection open, so the part is not shown there and nothing starts. Not on the web. See [Platform parity](Platform-Parity.md).

Both connections are **outbound only**. There is no open port and no webhook.

| | Telegram | Discord |
|---|---|---|
| Connection | HTTPS long polling, `getUpdates` (30 s) and `sendMessage` | Gateway WebSocket (`wss://gateway.discord.gg/?v=10&encoding=json`) and the REST API `POST /channels/{id}/messages` |
| What it listens to | `message` updates in one-to-one chats | direct messages (intent `DIRECT_MESSAGES`, 4096) |
| Privileged intent | none | none: without `MESSAGE_CONTENT`, Discord still sends the content of direct messages to the bot |
| Message limit | 4096 characters (The Aive keeps under it) | 2000 characters (same) |
| Sent as | plain text, no `parse_mode`, link previews off | plain text, `allowed_mentions.parse` empty, so nothing can ping anyone |

Groups, supergroups, channels and servers are ignored. Slash commands (Discord interactions) are not used, because they need an inbound endpoint.

## Setting up

1. Make a bot with the platform (Telegram: BotFather; Discord: the Developer Portal, then turn on direct messages for the app's installation). Keep its token.
2. Keeper's room, **Chat bots**: paste the token under the platform, **Save token**, then turn it **On**. The token goes to the place the app keeps its other keys (macOS Keychain, the Secret Service on Linux, DPAPI on Windows). If none is available it is refused; it is never written to a settings file. It is never shown again, never logged and never printed; the one redactor (`ControlRedaction`) knows the shapes of both tokens.
3. Pair: send `/pair` to the bot in a direct message. The app shows a **one-time six-digit code** under that platform. Type the code into the same chat within 5 minutes. Only then is that account the keeper.

Everything is **off by default**; each platform has its own switch.

### Headless

`aive serve` reads the tokens and the switches from the environment, never from a file:

```sh
export AIVE_TELEGRAM_BOT_TOKEN=...   AIVE_CHAT_TELEGRAM=on
export AIVE_DISCORD_BOT_TOKEN=...    AIVE_CHAT_DISCORD=on
aive serve
```

The pairing code is printed on the daemon's own output (the headless counterpart of the code the app shows). The paired account is kept in the same per-user preferences the desktop app uses, so run only one of the two per bot token (Telegram refuses two pollers on one bot).

## Pairing and the allowlist

- `/pair` starts a challenge for **that chat only**. A second chat cannot replace a waiting challenge or use its code, and while paired nobody can start another.
- The code works once, ends after 5 minutes, and ends after three wrong tries. A replayed code does nothing.
- Once paired, a message from any other account, any group or channel, or any bot gets **no reply at all**. It is counted (Keeper's room shows "Ignored messages from others" and "Held back by the rate limit") and rate limited.
- The keeper's own messages are rate limited too (20 a minute).
- **Unpair** in the Keeper's room forgets the keeper. Removing or replacing a token also unpairs.

## Commands

Parsed strictly: an exact command word, no extra arguments, at most 2,000 characters, control and invisible characters removed. Anything else gets "I do not know that" and is never echoed.

| Command | What it does |
|---|---|
| `/status` | Version, number of hives and runs, live runs, how many things wait on you. |
| `/hives` | The hives with their ids. |
| `/runs` | The six latest runs. |
| `/run <hive> <objective>` | Starts a run of a saved hive (the id, or its exact name if unique), through the same `startRun` the control API uses: same validation, same refusals ("a run is already unfinished"), same context-taint marking. The objective is marked as external content, as for any run started from outside the app, so a step that can change something waits at a context-taint gate. The run stops at its plan approval, in the app. |
| `/stop` | Names the live run and asks for `/stop confirm` within 60 seconds. Nothing stops yet. |
| `/stop confirm` | Stops the run it named, once, if it is still the live one and the 60 seconds have not passed. |
| `/help` | The list. |

The handler holds only the control host. It has no member that approves, rejects or releases anything, so no command can reach one; tests send approval-shaped messages and record every call the host receives.

## Notifications

Reuse the queen's level (Keeper's room, This device). Every sentence comes from a closed set plus a short run id; no text from a run is ever sent, and none holds a link.

- **Quiet** (the default): "N things need you. Open The Aive to approve.", and "Run x finished.", "failed." or "was stopped."
- **Helpful**: the same, plus "Run x started."

Pacing: at Quiet at least 60 seconds between messages and six an hour; at Helpful 20 seconds and twenty an hour. What waits is combined into one message of at most six lines and a bounded queue (older ones are dropped, with "and N more"). The first look after the bot starts only records what is already there.

## Post to my chat

In Edit, **Add creature**, **A step, run as it is**, **Post to my chat** (shown only where a chat bot can run) adds two creatures: **Allow posting to my chat?** (a human-approval gate, the keeper's consent for this run, asked in the app like any outward step) and **Post to my chat**, which waits for it. Wire the creature that writes the text to the post step.

- It sends the text of the creatures it waits for, scrubbed, with links removed, at most 3,000 characters, to the paired chat of each platform that is on, and to no other chat.
- The graph validator refuses a post step with no unconditional approval before it, and the executor refuses to send unless that gate was approved in this run.
- It is made once: one attempt, and a restart in the middle is reported, never repeated by itself. The artifact says only how many characters went where, not the text.
- Its output is external content for the context-taint rule, like any external service, and the step counts as able to change things.

## Limits and what is not built

- No approval from chat, by design.
- No group chats, no channels, no Discord slash commands or buttons, no attachments.
- No Android and no web.
- The paired account is kept in the user's per-user preferences, not in the keychain: it identifies the keeper to the bot, and it is not a secret.
- The Discord Gateway and the Telegram long poll are written from the vendors' documentation and were not run against the live services in the session that wrote them; see the change log.
