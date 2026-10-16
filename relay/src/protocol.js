/**
 * Pure helpers of the relay, kept apart from the Workers runtime so they can be tested with plain
 * Node (`npm test`). See worker.js and the "Relay" section of docs/Remote-API.md.
 */

/** Room ids are chosen by The Aive when it pairs. */
export const ROOM_ID = /^[A-Za-z0-9_-]{16,64}$/;

/** Client ids are minted by the relay (crypto.randomUUID) and are the only valid `to` targets. */
export const CLIENT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** Upper bound on one frame, in UTF-8 bytes. */
export const MAX_FRAME_BYTES = 1_000_000;

/** IDEaz instances that may be connected to one room at once. */
export const MAX_CLIENTS_PER_ROOM = 8;

/** A room whose host has not connected for this long is deleted. */
export const ROOM_TTL_MILLIS = 30 * 24 * 60 * 60 * 1000;

/**
 * Tokens are base64url (no padding) of at least 32 random bytes, so 43 characters or more. The
 * relay cannot prove randomness; it rejects anything shorter and strings with too few distinct
 * characters, which catches placeholders such as 43 "a"s.
 */
const TOKEN = /^[A-Za-z0-9_-]{43,128}$/;
const MIN_DISTINCT_TOKEN_CHARS = 16;

export function isStrongToken(token) {
  return typeof token === "string" && TOKEN.test(token) && new Set(token).size >= MIN_DISTINCT_TOKEN_CHARS;
}

/** The bearer token of [request], or "" when there is none. */
export function bearerToken(request) {
  const header = request.headers.get("Authorization") || "";
  const match = header.match(/^Bearer\s+(\S+)$/i);
  return match ? match[1] : "";
}

export async function sha256Hex(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Compares two equal-length hex digests without an early exit. */
export function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export function utf8Length(text) {
  return new TextEncoder().encode(text).length;
}

/**
 * Where a frame from a socket tagged [senderTags] goes. Returns null to drop it, or
 * {to: "host"} / {to: <clientId>} with the frame to deliver. Frames are opaque: only `data`
 * (and, from the host, `to`) is read.
 */
export function routeFrame(senderTags, text) {
  let frame;
  try {
    frame = JSON.parse(text);
  } catch {
    return null;
  }
  if (frame === null || typeof frame !== "object" || typeof frame.data !== "string") return null;
  if (senderTags[0] === "host") {
    if (typeof frame.to !== "string" || !CLIENT_ID.test(frame.to)) return null;
    return { to: frame.to, frame: { data: frame.data } };
  }
  if (senderTags[0] === "client" && CLIENT_ID.test(senderTags[1] || "")) {
    return { to: "host", frame: { from: senderTags[1], data: frame.data } };
  }
  return null;
}
