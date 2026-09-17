(() => {
    let wasm = null;
    const encoder = new TextEncoder();

    const requireReady = () => {
        if (!wasm) throw new Error("Haive node-creature renderer is not initialized.");
        return wasm;
    };

    const writeUtf8 = (value) => {
        const exports = requireReady();
        const bytes = encoder.encode(value || "");
        if (bytes.length === 0) return { ptr: 0, len: 0 };
        const ptr = Number(exports.haive_node_alloc_bytes(bytes.length));
        if (!ptr) throw new Error("Rust node-creature renderer could not allocate UTF-8 input memory.");
        new Uint8Array(exports.memory.buffer, ptr, bytes.length).set(bytes);
        return { ptr, len: bytes.length };
    };

    const freeUtf8 = ({ ptr, len }) => {
        if (ptr && len) requireReady().haive_node_free_bytes(ptr, len);
    };

    const bytesToBase64 = (bytes) => {
        let binary = "";
        const chunkSize = 0x8000;
        for (let offset = 0; offset < bytes.length; offset += chunkSize) {
            const chunk = bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length));
            binary += String.fromCharCode(...chunk);
        }
        return btoa(binary);
    };

    globalThis.HaiveNodeCreatures = {
        ready: false,

        async init(url) {
            if (this.ready) return;
            const response = await fetch(url, { cache: "no-cache" });
            if (!response.ok) {
                throw new Error(`Could not load node-creature renderer: HTTP ${response.status}`);
            }

            let result;
            if (WebAssembly.instantiateStreaming) {
                try {
                    result = await WebAssembly.instantiateStreaming(response.clone(), {});
                } catch (_) {
                    result = await WebAssembly.instantiate(await response.arrayBuffer(), {});
                }
            } else {
                result = await WebAssembly.instantiate(await response.arrayBuffer(), {});
            }

            wasm = result.instance.exports;
            if (!wasm.memory ||
                !wasm.haive_node_packet_version ||
                !wasm.haive_node_render_packet_size ||
                !wasm.haive_node_render_packet_into ||
                !wasm.haive_node_alloc_bytes ||
                !wasm.haive_node_free_bytes) {
                wasm = null;
                throw new Error("Node-creature WASM is missing the required HNCR ABI exports.");
            }

            const version = Number(wasm.haive_node_packet_version());
            if (version !== 1) {
                wasm = null;
                throw new Error(`Unsupported node-creature packet version ${version}; expected 1.`);
            }
            this.ready = true;
        },

        renderPacketBase64(roleLabel, identitySeed, activityCode, timeSeconds, cameraYaw, cameraPitch, cameraZoom) {
            const exports = requireReady();
            const role = writeUtf8(roleLabel);
            const seed = writeUtf8(identitySeed);
            let outputPtr = 0;
            let outputSize = 0;

            try {
                outputSize = Number(exports.haive_node_render_packet_size(
                    role.ptr,
                    role.len,
                    seed.ptr,
                    seed.len,
                    activityCode,
                    timeSeconds,
                    cameraYaw,
                    cameraPitch,
                    cameraZoom,
                ));
                if (!outputSize) throw new Error("Rust node-creature renderer produced an empty packet.");

                outputPtr = Number(exports.haive_node_alloc_bytes(outputSize));
                if (!outputPtr) throw new Error("Rust node-creature renderer could not allocate output memory.");

                const written = Number(exports.haive_node_render_packet_into(
                    role.ptr,
                    role.len,
                    seed.ptr,
                    seed.len,
                    activityCode,
                    timeSeconds,
                    cameraYaw,
                    cameraPitch,
                    cameraZoom,
                    outputPtr,
                    outputSize,
                ));
                if (written !== outputSize) {
                    throw new Error(`Node-creature packet size changed during render: ${outputSize} -> ${written}.`);
                }

                // Copy before freeing: a later allocation may grow or replace the WASM memory buffer.
                const packet = new Uint8Array(exports.memory.buffer, outputPtr, outputSize).slice();
                return bytesToBase64(packet);
            } finally {
                if (outputPtr && outputSize) exports.haive_node_free_bytes(outputPtr, outputSize);
                freeUtf8(seed);
                freeUtf8(role);
            }
        },
    };
})();
