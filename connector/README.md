# aive-connector

A Cloudflare Worker that lets Claude read the state of your own Aive device. It is a remote MCP server (Streamable HTTP, `POST /mcp`) with its own OAuth 2.1 authorization server, built for Claude's connectors directory. It reaches The Aive through the existing [relay](../relay/README.md). How it works, what it trusts and what it can see: [`docs/Connector.md`](../docs/Connector.md).

Four read-only tools: `get_device_status`, `get_swarm_status`, `get_story`, `list_waiting_approvals`. Nothing approves, rejects, edits, prompts or starts anything.

## Test

~~~sh
cd connector
npm test
~~~

Plain Node (20 or later), no dependencies. The tests run the Worker against in-memory Durable Objects, a fake relay with a fake Aive device behind it (using the real sealing code), and a fake Google (token endpoint and keys, signed with a locally generated RSA key).

## Deploy

1. Create a Google OAuth client (below) and put its id in `wrangler.toml` as `GOOGLE_CLIENT_ID`. Set `PUBLIC_BASE_URL` to the address the Worker is served at, with no trailing slash. `RELAY_URL` defaults to the standard relay; a pairing code that names a different relay is refused unless you list it in a comma-separated `EXTRA_RELAYS` variable.
2. Add the two Worker secrets to the central `HereLiesAz/workflows` repository (Settings > Secrets and variables > Actions): `CONNECTOR_KEK` and `GOOGLE_CLIENT_SECRET`. The central deploy sets them on the Worker after each deploy (the connector's `worker_secrets` profile). Make the key with:

   ~~~sh
   openssl rand -base64 32 | tr '+/' '-_' | tr -d '='
   ~~~

   `CONNECTOR_KEK` is 32 random bytes, base64url; it encrypts every stored pairing. Losing or changing it makes stored pairings unreadable, and people have to connect again. Do not put either value in the repository.
3. A change under `connector/` that reaches main deploys it: `.github/workflows/connector-deploy.yml` is bound to the central Cloudflare Worker Deploy workflow, which runs the tests, then `wrangler deploy` (not `versions upload`, so the Durable Object migration is applied), then sets the two secrets. By hand, from `connector/`: `npx wrangler@4 deploy`, then `npx wrangler@4 secret put CONNECTOR_KEK` and `npx wrangler@4 secret put GOOGLE_CLIENT_SECRET`.

## Google Cloud Console

Google only proves who the person is; this Worker issues its own tokens to Claude.

1. In Google Cloud Console, create or choose a project. Under **Google Auth Platform** (APIs & Services > OAuth consent screen), set the app name (The Aive connector), a support email, the **privacy policy link** `https://hereliesaz.github.io/aive/privacy`, and your home page link (the Worker's address).
2. Under **Data access** (scopes), add only `openid` and `.../auth/userinfo.email`. These are non-sensitive scopes, so no Google verification review is needed for them; publish the app (set it **In production**) so any Google account can sign in.
3. Under **Clients**, create an OAuth client of type **Web application**. Add the **Authorized redirect URI** `${PUBLIC_BASE_URL}/google/callback` (for example `https://aive-connector.hereliesaz.workers.dev/google/callback`). No JavaScript origins are needed.
4. Copy the client id into `GOOGLE_CLIENT_ID` and the client secret into the `GOOGLE_CLIENT_SECRET` Worker secret.

## Submission checklist (claude.ai/directory/manage)

- **Server URL:** `${PUBLIC_BASE_URL}/mcp`.
- **Authentication:** OAuth. Dynamic client registration is on; the callbacks `https://claude.ai/api/mcp/auth_callback` and `https://claude.com/api/mcp/auth_callback` are allowed. PKCE S256 is required. Sign-in is with Google; then the person pastes their Aive pairing code once.
- **Privacy policy URL:** `https://hereliesaz.github.io/aive/privacy`.
- **Documentation:** `https://github.com/HereLiesAz/aive/blob/main/docs/Connector.md`.
- **Icon:** the Aive mark (`branding/`); supply the file the form asks for.
- **Tools:** four, each with a title and all four annotations (`readOnlyHint` true, `destructiveHint` false, `idempotentHint` true, `openWorldHint` false). `openWorldHint` is false because the tools only reach the person's own device; text that swarm content brings in is marked as untrusted data in every result.
- **Test account:** the reviewer needs a Google account and an Aive pairing code, and The Aive running on an Android device with Remote access on. Say so plainly in the submission; there is no shared test account.
- **Reviewer notes:** the connector is a trusted party (it holds the pairing key encrypted and reads content while a call is in flight), unlike the relay. See the trust section of `docs/Connector.md`.

## Limits

Tool calls time out after 20 seconds; results are cut at 100 KB; 30 tool calls a minute per account; tokens: access 1 hour, refresh 30 days (rotated on every use); authorization codes 10 minutes and single use.
