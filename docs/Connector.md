# Claude connector

`connector/` holds a Cloudflare Worker, **aive-connector**, that lets Claude read the state of a keeper's own Aive device. It is a remote MCP server for Claude's connectors directory. It reaches The Aive through the existing [relay](Remote-API.md#relay) and uses the same pairing code, so the device side needs nothing new: the device keeps answering the sealed `ping` and `watch` requests it already answers.

Deploy and directory submission steps: [`connector/README.md`](../connector/README.md).

## What it is, and the trade-off

The relay is opaque: it forwards sealed frames and never holds the key. **The connector is not.** To read the device for Claude it must hold the pairing (room, client token and the end-to-end key), and it decrypts the device's replies while a call is in flight. That makes it a trusted party in a way the relay is not:

- The pairing is stored encrypted at rest (AES-256-GCM under the Worker secret `CONNECTOR_KEK`, bound to the account) in a Durable Object. It is decrypted only inside that object when a valid token asks for a call, and is never logged, never put in a URL and never returned to Claude.
- While a call is in flight the Worker sees the plaintext of the device's reply (a swarm's objective, status, Story events and previews). It does not store it. The reply goes to Claude, and Claude's own policies apply from there.
- Anyone who operates or compromises the Worker, or holds `CONNECTOR_KEK` and a copy of its storage, can reach the device as a client. Run your own copy if you do not want to trust the maintainer's.
- The pairing code is a bearer secret and cannot be told apart from its owner (`docs/THREAT_MODEL.md`). So the connector offers no way to decide anything: no tool approves, rejects or edits, and none starts a swarm or runs a prompt. Approvals happen only on the device.

## Sign-in and pairing

Google proves who the person is; the connector is its own OAuth 2.1 authorization server toward Claude (dynamic client registration, PKCE S256 required, single-use authorization codes, rotating refresh tokens).

1. Claude sends the person to `/authorize`. The connector sends them to "Sign in with Google" (authorization code with PKCE, `state` and `nonce`, scopes `openid email`). It checks the ID token's signature against Google's keys, `iss`, `aud`, `exp`, `nonce` and `email_verified`.
2. The connector keeps the Google account id (`sub`) and email address, nothing else from Google. The Google access token is not used or kept.
3. If that Google account already has a stored pairing, it shows a short confirm page ("Connect Claude to your Aive", signed in as the email) with **Allow**. Otherwise it asks for the Aive pairing code (Settings, Remote Access, Copy). It checks the code against the relay (the relay must accept the token and the device must answer a sealed ping) before storing anything. **Use a different pairing code** replaces the stored pairing.
4. One pairing per Google account. The consent form is protected by a per-request nonce, a cookie bound to the browser that started the request, a strict content-security policy with no script, and rate limits per client address and per pairing code.

Access tokens last 1 hour, refresh tokens 30 days and are rotated on every use (a stale one coming back later revokes that connection). Tokens, codes and nonces are random 256-bit values, stored only as SHA-256 hashes.

## Tools

All four are read-only (`readOnlyHint` true, `destructiveHint` false, `idempotentHint` true, `openWorldHint` false). Each is backed by a request the device already answers (`docs/Remote-API.md`, Client to host):

| Tool | Host request | Returns |
|---|---|---|
| `get_device_status` | `ping` | Whether the device answered. |
| `get_swarm_status` | `watch` (no events) | The run The Aive is showing (`run_status`) and recent queued-launch outcomes (`launch_outcome`). |
| `get_story` | `watch` with `events`, optional `runId` and `afterSeq` | The ordered run events, newest `limit` (default 50, at most 200). |
| `list_waiting_approvals` | `watch` with `events` | Gates and decisions still waiting, derived from the newest 200 events. |

**Starting a swarm is not offered.** The device has no request that starts a saved swarm: its `launch` request queues a new workflow from an objective, and it starts only when The Aive is next opened on the device. That is not "start a saved swarm", and adding one means changing the device's runtime, which this connector does not do.

Each call opens a short WebSocket to the relay as a client, seals its requests, and closes when the device answers a final ping. Side effects on the device: a `watch` makes the host remember the client (up to 16, oldest dropped) and, for events, read the run's timeline for as long as a watcher is registered. `get_story` and `list_waiting_approvals` wait up to 1.5 seconds after the ping for events the device sends a moment later.

## Untrusted content

Everything from the device is data, not instructions: swarm objectives, reasons, plan previews and artifact previews can carry text from the web or from files. Every result starts with a notice that says so and wraps the data in `<device_data>` tags.

## Errors

Tool failures are returned as an MCP tool result with `isError` and a plain sentence: the device is offline; the pairing was reset or deleted; the relay could not be reached or is busy; the device did not answer in 20 seconds; bad arguments; too many calls.

## Revoking

- Turning Remote access off in The Aive, or **Reset pairing**, deletes the room on the relay. The next call finds the relay refusing the token (or closing with `4001`/`4002`), tells Claude the pairing is gone and deletes the stored pairing and every token for that account. Claude then asks the person to connect again.
- `POST /revoke` (RFC 7009) with either token deletes the stored pairing and every token for that account at once.
- A pairing is also deleted by the object's own timer once its last refresh token has expired, or 10 minutes after an authorization that was never completed.

## Limits

- 20 seconds per call; results cut at 100 KB (the oldest events go first); requests are a few hundred bytes and replies over the relay's frame limits (1,000,000 bytes sealed, 600,000 plaintext) are dropped.
- 30 tool calls a minute per account; registration, sign-in and token endpoints are rate limited per client address; a wrong pairing code is limited per request (5) and per code (5 in 10 minutes).
- The connector uses only the configured relay (default `https://aive-relay.hereliesaz.workers.dev`) plus any the operator lists in `EXTRA_RELAYS`, so a pairing code cannot aim it at an arbitrary address.
- Redirect addresses: exact match of the two Claude callbacks, or an `http` loopback address (any port, per RFC 8252).

## Testing

`cd connector && npm test` (plain Node, no dependencies). The Kotlin device side is unchanged and is not exercised by these tests; the fake device in `connector/testkit/kit.js` follows `RelayHostRouter.kt` and `RelaySealedFrames.kt`.
