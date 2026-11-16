/**
 * "Sign in with Google" for the human only: authorization-code flow with PKCE, state and nonce,
 * scopes `openid email`. The ID token is verified here (RS256 signature against Google's JWKS,
 * iss, aud, exp, nonce, email_verified). Only `sub` and `email` are kept; the Google access token
 * is never used or stored.
 */
import { b64urlDecode, fromUtf8, utf8 } from "./util.js";

export const GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
export const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
export const GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const ISSUERS = ["https://accounts.google.com", "accounts.google.com"];
const CLOCK_SKEW_S = 60;
const JWKS_TTL_MS = 60 * 60 * 1000;

export class GoogleError extends Error {}

export function googleAuthUrl({ clientId, redirectUri, state, nonce, challenge }) {
  const url = new URL(GOOGLE_AUTH_URL);
  const q = url.searchParams;
  q.set("client_id", clientId);
  q.set("redirect_uri", redirectUri);
  q.set("response_type", "code");
  q.set("scope", "openid email");
  q.set("state", state);
  q.set("nonce", nonce);
  q.set("code_challenge", challenge);
  q.set("code_challenge_method", "S256");
  q.set("prompt", "select_account");
  return url.toString();
}

/** Exchanges the Google authorization [code]; returns the raw ID token string. */
export async function exchangeCode({ fetchImpl, clientId, clientSecret, redirectUri, code, verifier }) {
  const body = new URLSearchParams({
    grant_type: "authorization_code",
    code,
    client_id: clientId,
    client_secret: clientSecret,
    redirect_uri: redirectUri,
    code_verifier: verifier,
  });
  let response;
  try {
    response = await fetchImpl(GOOGLE_TOKEN_URL, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    });
  } catch {
    throw new GoogleError("Google could not be reached.");
  }
  if (!response.ok) throw new GoogleError("Google did not accept the sign-in.");
  let data;
  try {
    data = await response.json();
  } catch {
    throw new GoogleError("Google sent an unreadable answer.");
  }
  if (!data || typeof data.id_token !== "string") throw new GoogleError("Google did not return an ID token.");
  return data.id_token;
}

const jwksCache = { keys: null, at: 0, imported: new Map() };

/** Clears the cached keys (tests). */
export function resetJwksCache() {
  jwksCache.keys = null;
  jwksCache.at = 0;
  jwksCache.imported.clear();
}

async function loadKeys(fetchImpl, now, force) {
  if (!force && jwksCache.keys && now - jwksCache.at < JWKS_TTL_MS) return jwksCache.keys;
  let response;
  try {
    response = await fetchImpl(GOOGLE_JWKS_URL);
  } catch {
    throw new GoogleError("Google's signing keys could not be fetched.");
  }
  if (!response.ok) throw new GoogleError("Google's signing keys could not be fetched.");
  const data = await response.json();
  if (!data || !Array.isArray(data.keys)) throw new GoogleError("Google's signing keys were unreadable.");
  jwksCache.keys = data.keys;
  jwksCache.at = now;
  jwksCache.imported.clear();
  return data.keys;
}

async function keyFor(kid, fetchImpl, now) {
  for (const force of [false, true]) {
    const keys = await loadKeys(fetchImpl, now, force);
    const jwk = keys.find((k) => k && k.kid === kid && k.kty === "RSA");
    if (!jwk) continue;
    if (!jwksCache.imported.has(kid)) {
      jwksCache.imported.set(
        kid,
        await crypto.subtle.importKey("jwk", { kty: "RSA", n: jwk.n, e: jwk.e, alg: "RS256", ext: true }, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]),
      );
    }
    return jwksCache.imported.get(kid);
  }
  return null;
}

/**
 * Verifies [idToken] and returns {sub, email}. Throws GoogleError for anything wrong, with a
 * message safe to show.
 */
export async function verifyIdToken(idToken, { fetchImpl, clientId, nonce, now = Date.now() }) {
  const bad = () => new GoogleError("The Google sign-in could not be verified.");
  const parts = typeof idToken === "string" ? idToken.split(".") : [];
  if (parts.length !== 3) throw bad();
  let header;
  let claims;
  let signature;
  try {
    header = JSON.parse(fromUtf8(b64urlDecode(parts[0])));
    claims = JSON.parse(fromUtf8(b64urlDecode(parts[1])));
    signature = b64urlDecode(parts[2]);
  } catch {
    throw bad();
  }
  if (!header || !claims || typeof claims !== "object" || header.alg !== "RS256" || typeof header.kid !== "string") throw bad();
  const key = await keyFor(header.kid, fetchImpl, now);
  if (!key) throw bad();
  const ok = await crypto.subtle.verify({ name: "RSASSA-PKCS1-v1_5" }, key, signature, utf8(`${parts[0]}.${parts[1]}`));
  if (!ok) throw bad();
  const nowS = Math.floor(now / 1000);
  const audOk = Array.isArray(claims.aud) ? claims.aud.includes(clientId) : claims.aud === clientId;
  if (!claims || !ISSUERS.includes(claims.iss) || !audOk || !clientId) throw bad();
  if (typeof claims.exp !== "number" || claims.exp + CLOCK_SKEW_S < nowS) throw bad();
  if (typeof claims.iat === "number" && claims.iat - CLOCK_SKEW_S > nowS) throw bad();
  if (typeof nonce !== "string" || claims.nonce !== nonce) throw bad();
  if (claims.email_verified !== true && claims.email_verified !== "true") {
    throw new GoogleError("That Google account's email address is not verified, so it cannot be used here.");
  }
  if (typeof claims.sub !== "string" || !claims.sub || claims.sub.length > 255 || typeof claims.email !== "string" || !claims.email || claims.email.length > 320) {
    throw bad();
  }
  return { sub: claims.sub, email: claims.email };
}
