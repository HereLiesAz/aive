// Encrypts web credentials at rest. The AES-GCM key is generated non-extractable and kept in
// IndexedDB, so the ciphertext in localStorage is useless without this origin's key store. This
// protects copied storage and storage dumps; script running on the page can still decrypt.
(function (global) {
    "use strict";

    const PREFIX = "aive-vault-v1:";
    const DB_NAME = "aive-credential-vault";
    const STORE = "keys";
    const KEY_ID = "credentials";
    const crypto = global.crypto;
    const ready = !!(crypto && crypto.subtle && crypto.getRandomValues && global.indexedDB);
    let keyPromise = null;

    function request(req) {
        return new Promise((resolve, reject) => {
            req.onsuccess = () => resolve(req.result);
            req.onerror = () => reject(req.error);
        });
    }

    async function openDb() {
        const open = global.indexedDB.open(DB_NAME, 1);
        open.onupgradeneeded = () => open.result.createObjectStore(STORE);
        return request(open);
    }

    async function loadKey() {
        const db = await openDb();
        const existing = await request(db.transaction(STORE, "readonly").objectStore(STORE).get(KEY_ID));
        if (existing) return existing;
        const created = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
        await request(db.transaction(STORE, "readwrite").objectStore(STORE).put(created, KEY_ID));
        return created;
    }

    function key() {
        if (!keyPromise) keyPromise = loadKey().catch((error) => { keyPromise = null; throw error; });
        return keyPromise;
    }

    function toBase64(bytes) {
        let binary = "";
        for (let index = 0; index < bytes.length; index += 1) binary += String.fromCharCode(bytes[index]);
        return global.btoa(binary);
    }

    function fromBase64(value) {
        const binary = global.atob(value);
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
        return bytes;
    }

    async function seal(plaintext) {
        if (!ready) throw new Error("Web Crypto or IndexedDB is unavailable.");
        const nonce = new Uint8Array(12);
        crypto.getRandomValues(nonce);
        const encoded = new TextEncoder().encode(plaintext);
        const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce }, await key(), encoded));
        const combined = new Uint8Array(nonce.length + ciphertext.length);
        combined.set(nonce, 0);
        combined.set(ciphertext, nonce.length);
        return PREFIX + toBase64(combined);
    }

    async function open(stored) {
        if (!stored.startsWith(PREFIX)) return stored; // legacy plaintext; the caller re-seals it
        if (!ready) throw new Error("Web Crypto or IndexedDB is unavailable.");
        const combined = fromBase64(stored.slice(PREFIX.length));
        const plaintext = await crypto.subtle.decrypt(
            { name: "AES-GCM", iv: combined.subarray(0, 12) },
            await key(),
            combined.subarray(12),
        );
        return new TextDecoder().decode(plaintext);
    }

    function isSealed(stored) {
        return typeof stored === "string" && stored.startsWith(PREFIX);
    }

    global.AiveCredentialVault = { ready, seal, open, isSealed };
})(globalThis);
