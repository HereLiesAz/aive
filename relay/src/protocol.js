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

/** Rooms one IP address may create (or re-key) per [CREATE_WINDOW_MILLIS]. */
export const MAX_ROOM_CREATES_PER_WINDOW = 20;
export const CREATE_WINDOW_MILLIS = 60 * 60 * 1000;

/** Frames one socket may send: a burst, then a sustained rate. A sender over it is disconnected. */
export const FRAME_BURST = 40;
export const FRAME_REFILL_PER_SECOND = 10;

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

/** Token bucket for per-socket frame rate; [take] is false when the sender is over its limit. */
export class TokenBucket {
  constructor(capacity = FRAME_BURST, refillPerSecond = FRAME_REFILL_PER_SECOND, now = Date.now) {
    this.capacity = capacity;
    this.refillPerSecond = refillPerSecond;
    this.now = now;
    this.tokens = capacity;
    this.last = now();
  }

  take() {
    const t = this.now();
    this.tokens = Math.min(this.capacity, this.tokens + ((t - this.last) / 1000) * this.refillPerSecond);
    this.last = t;
    if (this.tokens < 1) return false;
    this.tokens -= 1;
    return true;
  }
}

/**
 * Fixed-window counter for room creation. [window] is the stored {start, count} (or undefined);
 * returns the window to store and whether this creation is allowed.
 */
export function countCreate(window, now, max = MAX_ROOM_CREATES_PER_WINDOW, span = CREATE_WINDOW_MILLIS) {
  const current = window && now - window.start < span ? window : { start: now, count: 0 };
  if (current.count >= max) return { window: current, allowed: false };
  return { window: { start: current.start, count: current.count + 1 }, allowed: true };
}
