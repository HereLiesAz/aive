/**
 * Test world: in-memory Durable Objects, a fake relay with a fake Aive device behind it (it seals
 * and unseals with the real protocol code), and a fake Google (token endpoint and JWKS, signed
 * with a locally generated RSA key). Plain Node, no dependencies.
 */
import { createHash, randomBytes } from "node:crypto";

import { resetJwksCache } from "../src/google.js";
import { openFrame, sealFrame } from "../src/seal.js";
import { Account, Limiter, Registry, Txn } from "../src/store.js";
import { createWorker } from "../src/worker.js";
import { b64urlEncode, fromUtf8, utf8 } from "../src/util.js";

export const BASE = "https://connector.test";
export const RELAY = "https://aive-relay.hereliesaz.workers.dev";
export const GOOGLE_ID = "client-id.apps.googleusercontent.com";
export const REDIRECT = "https://claude.ai/api/mcp/auth_callback";

class FakeState {
  constructor() {
    this.data = new Map();
    this.alarmAt = null;
    const s = this;
    this.storage = {
      async get(k) {
        return s.data.has(k) ? structuredClone(s.data.get(k)) : undefined;
      },
      async put(k, v) {
        s.data.set(k, structuredClone(v));
      },
      async deleteAll() {
        s.data.clear();
      },
      async setAlarm(at) {
        s.alarmAt = at;
      },
      async deleteAlarm() {
        s.alarmAt = null;
      },
    };
  }
}

class FakeNamespace {
  constructor(Cls, env) {
    this.Cls = Cls;
    this.env = env;
    this.objects = new Map();
  }
  idFromName(name) {
    return name;
  }
  get(id) {
    if (!this.objects.has(id)) {
      const state = new FakeState();
      this.objects.set(id, { state, obj: new this.Cls(state, this.env) });
    }
    const { obj } = this.objects.get(id);
    return { fetch: (request) => obj.fetch(request) };
  }
}

export const b64url = (bytes) => Buffer.from(bytes).toString("base64url");
export const challengeOf = (verifier) => createHash("sha256").update(verifier).digest("base64url");

export function makePairing(relay = RELAY) {
  const pairing = {
    relay,
    room: b64url(randomBytes(16)),
    clientToken: b64url(randomBytes(32)),
    key: b64url(randomBytes(32)),
  };
  const code = "aive1:" + b64url(Buffer.from(JSON.stringify({ v: 1, ...pairing })));
  return { pairing, code };
}

/** A fake Aive device: answers ping and watch like RelayHostRouter does. */
class FakeDevice {
  constructor(pairing) {
    this.pairing = pairing;
    this.online = true;
    this.runStatus = null;
    this.events = [];
    this.launches = [];
    this.sid = randomBytes(8).toString("hex");
    this.nextSeq = 1;
    this.lastSeq = new Map();
    this.requests = [];
    this.clock = Date.now;
  }

  async reply(message) {
    const seq = this.nextSeq++;
    const body = utf8(JSON.stringify({ sid: this.sid, seq, ts: this.clock(), ...message }));
    return sealFrame(this.pairing.key, this.pairing.room, "h2c", seq, body);
  }

  /** Handles one sealed client frame; returns sealed reply frames. */
  async handle(data) {
    const opened = await openFrame(this.pairing.key, this.pairing.room, "c2h", data);
    if (!opened) return [];
    const m = JSON.parse(fromUtf8(opened.plaintext));
    if (m.seq !== opened.seq || Math.abs(m.ts - Date.now()) > 600000) return [];
    const last = this.lastSeq.get(m.sid);
    if (last !== undefined && m.seq <= last) return [];
    this.lastSeq.set(m.sid, m.seq);
    this.requests.push(m);
    const out = [];
    if (m.t === "watch") {
      if (this.runStatus) out.push(await this.reply({ t: "run_status", ...this.runStatus }));
      for (const l of this.launches) out.push(await this.reply({ t: "launch_outcome", ...l }));
      if (m.events) {
        // Like the real host: batches that stay well under the frame limit.
        let batch = [];
        let size = 0;
        for (const e of this.events) {
          const n = JSON.stringify(e).length;
          if (batch.length && size + n > 200_000) {
            out.push(await this.reply({ t: "run_events", events: batch }));
            batch = [];
            size = 0;
          }
          batch.push(e);
          size += n;
        }
        if (batch.length) out.push(await this.reply({ t: "run_events", events: batch }));
      }
    } else if (m.t === "ping") out.push(await this.reply({ t: "pong", id: m.id }));
    return out;
  }
}

class FakeWs {
  constructor(world) {
    this.world = world;
    this.listeners = { message: [], close: [], error: [] };
    this.closed = null;
  }
  addEventListener(type, fn) {
    this.listeners[type].push(fn);
  }
  accept() {
    const w = this.world;
    queueMicrotask(() => {
      this.emit("message", { data: JSON.stringify({ event: w.device.online ? "host-online" : "host-offline" }) });
      if (w.relay.closeCode) this.emit("close", { code: w.relay.closeCode });
    });
  }
  emit(type, event) {
    if (this.closed && type !== "close") return;
    for (const fn of this.listeners[type]) fn(event);
  }
  send(text) {
    const w = this.world;
    if (!w.device.online) return;
    const { data } = JSON.parse(text);
    w.relay.frames.push(data);
    // The real host handles frames one at a time, in order (RelayHostLink reads them in a loop).
    w.device.chain = (w.device.chain || Promise.resolve()).then(() => w.device.handle(data)).then((frames) => {
      if (w.relay.silent) return;
      for (const f of frames) this.emit("message", { data: JSON.stringify({ data: f }) });
    });
  }
  close(code) {
    this.closed = { code };
  }
}

export async function makeWorld({ now = Date.now } = {}) {
  const { pairing, code } = makePairing();
  const env = {
    RELAY_URL: RELAY,
    PUBLIC_BASE_URL: BASE,
    GOOGLE_CLIENT_ID: GOOGLE_ID,
    GOOGLE_CLIENT_SECRET: "google-secret",
    CONNECTOR_KEK: b64url(randomBytes(32)),
  };
  env.ACCOUNTS = new FakeNamespace(Account, env);
  env.TXNS = new FakeNamespace(Txn, env);
  env.REGISTRY = new FakeNamespace(Registry, env);
  env.LIMITER = new FakeNamespace(Limiter, env);

  const keys = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  );
  const jwk = await crypto.subtle.exportKey("jwk", keys.publicKey);
  const world = {
    env,
    pairing,
    code,
    device: new FakeDevice(pairing),
    relay: { frames: [], silent: false, closeCode: 0, status: 101, down: false },
    google: { kid: "test-kid", jwks: [{ ...jwk, kid: "test-kid", use: "sig", alg: "RS256" }], claims: {}, lastChallenge: "", lastNonce: "", tokenCalls: 0, signKey: keys.privateKey },
    ipHeader: "203.0.113.7",
  };
  world.googleToken = async (overrides = {}) => {
    const header = { alg: "RS256", kid: world.google.kid, typ: "JWT" };
    const claims = {
      iss: "https://accounts.google.com",
      aud: GOOGLE_ID,
      sub: "google-sub-1",
      email: "keeper@example.com",
      email_verified: true,
      nonce: world.google.lastNonce,
      iat: Math.floor(Date.now() / 1000),
      exp: Math.floor(Date.now() / 1000) + 3600,
      ...world.google.claims,
      ...overrides,
    };
    const signing = `${b64urlEncode(utf8(JSON.stringify(header)))}.${b64urlEncode(utf8(JSON.stringify(claims)))}`;
    const sig = new Uint8Array(await crypto.subtle.sign({ name: "RSASSA-PKCS1-v1_5" }, world.google.signKey, utf8(signing)));
    return `${signing}.${b64urlEncode(sig)}`;
  };

  const fakeFetch = async (input, init = {}) => {
    const url = new URL(typeof input === "string" ? input : input.url);
    if (url.origin === "https://oauth2.googleapis.com" && url.pathname === "/token") {
      world.google.tokenCalls++;
      const form = new URLSearchParams(init.body);
      const ok =
        form.get("client_id") === GOOGLE_ID &&
        form.get("client_secret") === "google-secret" &&
        form.get("redirect_uri") === `${BASE}/google/callback` &&
        challengeOf(form.get("code_verifier") || "") === world.google.lastChallenge;
      if (!ok) return new Response("{}", { status: 400 });
      return new Response(JSON.stringify({ id_token: await world.googleToken(), access_token: "unused" }), { status: 200 });
    }
    if (url.origin === "https://www.googleapis.com" && url.pathname === "/oauth2/v3/certs") {
      return new Response(JSON.stringify({ keys: world.google.jwks }), { status: 200 });
    }
    if (url.origin === RELAY && url.pathname === `/v1/rooms/${world.pairing.room}`) {
      if (world.relay.down) throw new Error("network down");
      const auth = init.headers?.Authorization;
      if (world.relay.status !== 101) return { status: world.relay.status };
      if (auth !== `Bearer ${world.pairing.clientToken}`) return { status: 401 };
      return { status: 101, webSocket: new FakeWs(world) };
    }
    if (url.origin === RELAY) return { status: 404 };
    throw new Error(`unexpected fetch ${url}`);
  };
  world.fetch = fakeFetch;
  world.worker = createWorker({ fetch: fakeFetch, now });
  resetJwksCache();

  world.req = (method, path, { headers = {}, body, form } = {}) => {
    const h = { "cf-connecting-ip": world.ipHeader, ...headers };
    let payload = body;
    if (form) {
      h["content-type"] = "application/x-www-form-urlencoded";
      payload = new URLSearchParams(form).toString();
    } else if (body && typeof body !== "string") {
      h["content-type"] = "application/json";
      payload = JSON.stringify(body);
    }
    return new Request(BASE + path, { method, headers: h, body: payload });
  };
  world.call = (method, path, opts) => world.worker.fetch(world.req(method, path, opts), env);
  return world;
}

export const text = (response) => response.text();

/** Registers a client and starts /authorize; returns what the browser would hold. */
export async function startAuthorize(world, { verifier = b64url(randomBytes(32)), state = "xyz", redirectUri = REDIRECT, challenge } = {}) {
  const reg = await (await world.call("POST", "/register", { body: { redirect_uris: [redirectUri], client_name: "Claude" } })).json();
  const url = new URLSearchParams({
    response_type: "code",
    client_id: reg.client_id,
    redirect_uri: redirectUri,
    state,
    code_challenge: challenge ?? challengeOf(verifier),
    code_challenge_method: "S256",
    scope: "aive:read",
  });
  const response = await world.call("GET", `/authorize?${url}`);
  return { reg, verifier, state, redirectUri, response };
}

/** Follows the redirect to Google and back; returns the page response and the cookie. */
export async function googleRoundTrip(world, started) {
  const location = new URL(started.response.headers.get("location"));
  world.google.lastChallenge = location.searchParams.get("code_challenge");
  world.google.lastNonce = location.searchParams.get("nonce");
  const cookie = started.response.headers.get("set-cookie").split(";")[0];
  const callback = await world.call("GET", `/google/callback?code=gcode&state=${encodeURIComponent(location.searchParams.get("state"))}`, { headers: { cookie } });
  return { callback, cookie, location };
}

export const csrfOf = (html) => /name="csrf" value="([^"]+)"/.exec(html)?.[1];

/** The whole happy path up to the tokens. */
export async function connect(world, { code = world.code, withPairing = true } = {}) {
  const started = await startAuthorize(world);
  const { callback, cookie } = await googleRoundTrip(world, started);
  const html = await callback.text();
  const csrf = csrfOf(html);
  const post = await world.call("POST", "/authorize", { headers: { cookie }, form: { csrf, decision: "allow", ...(withPairing ? { code } : {}) } });
  return { started, html, csrf, cookie, post };
}

export async function exchange(world, started, authCode, overrides = {}) {
  return world.call("POST", "/token", {
    form: { grant_type: "authorization_code", code: authCode, client_id: started.reg.client_id, redirect_uri: started.redirectUri, code_verifier: started.verifier, ...overrides },
  });
}

export async function getTokens(world) {
  const c = await connect(world);
  const authCode = new URL(c.post.headers.get("location")).searchParams.get("code");
  const tokens = await (await exchange(world, c.started, authCode)).json();
  return { ...c, authCode, tokens };
}

export const rpc = (world, token, method, params, id = 1, headers = {}) =>
  world.call("POST", "/mcp", { headers: { authorization: `Bearer ${token}`, "mcp-protocol-version": "2025-06-18", ...headers }, body: { jsonrpc: "2.0", id, method, params } });
