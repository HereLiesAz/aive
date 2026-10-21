// Encrypts web credentials at rest. The AES-GCM key is generated non-extractable and kept in
// IndexedDB, so the ciphertext in localStorage is useless without this origin's key store. This
// protects copied storage and storage dumps; script running on the page can still decrypt.
// Each value is sealed with its localStorage key name as AES-GCM additional data, so a ciphertext
// copied under another key (say, one credential over another) fails to open. Values sealed by the
// v1 format had no additional data; they still open and the caller re-seals them as v2.
(function (global) {
    "use strict";

    const PREFIX = "aive-vault-v2:";
    const LEGACY_PREFIX = "aive-vault-v1:";
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

    function additionalData(storageKey) {
        if (typeof storageKey !== "string" || storageKey.length === 0) throw new Error("A storage key is required.");
        return new TextEncoder().encode("aive-credential:" + storageKey);
    }

    async function seal(plaintext, storageKey) {
        if (!ready) throw new Error("Web Crypto or IndexedDB is unavailable.");
        const nonce = new Uint8Array(12);
        crypto.getRandomValues(nonce);
        const encoded = new TextEncoder().encode(plaintext);
        const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
            { name: "AES-GCM", iv: nonce, additionalData: additionalData(storageKey) },
            await key(),
            encoded,
        ));
        const combined = new Uint8Array(nonce.length + ciphertext.length);
        combined.set(nonce, 0);
        combined.set(ciphertext, nonce.length);
        return PREFIX + toBase64(combined);
    }

    async function open(stored, storageKey) {
        const legacy = stored.startsWith(LEGACY_PREFIX);
        if (!legacy && !stored.startsWith(PREFIX)) return stored; // legacy plaintext; the caller re-seals it
        if (!ready) throw new Error("Web Crypto or IndexedDB is unavailable.");
        const combined = fromBase64(stored.slice((legacy ? LEGACY_PREFIX : PREFIX).length));
        const params = { name: "AES-GCM", iv: combined.subarray(0, 12) };
        if (!legacy) params.additionalData = additionalData(storageKey);
        const plaintext = await crypto.subtle.decrypt(params, await key(), combined.subarray(12));
        return new TextDecoder().decode(plaintext);
    }

    function isSealed(stored) {
        return typeof stored === "string" && (stored.startsWith(PREFIX) || stored.startsWith(LEGACY_PREFIX));
    }

    /** Sealed without the storage key bound; re-seal after opening. */
    function isLegacySealed(stored) {
        return typeof stored === "string" && stored.startsWith(LEGACY_PREFIX);
    }

    global.AiveCredentialVault = { ready, seal, open, isSealed, isLegacySealed };
})(globalThis);
