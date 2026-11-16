/**
 * The client end of the sealed relay protocol (docs/Remote-API.md, "Sealed frames and messages"),
 * plus the pairing-code reader. Reference implementation on the device:
 * androidApp/.../remote/RelaySealedFrames.kt and RemoteRelayPairing.kt.
 */
import { b64urlDecode, b64urlEncode, fromUtf8, randomBytes, utf8 } from "./util.js";

export const CODE_PREFIX = "aive1:";
export const MAX_PLAINTEXT_BYTES = 600_000;
export const MAX_FRAME_BYTES = 1_000_000;
export const MAX_CLOCK_SKEW_MS = 10 * 60 * 1000;
const NONCE_BYTES = 12;
const TAG_BYTES = 16;
const ROOM_ID = /^[A-Za-z0-9_-]{16,64}$/;
const TOKEN = /^[A-Za-z0-9_-]{43,128}$/;

export class PairingCodeError extends Error {}

/**
 * Reads an `aive1:` pairing code. Returns {relay, room, clientToken, key} (key as base64url) or
 * throws PairingCodeError with a plain-language message. Never includes the code in the message.
 */
export function parsePairingCode(code) {
  const text = typeof code === "string" ? code.trim() : "";
  if (!text.startsWith(CODE_PREFIX) || text.length > 4096) throw new PairingCodeError("That is not an Aive pairing code. It starts with aive1:.");
  let body;
  try {
    body = JSON.parse(fromUtf8(b64urlDecode(text.slice(CODE_PREFIX.length))));
  } catch {
    throw new PairingCodeError("That pairing code is damaged. Copy it again from Settings > Remote Access in The Aive.");
  }
  const str = (v) => (typeof v === "string" ? v : "");
  if (!body || body.v !== 1) throw new PairingCodeError("That pairing code is from a version this connector does not understand.");
  let keyBytes;
  try {
    keyBytes = b64urlDecode(str(body.key));
  } catch {
    keyBytes = new Uint8Array(0);
  }
  if (!ROOM_ID.test(str(body.room)) || !TOKEN.test(str(body.clientToken)) || keyBytes.length !== 32 || !str(body.relay)) {
    throw new PairingCodeError("That pairing code is damaged. Copy it again from Settings > Remote Access in The Aive.");
  }
  return { relay: body.relay, room: body.room, clientToken: body.clientToken, key: b64urlEncode(keyBytes) };
}

/**
 * The relay origin to use, or null when it is not allowed. Only https origins on [allowed] (the
 * configured relay plus any extra ones the operator listed) are used, so a pairing code cannot
 * point this Worker at an arbitrary address.
 */
export function allowedRelayOrigin(relay, allowed) {
  let url;
  try {
    url = new URL(relay);
  } catch {
    return null;
  }
  if (url.protocol !== "https:" || url.username || url.password || url.pathname !== "/" || url.search || url.hash) return null;
  return allowed.includes(url.origin) ? url.origin : null;
}

const aad = (room, dir, seq) => utf8(`aive-relay/1|${room}|${dir}|${seq}`);

async function aesKey(keyB64) {
  return crypto.subtle.importKey("raw", b64urlDecode(keyB64), "AES-GCM", false, ["encrypt", "decrypt"]);
}

/** `<seq>.<base64url(nonce || ciphertext || tag)>` */
export async function sealFrame(keyB64, room, dir, seq, plaintextBytes) {
  if (plaintextBytes.length > MAX_PLAINTEXT_BYTES) throw new Error("plaintext too large");
  const nonce = randomBytes(NONCE_BYTES);
  const ct = new Uint8Array(
    await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce, additionalData: aad(room, dir, seq), tagLength: 128 }, await aesKey(keyB64), plaintextBytes),
  );
  const out = new Uint8Array(nonce.length + ct.length);
  out.set(nonce);
  out.set(ct, nonce.length);
  return `${seq}.${b64urlEncode(out)}`;
}

/** Opens a frame; returns {seq, plaintext} or null for anything that is not authentic. */
export async function openFrame(keyB64, room, dir, data) {
  if (typeof data !== "string" || data.length > MAX_FRAME_BYTES) return null;
  const dot = data.indexOf(".");
  if (dot < 1 || dot > 19) return null;
  const seqText = data.slice(0, dot);
  if (!/^[0-9]+$/.test(seqText) || (seqText.length > 1 && seqText[0] === "0")) return null;
  const seq = Number(seqText);
  if (!Number.isSafeInteger(seq) || seq < 1) return null;
  let bytes;
  try {
    bytes = b64urlDecode(data.slice(dot + 1));
  } catch {
    return null;
  }
  if (bytes.length < NONCE_BYTES + TAG_BYTES || bytes.length > NONCE_BYTES + TAG_BYTES + MAX_PLAINTEXT_BYTES) return null;
  try {
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: bytes.subarray(0, NONCE_BYTES), additionalData: aad(room, dir, seq), tagLength: 128 },
      await aesKey(keyB64),
      bytes.subarray(NONCE_BYTES),
    );
    return { seq, plaintext: new Uint8Array(plain) };
  } catch {
    return null;
  }
}

/**
 * One client connection's protocol state: its own sid and sequence for sealing requests, and the
 * replay guard for replies (strictly increasing seq per host sid, timestamp within ten minutes).
 */
export class ClientSession {
  constructor({ room, key, now = Date.now }) {
    this.room = room;
    this.key = key;
    this.now = now;
    this.sid = [...randomBytes(8)].map((b) => b.toString(16).padStart(2, "0")).join("");
    this.nextSeq = 1;
    this.lastSeq = new Map();
  }

  /** Seals [message] (an object with `t` and fields) for the host. */
  async seal(message) {
    const seq = this.nextSeq++;
    const body = utf8(JSON.stringify({ ...message, sid: this.sid, seq, ts: this.now() }));
    return sealFrame(this.key, this.room, "c2h", seq, body);
  }

  /** The host's message in [data], or null when it must be ignored. */
  async unseal(data) {
    const opened = await openFrame(this.key, this.room, "h2c", data);
    if (!opened) return null;
    let message;
    try {
      message = JSON.parse(fromUtf8(opened.plaintext));
    } catch {
      return null;
    }
    if (!message || typeof message !== "object" || typeof message.sid !== "string" || !/^[0-9a-f]{16}$/.test(message.sid)) return null;
    if (message.seq !== opened.seq || typeof message.ts !== "number") return null;
    if (Math.abs(message.ts - this.now()) > MAX_CLOCK_SKEW_MS) return null;
    const last = this.lastSeq.get(message.sid);
    if (last !== undefined && message.seq <= last) return null;
    this.lastSeq.set(message.sid, message.seq);
    return message;
  }
}
