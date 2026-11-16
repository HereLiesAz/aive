/**
 * Durable Objects. Everything secret is stored hashed (tokens, codes, nonces) or encrypted with
 * AES-GCM under the Worker secret CONNECTOR_KEK (the pairing). Nothing here is ever logged.
 *
 * - Account  one per Google account (keyed by a hash of the Google `sub`): email, the encrypted
 *            pairing, pending authorization codes and the OAuth token families.
 * - Txn      one per authorization request in flight (before and during the Google sign-in).
 * - Registry dynamic client registrations (RFC 7591).
 * - Limiter  fixed-window counters, one object per key (client address, pairing code).
 */
import { b64urlDecode, b64urlEncode, fromUtf8, json, randomBytes, randomToken, sha256B64url, timingSafeEqual, utf8 } from "./util.js";

export const CODE_TTL_MS = 10 * 60 * 1000;
export const ACCESS_TTL_MS = 60 * 60 * 1000;
export const REFRESH_TTL_MS = 30 * 24 * 60 * 60 * 1000;
export const TXN_TTL_MS = 10 * 60 * 1000;
const MAX_FAMILIES = 10;
const MAX_ATTEMPTS = 5;
const REFRESH_REUSE_GRACE_MS = 60 * 1000;
const CLIENT_TTL_MS = 90 * 24 * 60 * 60 * 1000;

/** Runs one handler at a time per object, so read-modify-write cannot interleave across awaits. */
class Serial {
  constructor() {
    this.tail = Promise.resolve();
  }
  run(fn) {
    const next = this.tail.then(fn, fn);
    this.tail = next.catch(() => {});
    return next;
  }
}

async function body(request) {
  try {
    return await request.json();
  } catch {
    return {};
  }
}

async function kekKey(env) {
  let raw;
  try {
    raw = b64urlDecode(env.CONNECTOR_KEK || "");
  } catch {
    raw = new Uint8Array(0);
  }
  if (raw.length !== 32) throw new Error("CONNECTOR_KEK is not set to 32 random bytes (base64url)");
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

const pairingAad = (acct) => utf8(`aive-connector/pairing/1|${acct}`);

async function encryptPairing(env, acct, pairing) {
  const iv = randomBytes(12);
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: pairingAad(acct) }, await kekKey(env), utf8(JSON.stringify(pairing)));
  return { iv: b64urlEncode(iv), ct: b64urlEncode(new Uint8Array(ct)) };
}

async function decryptPairing(env, acct, sealed) {
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: b64urlDecode(sealed.iv), additionalData: pairingAad(acct) },
    await kekKey(env),
    b64urlDecode(sealed.ct),
  );
  return JSON.parse(fromUtf8(new Uint8Array(plain)));
}

const fail = (error, status = 400) => json({ error }, status);

export class Account {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.serial = new Serial();
  }

  now() {
    return this.env.clock ? this.env.clock() : Date.now();
  }

  async load() {
    return (await this.state.storage.get("rec")) || null;
  }

  async save(rec) {
    await this.state.storage.put("rec", rec);
    // The object lives while something in it is live; the alarm deletes it afterwards.
    const now = this.now();
    let latest = now + 60_000;
    for (const c of Object.values(rec.codes)) latest = Math.max(latest, c.exp);
    for (const f of Object.values(rec.families)) latest = Math.max(latest, f.refreshExp);
    await this.state.storage.setAlarm(latest + 1000);
  }

  async alarm() {
    return this.serial.run(async () => {
      const rec = await this.load();
      if (!rec) return;
      const now = this.now();
      const live =
        Object.values(rec.codes).some((c) => c.exp > now) || Object.values(rec.families).some((f) => f.refreshExp > now);
      if (live) await this.save(rec);
      else await this.state.storage.deleteAll();
    });
  }

  fetch(request) {
    return this.serial.run(async () => {
      const op = new URL(request.url).pathname;
      const input = await body(request);
      try {
        return await this.handle(op, input);
      } catch (e) {
        if (String(e && e.message).includes("CONNECTOR_KEK")) return fail("server_misconfigured", 500);
        return fail("server_error", 500);
      }
    });
  }

  async handle(op, input) {
    const now = this.now();
    let rec = await this.load();
    switch (op) {
      case "/identify": {
        // Called after a verified Google sign-in. Only sub and email are kept.
        rec = rec || { acct: input.acct, sub: input.sub, email: input.email, pairing: null, codes: {}, families: {} };
        rec.email = input.email;
        await this.save(rec);
        return json({ ok: true, hasPairing: !!rec.pairing });
      }
      case "/pair": {
        if (!rec) return fail("no_account");
        rec.pairing = await encryptPairing(this.env, rec.acct, input.pairing);
        await this.save(rec);
        return json({ ok: true });
      }
      case "/issue-code": {
        if (!rec || !rec.pairing) return fail("no_pairing");
        const secret = randomToken(32);
        rec.codes[await sha256B64url(secret)] = {
          clientId: input.clientId,
          redirectUri: input.redirectUri,
          challenge: input.challenge,
          exp: now + CODE_TTL_MS,
        };
        for (const [h, c] of Object.entries(rec.codes)) if (c.exp < now) delete rec.codes[h];
        await this.save(rec);
        return json({ ok: true, code: `${rec.acct}.${secret}` });
      }
      case "/exchange": {
        if (!rec) return fail("invalid_grant");
        const hash = await sha256B64url(String(input.secret));
        const used = rec.usedCodes || [];
        if (used.includes(hash)) {
          // A code was presented twice: treat every token minted so far as compromised.
          rec.families = {};
          await this.save(rec);
          return fail("invalid_grant");
        }
        const code = rec.codes[hash];
        if (!code) return fail("invalid_grant");
        delete rec.codes[hash];
        rec.usedCodes = [...used, hash].slice(-20);
        const verifierOk =
          typeof input.verifier === "string" &&
          /^[A-Za-z0-9\-._~]{43,128}$/.test(input.verifier) &&
          timingSafeEqual(await sha256B64url(input.verifier), code.challenge);
        if (code.exp < now || code.clientId !== input.clientId || code.redirectUri !== input.redirectUri || !verifierOk) {
          await this.save(rec);
          return fail("invalid_grant");
        }
        return this.mint(rec, code.clientId, now);
      }
      case "/refresh": {
        if (!rec) return fail("invalid_grant");
        const family = rec.families[input.familyId];
        if (!family || family.clientId !== input.clientId) return fail("invalid_grant");
        const hash = await sha256B64url(String(input.secret));
        if (family.prevRefreshHash && timingSafeEqual(hash, family.prevRefreshHash)) {
          // An already-rotated refresh token came back. Soon after rotation it is probably a
          // racing retry; later it is probably theft, so the whole family is revoked.
          if (now - family.rotatedAt > REFRESH_REUSE_GRACE_MS) {
            delete rec.families[input.familyId];
            await this.save(rec);
          }
          return fail("invalid_grant");
        }
        if (!timingSafeEqual(hash, family.refreshHash) || family.refreshExp < now) return fail("invalid_grant");
        return this.rotate(rec, input.familyId, family, now);
      }
      case "/use": {
        if (!rec || !rec.pairing) return fail("invalid_token", 401);
        const family = rec.families[input.familyId];
        if (!family) return fail("invalid_token", 401);
        const hash = await sha256B64url(String(input.secret));
        if (!timingSafeEqual(hash, family.accessHash) || family.accessExp < now) return fail("invalid_token", 401);
        return json({ ok: true, pairing: await decryptPairing(this.env, rec.acct, rec.pairing) });
      }
      case "/revoke": {
        // Possession of either token of a family ends the connection and deletes the pairing.
        if (!rec) return json({ ok: true });
        const family = rec.families[input.familyId];
        if (!family) return json({ ok: true });
        const hash = await sha256B64url(String(input.secret));
        if (timingSafeEqual(hash, family.accessHash) || timingSafeEqual(hash, family.refreshHash)) {
          await this.state.storage.deleteAll();
          await this.state.storage.deleteAlarm();
        }
        return json({ ok: true });
      }
      case "/purge": {
        // The device reset or deleted its pairing: forget it, and every token with it.
        await this.state.storage.deleteAll();
        await this.state.storage.deleteAlarm();
        return json({ ok: true });
      }
      default:
        return fail("not_found", 404);
    }
  }

  async mint(rec, clientId, now) {
    const familyId = randomToken(12);
    const family = { clientId, createdAt: now };
    const ids = Object.keys(rec.families);
    if (ids.length >= MAX_FAMILIES) {
      ids.sort((a, b) => rec.families[a].createdAt - rec.families[b].createdAt);
      delete rec.families[ids[0]];
    }
    rec.families[familyId] = family;
    return this.rotate(rec, familyId, family, now, true);
  }

  async rotate(rec, familyId, family, now, first = false) {
    const access = randomToken(32);
    const refresh = randomToken(32);
    if (!first) {
      family.prevRefreshHash = family.refreshHash;
      family.rotatedAt = now;
    }
    family.accessHash = await sha256B64url(access);
    family.accessExp = now + ACCESS_TTL_MS;
    family.refreshHash = await sha256B64url(refresh);
    family.refreshExp = now + REFRESH_TTL_MS;
    await this.save(rec);
    return json({
      ok: true,
      access_token: `${rec.acct}.${familyId}.${access}`,
      refresh_token: `${rec.acct}.${familyId}.${refresh}`,
      expires_in: ACCESS_TTL_MS / 1000,
    });
  }
}

/** An authorization request while the person signs in with Google and confirms. */
export class Txn {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.serial = new Serial();
  }

  now() {
    return this.env.clock ? this.env.clock() : Date.now();
  }

  alarm() {
    return this.state.storage.deleteAll();
  }

  fetch(request) {
    return this.serial.run(async () => {
      const op = new URL(request.url).pathname;
      const input = await body(request);
      const now = this.now();
      let rec = await this.state.storage.get("rec");
      if (op === "/create") {
        await this.state.storage.put("rec", { ...input.rec, exp: now + TXN_TTL_MS, attempts: 0, stage: "google" });
        await this.state.storage.setAlarm(now + TXN_TTL_MS + 5000);
        return json({ ok: true });
      }
      if (!rec || rec.exp < now) return fail("expired");
      const browserOk = async () => timingSafeEqual(await sha256B64url(String(input.browserSecret)), rec.browserHash);
      switch (op) {
        case "/google": {
          // The Google redirect: state is single use and must come from the same browser.
          if (rec.stage !== "google" || !(await browserOk()) || !timingSafeEqual(await sha256B64url(String(input.stateSecret)), rec.stateHash)) {
            return fail("bad_state");
          }
          rec.stage = "exchanging";
          await this.state.storage.put("rec", rec);
          return json({ ok: true, verifier: rec.googleVerifier, nonce: rec.nonce, request: rec.request });
        }
        case "/identify": {
          if (rec.stage !== "exchanging") return fail("bad_state");
          Object.assign(rec, { stage: "signed_in", sub: input.sub, email: input.email, acct: input.acct, csrfHash: input.csrfHash, googleVerifier: undefined, nonce: undefined });
          await this.state.storage.put("rec", rec);
          return json({ ok: true });
        }
        case "/check": {
          // The consent form: needs the same browser and the per-request nonce.
          if (rec.stage !== "signed_in" || !(await browserOk()) || !timingSafeEqual(await sha256B64url(String(input.csrf)), rec.csrfHash)) {
            return fail("csrf", 403);
          }
          return json({ ok: true, request: rec.request, email: rec.email, acct: rec.acct, sub: rec.sub });
        }
        case "/failed": {
          // A wrong pairing code: new nonce, and only a few tries per request.
          rec.attempts += 1;
          if (rec.attempts >= MAX_ATTEMPTS) {
            await this.state.storage.deleteAll();
            return fail("too_many");
          }
          rec.csrfHash = input.csrfHash;
          await this.state.storage.put("rec", rec);
          return json({ ok: true });
        }
        case "/rotate": {
          rec.csrfHash = input.csrfHash;
          await this.state.storage.put("rec", rec);
          return json({ ok: true });
        }
        case "/delete":
          await this.state.storage.deleteAll();
          await this.state.storage.deleteAlarm();
          return json({ ok: true });
        default:
          return fail("not_found", 404);
      }
    });
  }
}

export class Registry {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const op = new URL(request.url).pathname;
    const input = await body(request);
    const now = this.env.clock ? this.env.clock() : Date.now();
    if (op === "/register") {
      await this.state.storage.put(`c:${input.id}`, { ...input.client, exp: now + CLIENT_TTL_MS });
      return json({ ok: true });
    }
    if (op === "/get") {
      const client = await this.state.storage.get(`c:${input.id}`);
      return client && client.exp > now ? json({ ok: true, client }) : fail("unknown_client", 404);
    }
    return fail("not_found", 404);
  }
}

export class Limiter {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const input = await body(request);
    const now = this.env.clock ? this.env.clock() : Date.now();
    let w = await this.state.storage.get("w");
    if (!w || now - w.start >= input.windowMs) w = { start: now, count: 0 };
    if (w.count >= input.max) {
      return json({ allowed: false, retryAfter: Math.ceil((w.start + input.windowMs - now) / 1000) });
    }
    w.count += 1;
    await this.state.storage.put("w", w);
    await this.state.storage.setAlarm(w.start + input.windowMs + 1000);
    return json({ allowed: true });
  }

  alarm() {
    return this.state.storage.deleteAll();
  }
}
