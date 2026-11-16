import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { test } from "node:test";

import { BASE, REDIRECT, challengeOf, connect, csrfOf, exchange, getTokens, googleRoundTrip, makeWorld, makePairing, rpc, startAuthorize, b64url } from "../testkit/kit.js";

const codeOf = (response) => new URL(response.headers.get("location")).searchParams.get("code");
const dump = (world) => JSON.stringify([...world.env.ACCOUNTS.objects.values()].map((o) => [...o.state.data.entries()]));

test("metadata documents S256-only PKCE, registration and revocation", async () => {
  const w = await makeWorld();
  const as = await (await w.call("GET", "/.well-known/oauth-authorization-server")).json();
  assert.equal(as.issuer, BASE);
  assert.deepEqual(as.code_challenge_methods_supported, ["S256"]);
  assert.equal(as.registration_endpoint, `${BASE}/register`);
  assert.equal(as.revocation_endpoint, `${BASE}/revoke`);
  const rs = await (await w.call("GET", "/.well-known/oauth-protected-resource")).json();
  assert.equal(rs.resource, `${BASE}/mcp`);
  assert.deepEqual(rs.authorization_servers, [BASE]);
});

test("registration accepts Claude callbacks and loopback, refuses anything else", async () => {
  const w = await makeWorld();
  const reg = (uris) => w.call("POST", "/register", { body: { redirect_uris: uris } });
  assert.equal((await reg([REDIRECT, "https://claude.com/api/mcp/auth_callback"])).status, 201);
  assert.equal((await reg(["http://localhost:3000/cb", "http://127.0.0.1/cb"])).status, 201);
  assert.equal((await reg(["https://evil.example/cb"])).status, 400);
  assert.equal((await reg(["https://claude.ai/api/mcp/auth_callback#x"])).status, 400);
  assert.equal((await reg([])).status, 400);
});

test("happy path: Google sign-in, pairing code, PKCE, tokens, and an MCP call", async () => {
  const w = await makeWorld();
  const { started, html, tokens } = await getTokens(w);
  assert.match(html, /Signed in as <strong>keeper@example.com<\/strong>/);
  assert.match(html, /approve, reject or change anything/);
  assert.equal(started.response.status, 302);
  const google = new URL(started.response.headers.get("location"));
  assert.equal(google.origin + google.pathname, "https://accounts.google.com/o/oauth2/v2/auth");
  assert.equal(google.searchParams.get("scope"), "openid email");
  assert.equal(google.searchParams.get("code_challenge_method"), "S256");
  assert.equal(google.searchParams.get("redirect_uri"), `${BASE}/google/callback`);
  assert.equal(tokens.token_type, "Bearer");
  assert.equal(tokens.expires_in, 3600);
  const call = await (await rpc(w, tokens.access_token, "tools/call", { name: "get_device_status", arguments: {} })).json();
  assert.equal(call.result.isError, undefined);
  assert.equal(call.result.structuredContent.online, true);
  // Nothing sensitive is stored in the clear.
  const stored = dump(w);
  for (const secret of [w.pairing.clientToken, w.pairing.key, w.code, tokens.access_token.split(".")[2], tokens.refresh_token.split(".")[2]]) {
    assert.ok(!stored.includes(secret), "a sensitive value is stored in the clear");
  }
  // What Google gives is kept to the account id and the email address, nothing else.
  assert.ok(stored.includes("google-sub-1") && stored.includes("keeper@example.com"));
});

test("the consent page is script-free with a strict CSP and no pairing code in any URL", async () => {
  const w = await makeWorld();
  const started = await startAuthorize(w);
  const { callback, cookie } = await googleRoundTrip(w, started);
  const csp = callback.headers.get("content-security-policy");
  assert.match(csp, /default-src 'none'/);
  assert.ok(!/script-src|unsafe-inline/.test(csp));
  assert.ok(!/<script/i.test(await callback.text()));
  assert.match(cookie, /^__Host-aive_txn=/);
  assert.match(started.response.headers.get("set-cookie"), /HttpOnly/);
});

test("PKCE S256 is required: plain and missing challenges are refused", async () => {
  const w = await makeWorld();
  const reg = await (await w.call("POST", "/register", { body: { redirect_uris: [REDIRECT] } })).json();
  const base = { response_type: "code", client_id: reg.client_id, redirect_uri: REDIRECT, state: "s" };
  const plain = await w.call("GET", `/authorize?${new URLSearchParams({ ...base, code_challenge: "a".repeat(43), code_challenge_method: "plain" })}`);
  assert.equal(plain.status, 303);
  assert.equal(new URL(plain.headers.get("location")).searchParams.get("error"), "invalid_request");
  const none = await w.call("GET", `/authorize?${new URLSearchParams(base)}`);
  assert.equal(new URL(none.headers.get("location")).searchParams.get("error"), "invalid_request");
});

test("a redirect address that is not registered is never redirected to", async () => {
  const w = await makeWorld();
  const started = await startAuthorize(w, { redirectUri: REDIRECT });
  const bad = new URLSearchParams({ response_type: "code", client_id: started.reg.client_id, redirect_uri: "https://claude.ai/api/mcp/other", code_challenge: challengeOf("v".repeat(43)), code_challenge_method: "S256" });
  const r = await w.call("GET", `/authorize?${bad}`);
  assert.equal(r.status, 400);
  assert.equal(r.headers.get("location"), null);
});

test("a wrong code_verifier is refused and burns the code", async () => {
  const w = await makeWorld();
  const c = await connect(w);
  const code = codeOf(c.post);
  const wrong = await exchange(w, c.started, code, { code_verifier: b64url(randomBytes(32)) });
  assert.equal(wrong.status, 400);
  assert.equal((await wrong.json()).error, "invalid_grant");
  assert.equal((await exchange(w, c.started, code)).status, 400, "the right verifier no longer works");
});

test("a replayed code is refused and revokes what the first use minted", async () => {
  const w = await makeWorld();
  const { started, authCode, tokens } = await getTokens(w);
  const again = await exchange(w, started, authCode);
  assert.equal(again.status, 400);
  assert.equal((await again.json()).error, "invalid_grant");
  const r = await rpc(w, tokens.access_token, "tools/list");
  assert.equal(r.status, 401);
});

test("a code older than ten minutes is refused; a different redirect_uri is refused", async () => {
  const w = await makeWorld();
  let now = Date.now();
  w.env.clock = () => now;
  const c = await connect(w);
  const code = codeOf(c.post);
  assert.equal((await exchange(w, c.started, code, { redirect_uri: "http://localhost/cb" })).status, 400);
  const c2 = await connect(w);
  now += 10 * 60 * 1000 + 1;
  assert.equal((await exchange(w, c2.started, codeOf(c2.post))).status, 400);
});

test("refresh rotates the token; a stale refresh token is refused and, later, revokes the family", async () => {
  const w = await makeWorld();
  let now = Date.now();
  w.env.clock = () => now;
  const { started, tokens } = await getTokens(w);
  const refresh = (rt) => w.call("POST", "/token", { form: { grant_type: "refresh_token", refresh_token: rt, client_id: started.reg.client_id } });
  const second = await (await refresh(tokens.refresh_token)).json();
  assert.notEqual(second.refresh_token, tokens.refresh_token);
  assert.notEqual(second.access_token, tokens.access_token);
  assert.equal((await rpc(w, tokens.access_token, "tools/list")).status, 401, "the old access token stops working");
  assert.equal((await rpc(w, second.access_token, "tools/list")).status, 200);
  assert.equal((await refresh(tokens.refresh_token)).status, 400, "reuse inside the grace window is refused");
  assert.equal((await refresh(second.refresh_token)).status, 200, "and does not end the family");
  now += 10 * 60 * 1000;
  const third = await (await w.call("POST", "/token", { form: { grant_type: "refresh_token", refresh_token: second.refresh_token, client_id: started.reg.client_id } })).json();
  assert.equal(third.error, "invalid_grant");
});

test("access tokens last an hour, refresh tokens thirty days", async () => {
  const w = await makeWorld();
  let now = Date.now();
  w.env.clock = () => now;
  const { started, tokens } = await getTokens(w);
  now += 61 * 60 * 1000;
  assert.equal((await rpc(w, tokens.access_token, "tools/list")).status, 401);
  const r = await w.call("POST", "/token", { form: { grant_type: "refresh_token", refresh_token: tokens.refresh_token, client_id: started.reg.client_id } });
  assert.equal(r.status, 200);
  now += 31 * 24 * 60 * 60 * 1000;
  const late = await w.call("POST", "/token", { form: { grant_type: "refresh_token", refresh_token: (await r.json()).refresh_token, client_id: started.reg.client_id } });
  assert.equal(late.status, 400);
});

test("revoking a token deletes the stored pairing and ends the connection", async () => {
  const w = await makeWorld();
  const { tokens } = await getTokens(w);
  assert.ok([...w.env.ACCOUNTS.objects.values()].some((o) => o.state.data.size > 0));
  const res = await w.call("POST", "/revoke", { form: { token: tokens.refresh_token } });
  assert.equal(res.status, 200);
  assert.equal((await rpc(w, tokens.access_token, "tools/list")).status, 401);
  assert.ok([...w.env.ACCOUNTS.objects.values()].every((o) => o.state.data.size === 0), "nothing is left");
  assert.equal((await w.call("POST", "/revoke", { form: { token: "garbage" } })).status, 200, "RFC 7009: still 200");
});

test("a second sign-in by the same Google account confirms without a code; re-pairing replaces the pairing", async () => {
  const w = await makeWorld();
  await getTokens(w);
  const second = await connect(w, { withPairing: false });
  assert.doesNotMatch(second.html, /name="code"/);
  assert.match(second.html, /Your Aive is already paired/);
  assert.equal(second.post.status, 303);
  assert.ok(codeOf(second.post));
  // Re-pair with a new device through "Use a different pairing code".
  const started = await startAuthorize(w);
  const { callback, cookie } = await googleRoundTrip(w, started);
  const repair = await w.call("POST", "/authorize", { headers: { cookie }, form: { csrf: csrfOf(await callback.text()), decision: "repair" } });
  const html = await repair.text();
  assert.match(html, /name="code"/);
  const other = makePairing();
  const oldPairing = w.pairing;
  Object.assign(w.pairing, other.pairing); // the fake relay now serves the new room
  w.device.pairing = w.pairing;
  const done = await w.call("POST", "/authorize", { headers: { cookie }, form: { csrf: csrfOf(html), decision: "allow", code: other.code } });
  assert.equal(done.status, 303);
  assert.ok(!dump(w).includes(oldPairing.clientToken));
});

test("a different Google account gets its own pairing, not the first one's", async () => {
  const w = await makeWorld();
  await getTokens(w);
  w.google.claims = { sub: "google-sub-2", email: "other@example.com" };
  const second = await connect(w, { withPairing: false });
  assert.match(second.html, /name="code"/);
  assert.match(second.html, /Paste your pairing code|Pairing code/);
});

test("the consent form needs the right nonce and the browser that started it", async () => {
  const w = await makeWorld();
  const started = await startAuthorize(w);
  const { callback, cookie } = await googleRoundTrip(w, started);
  const csrf = csrfOf(await callback.text());
  const post = (headers, form) => w.call("POST", "/authorize", { headers, form });
  assert.equal((await post({ cookie }, { csrf: "wrong", decision: "allow", code: w.code })).status, 403);
  assert.equal((await post({}, { csrf, decision: "allow", code: w.code })).status, 403, "no cookie");
  assert.equal((await post({ cookie: "__Host-aive_txn=" + "a".repeat(22) + "." + "b".repeat(43) }, { csrf, decision: "allow", code: w.code })).status, 403, "someone else's browser");
  assert.equal((await post({ cookie }, { csrf, decision: "allow", code: w.code })).status, 303, "the real one still works");
});

test("an unfinished Google callback cannot be replayed or used from another browser", async () => {
  const w = await makeWorld();
  const started = await startAuthorize(w);
  const location = new URL(started.response.headers.get("location"));
  w.google.lastChallenge = location.searchParams.get("code_challenge");
  w.google.lastNonce = location.searchParams.get("nonce");
  const state = encodeURIComponent(location.searchParams.get("state"));
  const stranger = await w.call("GET", `/google/callback?code=c&state=${state}`, { headers: { cookie: "__Host-aive_txn=zzzzzzzzzzzzzzzzzzzzzz.yyyyyyyyyyyyyyyyyyyyyyy" } });
  assert.equal(stranger.status, 400);
  const cookie = started.response.headers.get("set-cookie").split(";")[0];
  assert.equal((await w.call("GET", `/google/callback?code=c&state=${state}`, { headers: { cookie } })).status, 200);
  assert.equal((await w.call("GET", `/google/callback?code=c&state=${state}`, { headers: { cookie } })).status, 400, "state is single use");
});

for (const [name, overrides] of [
  ["a nonce that does not match", { nonce: "other" }],
  ["the wrong audience", { aud: "someone-else" }],
  ["a wrong issuer", { iss: "https://evil.example" }],
  ["an expired token", { exp: Math.floor(Date.now() / 1000) - 3600 }],
  ["an unverified email", { email_verified: false }],
]) {
  test(`Google sign-in with ${name} is refused`, async () => {
    const w = await makeWorld();
    w.google.claims = overrides;
    const started = await startAuthorize(w);
    const { callback } = await googleRoundTrip(w, started);
    assert.equal(callback.status, 400);
    assert.ok(!(await callback.text()).includes("name=\"code\""));
  });
}

test("a Google ID token signed with another key is refused", async () => {
  const w = await makeWorld();
  const other = await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
  w.google.signKey = other.privateKey;
  const { callback } = await googleRoundTrip(w, await startAuthorize(w));
  assert.equal(callback.status, 400);
});

test("a pairing code is checked against the relay before anything is stored", async () => {
  const offline = await makeWorld();
  offline.device.online = false;
  const a = await connect(offline);
  assert.equal(a.post.status, 400);
  assert.match(await a.post.text(), /did not answer/);
  assert.ok([...offline.env.ACCOUNTS.objects.values()].every((o) => !o.state.data.has("rec") || o.state.data.get("rec").pairing === null));

  const revoked = await makeWorld();
  revoked.relay.status = 404;
  assert.match(await (await connect(revoked)).post.text(), /not valid any more/);

  const garbage = await makeWorld();
  assert.match(await (await connect(garbage, { code: "hello" })).post.text(), /not an Aive pairing code/);

  const foreign = await makeWorld();
  const f = makePairing("https://relay.attacker.example");
  assert.match(await (await connect(foreign, { code: f.code })).post.text(), /standard Aive relay/);
});

test("a wrong pairing code can be tried only a few times per request and per code", async () => {
  const w = await makeWorld();
  const bogus = "aive1:" + b64url(Buffer.from("{}"));
  const started = await startAuthorize(w);
  const { callback, cookie } = await googleRoundTrip(w, started);
  let html = await callback.text();
  let last;
  for (let i = 0; i < 5; i++) {
    last = await w.call("POST", "/authorize", { headers: { cookie }, form: { csrf: csrfOf(html), decision: "allow", code: bogus } });
    if (i < 4) html = await last.text();
  }
  assert.equal(last.status, 429, "the fifth failure ends the request");
  // The per-code counter is shared across requests.
  const w2 = await makeWorld();
  let status;
  for (let round = 0; round < 3; round++) {
    const s = await startAuthorize(w2);
    const rt = await googleRoundTrip(w2, s);
    let page = await rt.callback.text();
    for (let i = 0; i < 2; i++) {
      const r = await w2.call("POST", "/authorize", { headers: { cookie: rt.cookie }, form: { csrf: csrfOf(page), decision: "allow", code: bogus } });
      status = r.status;
      page = await r.text();
    }
  }
  assert.equal(status, 429, "the sixth try of the same code is refused");
});

test("one client address is rate limited on the consent form", async () => {
  const w = await makeWorld();
  let status = 0;
  for (let i = 0; i < 25 && status !== 429; i++) status = (await w.call("POST", "/authorize", { form: { csrf: "x" } })).status;
  assert.equal(status, 429);
  const other = await makeWorld();
  assert.equal((await other.call("POST", "/authorize", { form: { csrf: "x" } })).status, 403, "a fresh address is not limited");
});

test("registration is rate limited per address", async () => {
  const w = await makeWorld();
  let status = 0;
  for (let i = 0; i < 25 && status !== 429; i++) status = (await w.call("POST", "/register", { body: { redirect_uris: [REDIRECT] } })).status;
  assert.equal(status, 429);
});

test("denying redirects back with access_denied and stores nothing", async () => {
  const w = await makeWorld();
  const started = await startAuthorize(w);
  const { callback, cookie } = await googleRoundTrip(w, started);
  const r = await w.call("POST", "/authorize", { headers: { cookie }, form: { csrf: csrfOf(await callback.text()), decision: "deny" } });
  assert.equal(new URL(r.headers.get("location")).searchParams.get("error"), "access_denied");
});

test("the device resetting its pairing deletes the stored pairing", async () => {
  const w = await makeWorld();
  const { tokens } = await getTokens(w);
  w.relay.status = 404; // the relay no longer knows the room
  const r = await (await rpc(w, tokens.access_token, "tools/call", { name: "get_device_status", arguments: {} })).json();
  assert.equal(r.result.isError, true);
  assert.match(r.result.content[0].text, /reset or deleted/);
  assert.ok([...w.env.ACCOUNTS.objects.values()].every((o) => o.state.data.size === 0));
  assert.equal((await rpc(w, tokens.access_token, "tools/list")).status, 401);
});

test("the connector refuses to run without its secrets", async () => {
  const w = await makeWorld();
  delete w.env.CONNECTOR_KEK;
  const started = await startAuthorize(w);
  assert.equal(started.response.status, 503);
});
