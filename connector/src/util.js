/** Small shared helpers: encoding, hashing, random values, responses. Web Crypto only. */

const enc = new TextEncoder();
const dec = new TextDecoder();

export function b64urlEncode(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Decodes base64url (padding optional); throws on anything that is not base64url. */
export function b64urlDecode(text) {
  if (typeof text !== "string" || !/^[A-Za-z0-9_-]*$/.test(text) || text.length % 4 === 1) {
    throw new Error("not base64url");
  }
  const padded = text.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((text.length + 3) % 4);
  const bin = atob(padded);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export const utf8 = (text) => enc.encode(text);
export const fromUtf8 = (bytes) => dec.decode(bytes);
export const utf8Length = (text) => enc.encode(text).length;

export function randomBytes(n) {
  return crypto.getRandomValues(new Uint8Array(n));
}

/** A random token of [bytes] bytes, base64url. 32 bytes is 256 bits. */
export const randomToken = (bytes = 32) => b64urlEncode(randomBytes(bytes));

export async function sha256(bytesOrText) {
  const data = typeof bytesOrText === "string" ? enc.encode(bytesOrText) : bytesOrText;
  return new Uint8Array(await crypto.subtle.digest("SHA-256", data));
}

export async function sha256Hex(text) {
  return [...(await sha256(text))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function sha256B64url(text) {
  return b64urlEncode(await sha256(text));
}

/** Compares two strings without an early exit on the first difference. */
export function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const len = Math.max(a.length, b.length);
  let diff = a.length ^ b.length;
  for (let i = 0; i < len; i++) diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return diff === 0;
}

export function json(body, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
  });
}

export function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

/** Cuts [text] to at most [maxBytes] UTF-8 bytes without splitting a character. */
export function truncateUtf8(text, maxBytes) {
  const bytes = enc.encode(text);
  if (bytes.length <= maxBytes) return { text, truncated: false };
  let end = maxBytes;
  while (end > 0 && (bytes[end] & 0xc0) === 0x80) end--;
  return { text: dec.decode(bytes.subarray(0, end)), truncated: true };
}

export function readCookie(request, name) {
  const header = request.headers.get("cookie") || "";
  for (const part of header.split(";")) {
    const i = part.indexOf("=");
    if (i > 0 && part.slice(0, i).trim() === name) return part.slice(i + 1).trim();
  }
  return "";
}

export function clientIp(request) {
  return request.headers.get("cf-connecting-ip") || "unknown";
}
