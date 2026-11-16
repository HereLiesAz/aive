/**
 * The OAuth 2.1 authorization server toward Claude: metadata, dynamic client registration,
 * /authorize (Google sign-in, then consent), /token, /revoke. Google only authenticates the
 * human; this Worker issues its own tokens.
 */
import { converse, DeviceError } from "./device.js";
import { exchangeCode, GoogleError, googleAuthUrl, verifyIdToken } from "./google.js";
import { confirmPage, errorPage, pairingPage } from "./pages.js";
import { allowedRelayOrigin, PairingCodeError, parsePairingCode } from "./seal.js";
import { clientIp, json, randomToken, readCookie, sha256B64url, sha256Hex } from "./util.js";

export const SCOPE = "aive:read";
const COOKIE = "__Host-aive_txn";
const CLAUDE_CALLBACKS = ["https://claude.ai/api/mcp/auth_callback", "https://claude.com/api/mcp/auth_callback"];
const MAX_FORM_BYTES = 8192;

export const baseUrl = (env) => String(env.PUBLIC_BASE_URL || "").replace(/\/+$/, "");
export const resourceUrl = (env) => `${baseUrl(env)}/mcp`;
const relayOrigins = (env) => [new URL(env.RELAY_URL).origin, ...String(env.EXTRA_RELAYS || "").split(",").map((s) => s.trim()).filter(Boolean)];

const nameOf = (ns, name) => ns.get(ns.idFromName(name));
async function call(stub, op, input = {}) {
  const response = await stub.fetch(new Request(`https://do.internal${op}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(input) }));
  let data = {};
  try {
    data = await response.json();
  } catch {
    // leave empty
  }
  return { status: response.status, ...data };
}
export const accountStub = (env, acct) => nameOf(env.ACCOUNTS, `acct:${acct}`);

/** True when the caller is over [max] hits per [windowMs] for [key]. */
async function limited(env, key, max, windowMs) {
  const r = await call(nameOf(env.LIMITER, key), "/hit", { max, windowMs });
  return r.allowed === false ? r.retryAfter || 60 : 0;
}
const ipKey = async (request, what) => `${what}:${(await sha256Hex(clientIp(request))).slice(0, 32)}`;
const tooMany = (retryAfter) =>
  json({ error: "rate_limited", error_description: "Too many attempts. Wait a few minutes and try again." }, 429, { "retry-after": String(retryAfter) });

const cors = { "access-control-allow-origin": "*", "access-control-allow-headers": "content-type, authorization, mcp-protocol-version", "access-control-allow-methods": "GET, POST, OPTIONS" };
export const withCors = (response) => {
  for (const [k, v] of Object.entries(cors)) response.headers.set(k, v);
  return response;
};

// ---- metadata -----------------------------------------------------------------------------

export function protectedResourceMetadata(env) {
  return json({
    resource: resourceUrl(env),
    authorization_servers: [baseUrl(env)],
    bearer_methods_supported: ["header"],
    scopes_supported: [SCOPE],
    resource_name: "The Aive",
  });
}

export function authorizationServerMetadata(env) {
  const base = baseUrl(env);
  return json({
    issuer: base,
    authorization_endpoint: `${base}/authorize`,
    token_endpoint: `${base}/token`,
    registration_endpoint: `${base}/register`,
    revocation_endpoint: `${base}/revoke`,
    response_types_supported: ["code"],
    grant_types_supported: ["authorization_code", "refresh_token"],
    code_challenge_methods_supported: ["S256"],
    token_endpoint_auth_methods_supported: ["none"],
    revocation_endpoint_auth_methods_supported: ["none"],
    scopes_supported: [SCOPE],
    authorization_response_iss_parameter_supported: true,
    service_documentation: "https://github.com/HereLiesAz/aive/blob/main/docs/Connector.md",
  });
}

// ---- redirect URIs ------------------------------------------------------------------------

function parseUri(text) {
  try {
    const url = new URL(text);
    return url.hash || url.username || url.password ? null : url;
  } catch {
    return null;
  }
}
const isLoopback = (url) => url.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);

/** Whether [text] may be registered: the two Claude callbacks, or an http loopback address. */
export function registrableRedirect(text) {
  if (typeof text !== "string" || text.length > 512) return false;
  if (CLAUDE_CALLBACKS.includes(text)) return true;
  const url = parseUri(text);
  return !!url && isLoopback(url);
}

/** Exact match; a loopback address may differ in its port only (RFC 8252, section 7.3). */
function redirectMatches(registered, given) {
  if (registered.includes(given)) return true;
  const g = parseUri(given);
  if (!g || !isLoopback(g)) return false;
  return registered.some((r) => {
    const u = parseUri(r);
    return u && isLoopback(u) && u.hostname === g.hostname && u.pathname === g.pathname && u.search === g.search;
  });
}

// ---- registration -------------------------------------------------------------------------

export async function register(request, env) {
  const retry = await limited(env, await ipKey(request, "reg"), 20, 60 * 60 * 1000);
  if (retry) return tooMany(retry);
  const text = await request.text();
  let data;
  try {
    data = text.length <= MAX_FORM_BYTES ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  const invalid = (description) => json({ error: "invalid_client_metadata", error_description: description }, 400);
  if (!data || typeof data !== "object") return invalid("Send a JSON object.");
  const uris = data.redirect_uris;
  if (!Array.isArray(uris) || uris.length < 1 || uris.length > 10 || !uris.every(registrableRedirect)) {
    return json({ error: "invalid_redirect_uri", error_description: "Redirect URIs must be the Claude callback addresses or an http loopback address." }, 400);
  }
  const grants = data.grant_types === undefined ? ["authorization_code", "refresh_token"] : data.grant_types;
  if (!Array.isArray(grants) || !grants.every((g) => g === "authorization_code" || g === "refresh_token")) return invalid("Only authorization_code and refresh_token are supported.");
  if (data.response_types !== undefined && !(Array.isArray(data.response_types) && data.response_types.every((t) => t === "code"))) return invalid("Only the code response type is supported.");
  if (data.token_endpoint_auth_method !== undefined && data.token_endpoint_auth_method !== "none") return invalid("Only public clients (token_endpoint_auth_method none) are supported.");
  const clientName = typeof data.client_name === "string" ? data.client_name.replace(/[\u0000-\u001f\u007f]/g, "").slice(0, 100) : "";
  const id = randomToken(16);
  const issued = Math.floor(Date.now() / 1000);
  await call(nameOf(env.REGISTRY, "registry"), "/register", { id, client: { redirect_uris: uris, client_name: clientName } });
  return json(
    { client_id: id, client_id_issued_at: issued, redirect_uris: uris, client_name: clientName, grant_types: grants, response_types: ["code"], token_endpoint_auth_method: "none" },
    201,
  );
}

const getClient = async (env, id) => {
  if (typeof id !== "string" || id.length > 64) return null;
  const r = await call(nameOf(env.REGISTRY, "registry"), "/get", { id });
  return r.ok ? r.client : null;
};

// ---- authorize ----------------------------------------------------------------------------

function redirectBack(env, request, params) {
  const url = new URL(request.redirectUri);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  if (request.state) url.searchParams.set("state", request.state);
  url.searchParams.set("iss", baseUrl(env));
  return new Response(null, { status: 303, headers: { location: url.toString(), "cache-control": "no-store" } });
}

const cookieHeader = (value) => `${COOKIE}=${value}; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=600`;

export async function authorizeStart(request, env) {
  if (!env.GOOGLE_CLIENT_ID || !env.GOOGLE_CLIENT_SECRET || !env.CONNECTOR_KEK) {
    return errorPage("This connector is not set up yet. The person who runs it needs to finish its configuration.", 503);
  }
  const q = new URL(request.url).searchParams;
  const client = await getClient(env, q.get("client_id"));
  const redirectUri = q.get("redirect_uri") || "";
  if (!client) return errorPage("Claude is not a known client of this connector.");
  if (!redirectMatches(client.redirect_uris, redirectUri)) return errorPage("The return address does not match the one registered.");
  const req = { redirectUri, state: (q.get("state") || "").slice(0, 512) };
  const bad = (error, description) => redirectBack(env, req, { error, error_description: description });
  if (q.get("response_type") !== "code") return bad("unsupported_response_type", "Only response_type=code is supported.");
  if (q.get("code_challenge_method") !== "S256") return bad("invalid_request", "PKCE with code_challenge_method=S256 is required.");
  const challenge = q.get("code_challenge") || "";
  if (!/^[A-Za-z0-9\-._~]{43,128}$/.test(challenge)) return bad("invalid_request", "A valid code_challenge is required.");
  const scope = q.get("scope");
  if (scope && scope.split(" ").some((s) => s && s !== SCOPE)) return bad("invalid_scope", `Only ${SCOPE} is available.`);
  const resource = q.get("resource");
  if (resource && resource !== resourceUrl(env) && resource !== baseUrl(env)) return bad("invalid_target", "Unknown resource.");
  const retry = await limited(env, await ipKey(request, "auth"), 30, 10 * 60 * 1000);
  if (retry) return errorPage("Too many attempts. Wait a few minutes and try again.", 429);

  const txnId = randomToken(16);
  const stateSecret = randomToken(32);
  const browserSecret = randomToken(32);
  const googleVerifier = randomToken(32);
  const nonce = randomToken(16);
  await call(nameOf(env.TXNS, txnId), "/create", {
    rec: {
      request: { clientId: client.id || q.get("client_id"), redirectUri, state: req.state, challenge },
      browserHash: await sha256B64url(browserSecret),
      stateHash: await sha256B64url(stateSecret),
      googleVerifier,
      nonce,
    },
  });
  const location = googleAuthUrl({
    clientId: env.GOOGLE_CLIENT_ID,
    redirectUri: `${baseUrl(env)}/google/callback`,
    state: `${txnId}.${stateSecret}`,
    nonce,
    challenge: await sha256B64url(googleVerifier),
  });
  return new Response(null, { status: 302, headers: { location, "set-cookie": cookieHeader(`${txnId}.${browserSecret}`), "cache-control": "no-store" } });
}

const splitCookie = (request) => {
  const [id, secret, extra] = readCookie(request, COOKIE).split(".");
  return /^[A-Za-z0-9_-]{16,64}$/.test(id || "") && /^[A-Za-z0-9_-]{16,64}$/.test(secret || "") && extra === undefined ? { id, secret } : null;
};

export async function googleCallback(request, env, deps) {
  const q = new URL(request.url).searchParams;
  const cookie = splitCookie(request);
  const [stateId, stateSecret, extra] = (q.get("state") || "").split(".");
  if (!cookie || !stateId || !stateSecret || extra !== undefined || stateId !== cookie.id) {
    return errorPage("This sign-in did not start from Claude on this browser.");
  }
  const retry = await limited(env, await ipKey(request, "gcb"), 30, 10 * 60 * 1000);
  if (retry) return errorPage("Too many attempts. Wait a few minutes and try again.", 429);
  const txn = nameOf(env.TXNS, cookie.id);
  const t = await call(txn, "/google", { browserSecret: cookie.secret, stateSecret });
  if (!t.ok) return errorPage("This sign-in expired or was already used.");
  if (q.get("error") || !q.get("code")) {
    await call(txn, "/delete");
    return redirectBack(env, t.request, { error: "access_denied", error_description: "The Google sign-in was cancelled." });
  }
  let identity;
  try {
    const idToken = await exchangeCode({
      fetchImpl: deps.fetch,
      clientId: env.GOOGLE_CLIENT_ID,
      clientSecret: env.GOOGLE_CLIENT_SECRET,
      redirectUri: `${baseUrl(env)}/google/callback`,
      code: q.get("code"),
      verifier: t.verifier,
    });
    identity = await verifyIdToken(idToken, { fetchImpl: deps.fetch, clientId: env.GOOGLE_CLIENT_ID, nonce: t.nonce, now: deps.now() });
  } catch (e) {
    await call(txn, "/delete");
    return errorPage(e instanceof GoogleError ? e.message : "The Google sign-in failed.");
  }
  const acct = await sha256B64url(`google-sub:${identity.sub}`);
  const known = await call(accountStub(env, acct), "/identify", { acct, sub: identity.sub, email: identity.email });
  if (!known.ok) return errorPage("The connector could not save your sign-in. Try again.", 500);
  const csrf = randomToken(16);
  await call(txn, "/identify", { sub: identity.sub, email: identity.email, acct, csrfHash: await sha256B64url(csrf) });
  return known.hasPairing ? confirmPage({ email: identity.email, csrf }) : pairingPage({ email: identity.email, csrf });
}

async function readForm(request) {
  const type = request.headers.get("content-type") || "";
  if (!type.startsWith("application/x-www-form-urlencoded")) return null;
  const text = await request.text();
  return text.length > MAX_FORM_BYTES ? null : new URLSearchParams(text);
}

export async function authorizeSubmit(request, env, deps) {
  const retry = await limited(env, await ipKey(request, "post"), 20, 10 * 60 * 1000);
  if (retry) return errorPage("Too many attempts. Wait a few minutes and try again.", 429);
  const form = await readForm(request);
  const cookie = splitCookie(request);
  if (!form || !cookie) return errorPage("This page expired or was not opened from Claude.", 403);
  const txn = nameOf(env.TXNS, cookie.id);
  const checked = await call(txn, "/check", { browserSecret: cookie.secret, csrf: form.get("csrf") || "" });
  if (!checked.ok) return errorPage("This page expired or was not opened from Claude.", 403);
  const { request: req, email, acct } = checked;
  const decision = form.get("decision");
  const account = accountStub(env, acct);

  if (decision === "deny") {
    await call(txn, "/delete");
    return redirectBack(env, req, { error: "access_denied", error_description: "You did not allow the connection." });
  }
  const newCsrf = async () => {
    const csrf = randomToken(16);
    return { csrf, csrfHash: await sha256B64url(csrf) };
  };
  if (decision === "repair") {
    const n = await newCsrf();
    await call(txn, "/rotate", { csrfHash: n.csrfHash });
    return pairingPage({ email, csrf: n.csrf });
  }
  if (decision !== "allow") return errorPage("Choose Allow or Cancel.", 400);

  const code = (form.get("code") || "").trim();
  const status = await call(account, "/identify", { acct, sub: checked.sub, email });
  if (!code && status.hasPairing) return finishGrant(env, txn, account, req);

  const again = async (error, status) => {
    const n = await newCsrf();
    const r = await call(txn, "/failed", { csrfHash: n.csrfHash });
    if (!r.ok) return errorPage("Too many wrong pairing codes. Start again from Claude.", 429);
    return pairingPage({ email, csrf: n.csrf, error, status });
  };
  if (!code) return again("Paste your pairing code to continue.", 400);

  const codeRetry = await limited(env, `code:${(await sha256Hex(code)).slice(0, 32)}`, 5, 10 * 60 * 1000);
  if (codeRetry) return again("That pairing code has been tried too many times. Wait a few minutes.", 429);
  let pairing;
  try {
    pairing = parsePairingCode(code);
  } catch (e) {
    return again(e instanceof PairingCodeError ? e.message : "That pairing code could not be read.", 400);
  }
  const origin = allowedRelayOrigin(pairing.relay, relayOrigins(env));
  if (!origin) return again("This connector only works with the standard Aive relay. That pairing code uses a different relay.", 400);
  pairing.relay = origin;
  try {
    // The relay must accept the token, and the device must answer a sealed ping: that proves the
    // room, the token and the key all belong together before anything is stored.
    await converse(pairing, [], { fetchImpl: deps.fetch, timeoutMs: 8000, now: deps.now });
  } catch (e) {
    const messages = {
      pairing_revoked: "That pairing code is not valid any more. In The Aive, check Settings > Remote Access and copy the current code.",
      device_offline: "Your Aive did not answer. Open The Aive on your device, make sure Remote access is on, and try again.",
      relay_unreachable: "The relay could not be reached. Try again in a moment.",
      relay_busy: "The relay is busy. Try again in a moment.",
      timeout: "Your Aive did not answer. Open The Aive on your device and try again.",
    };
    return again(e instanceof DeviceError ? messages[e.code] || e.message : "The pairing could not be checked.", 400);
  }
  const stored = await call(account, "/pair", { pairing });
  if (!stored.ok) return errorPage("The connector could not store your pairing. The person who runs it may need to finish its configuration.", 500);
  return finishGrant(env, txn, account, req);
}

async function finishGrant(env, txn, account, req) {
  const issued = await call(account, "/issue-code", { clientId: req.clientId, redirectUri: req.redirectUri, challenge: req.challenge });
  await call(txn, "/delete");
  if (!issued.ok) return errorPage("The connector could not finish. Start again from Claude.", 500);
  return redirectBack(env, req, { code: issued.code });
}

// ---- token and revoke ---------------------------------------------------------------------

const oauthError = (error, description, status = 400) => json({ error, error_description: description }, status, { pragma: "no-cache" });
const ACCT = /^[A-Za-z0-9_-]{43}$/;

function splitToken(token, parts) {
  const items = typeof token === "string" ? token.split(".") : [];
  if (items.length !== parts || !ACCT.test(items[0]) || items.some((s) => !/^[A-Za-z0-9_-]+$/.test(s)) || token.length > 256) return null;
  return items;
}

export async function token(request, env) {
  const retry = await limited(env, await ipKey(request, "tok"), 120, 10 * 60 * 1000);
  if (retry) return tooMany(retry);
  const form = await readForm(request);
  if (!form) return oauthError("invalid_request", "Send application/x-www-form-urlencoded.");
  const client = await getClient(env, form.get("client_id"));
  if (!client) return oauthError("invalid_client", "Unknown client_id.", 401);
  const grantType = form.get("grant_type");
  let result;
  if (grantType === "authorization_code") {
    const parts = splitToken(form.get("code"), 2);
    if (!parts) return oauthError("invalid_grant", "The code is not valid.");
    result = await call(accountStub(env, parts[0]), "/exchange", {
      secret: parts[1],
      clientId: form.get("client_id"),
      redirectUri: form.get("redirect_uri") || "",
      verifier: form.get("code_verifier") || "",
    });
  } else if (grantType === "refresh_token") {
    const parts = splitToken(form.get("refresh_token"), 3);
    if (!parts) return oauthError("invalid_grant", "The refresh token is not valid.");
    result = await call(accountStub(env, parts[0]), "/refresh", { familyId: parts[1], secret: parts[2], clientId: form.get("client_id") });
  } else {
    return oauthError("unsupported_grant_type", "Use authorization_code or refresh_token.");
  }
  if (!result.ok) return oauthError(result.error === "server_misconfigured" ? "server_error" : "invalid_grant", "The code or token is not valid, or it was already used.", result.status >= 500 ? 500 : 400);
  return json({ access_token: result.access_token, token_type: "Bearer", expires_in: result.expires_in, refresh_token: result.refresh_token, scope: SCOPE }, 200, { pragma: "no-cache" });
}

/** RFC 7009: always 200. A valid access or refresh token ends the connection and deletes the pairing. */
export async function revoke(request, env) {
  const retry = await limited(env, await ipKey(request, "rev"), 60, 10 * 60 * 1000);
  if (retry) return tooMany(retry);
  const form = await readForm(request);
  const parts = form && splitToken(form.get("token"), 3);
  if (parts) await call(accountStub(env, parts[0]), "/revoke", { familyId: parts[1], secret: parts[2] });
  return new Response(null, { status: 200, headers: { "cache-control": "no-store" } });
}

/** The access token's pairing for an MCP call, or null. */
export async function pairingForToken(env, bearer) {
  const parts = splitToken(bearer, 3);
  if (!parts) return null;
  const r = await call(accountStub(env, parts[0]), "/use", { familyId: parts[1], secret: parts[2] });
  return r.ok ? { pairing: r.pairing, acct: parts[0] } : null;
}

export const purgeAccount = (env, acct) => call(accountStub(env, acct), "/purge");
export const mcpLimited = (env, acct) => limited(env, `mcp:${acct}`, 30, 60 * 1000);
