(function (global) {
    "use strict";

    const crypto = global.crypto;
    const ready = !!(crypto && crypto.subtle && crypto.getRandomValues);
    const keyCache = new Map();

    function fromBase64Url(value) {
        const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
        const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
        const binary = global.atob(padded);
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index += 1) {
            bytes[index] = binary.charCodeAt(index);
        }
        return bytes;
    }

    function toBase64Url(bytes) {
        let binary = "";
        const chunk = 0x8000;
        for (let offset = 0; offset < bytes.length; offset += chunk) {
            binary += String.fromCharCode.apply(
                null,
                bytes.subarray(offset, Math.min(offset + chunk, bytes.length)),
            );
        }
        return global.btoa(binary).replace(/\+/g, "-").replace(/\//g, "_");
    }

    async function keyFor(keyBase64) {
        let imported = keyCache.get(keyBase64);
        if (imported) return imported;
        const bytes = fromBase64Url(keyBase64);
        if (bytes.length !== 32) {
            throw new Error("Haive mesh room key must be exactly 256 bits.");
        }
        imported = await crypto.subtle.importKey(
            "raw",
            bytes,
            { name: "AES-GCM" },
            false,
            ["encrypt", "decrypt"],
        );
        keyCache.set(keyBase64, imported);
        return imported;
    }

    function randomBase64(size) {
        if (!ready) throw new Error("Web Crypto is unavailable.");
        if (!Number.isInteger(size) || size <= 0) throw new Error("Random byte count must be positive.");
        const bytes = new Uint8Array(size);
        crypto.getRandomValues(bytes);
        return toBase64Url(bytes);
    }

    async function sealBase64(keyBase64, plaintextBase64, associatedDataBase64) {
        if (!ready) throw new Error("Web Crypto is unavailable.");
        const nonce = new Uint8Array(12);
        crypto.getRandomValues(nonce);
        const ciphertext = await crypto.subtle.encrypt(
            {
                name: "AES-GCM",
                iv: nonce,
                additionalData: fromBase64Url(associatedDataBase64),
                tagLength: 128,
            },
            await keyFor(keyBase64),
            fromBase64Url(plaintextBase64),
        );
        return toBase64Url(nonce) + "." + toBase64Url(new Uint8Array(ciphertext));
    }

    async function openBase64(
        keyBase64,
        nonceBase64,
        ciphertextBase64,
        associatedDataBase64,
    ) {
        if (!ready) throw new Error("Web Crypto is unavailable.");
        const nonce = fromBase64Url(nonceBase64);
        if (nonce.length !== 12) throw new Error("Invalid Haive mesh AES-GCM nonce.");
        const plaintext = await crypto.subtle.decrypt(
            {
                name: "AES-GCM",
                iv: nonce,
                additionalData: fromBase64Url(associatedDataBase64),
                tagLength: 128,
            },
            await keyFor(keyBase64),
            fromBase64Url(ciphertextBase64),
        );
        return toBase64Url(new Uint8Array(plaintext));
    }

    global.HaiveMeshCrypto = Object.freeze({
        ready,
        randomBase64,
        sealBase64,
        openBase64,
    });
})(globalThis);
