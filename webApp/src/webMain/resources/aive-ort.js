(function (global) {
    "use strict";

    // ONNX Runtime Web bridge for Kotlin/Wasm (shared/.../memory/BrowserOrt.wasmJs.kt).
    //
    // The runtime (onnxruntime-web's WebGPU build) is NOT bundled by webpack. The webApp build copies
    // ort.webgpu.min.mjs and the jsep .mjs/.wasm pair from the npm package into ./ort/ next to this
    // script (see generateWebOrtRuntime in webApp/build.gradle.kts), and it is loaded with a dynamic
    // import on first use, so pages that never run a model pay nothing.
    //
    // Everything crossing the Kotlin boundary is a String (JSON) so no JS object or Promise rejection
    // has to be mapped: every call answers {"ok":true,...} or {"ok":false,"error":"..."}.

    const scriptBase = (function () {
        const current = global.document && global.document.currentScript;
        if (current && current.src) return new URL(".", current.src).href;
        return new URL(".", (global.document && global.document.baseURI) || global.location.href).href;
    })();
    const ortBase = scriptBase + "ort/";

    const sessions = new Map();
    const queues = new Map();
    const buffers = new Map();
    let nextId = 1;
    let ortPromise = null;

    function ok(extra) {
        return JSON.stringify(Object.assign({ ok: true }, extra || {}));
    }

    function fail(error) {
        let message;
        try {
            message = error && error.message ? error.message : String(error);
        } catch (_) {
            message = "unknown error";
        }
        return JSON.stringify({ ok: false, error: String(message).slice(0, 2000) });
    }

    function loadOrt() {
        if (!ortPromise) {
            ortPromise = import(ortBase + "ort.webgpu.min.mjs")
                .then(function (module) {
                    const ort = module.InferenceSession ? module : module.default;
                    if (!ort || !ort.InferenceSession) {
                        throw new Error("Failed to load ORT: onnxruntime-web exports no InferenceSession");
                    }
                    ort.env.wasm.wasmPaths = ortBase;
                    // Threads need cross-origin isolation; one thread also needs no blob worker.
                    ort.env.wasm.numThreads = 1;
                    ort.env.wasm.proxy = false;
                    return ort;
                })
                .catch(function (error) {
                    ortPromise = null;
                    throw error;
                });
        }
        return ortPromise;
    }

    function base64ToBytes(base64) {
        const binary = global.atob(base64);
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index += 1) {
            bytes[index] = binary.charCodeAt(index);
        }
        return bytes;
    }

    function bytesToBase64(bytes) {
        let binary = "";
        const chunk = 0x8000;
        for (let offset = 0; offset < bytes.length; offset += chunk) {
            binary += String.fromCharCode.apply(
                null,
                bytes.subarray(offset, Math.min(offset + chunk, bytes.length)),
            );
        }
        return global.btoa(binary);
    }

    const arrayTypes = {
        float32: Float32Array,
        float64: Float64Array,
        int32: Int32Array,
        int64: BigInt64Array,
        uint8: Uint8Array,
        int8: Int8Array,
        bool: Uint8Array,
    };

    function toTensor(ort, spec) {
        const ArrayType = arrayTypes[spec.type];
        if (!ArrayType) throw new Error("Unsupported tensor type: " + spec.type);
        const bytes = base64ToBytes(spec.data);
        if (bytes.length % ArrayType.BYTES_PER_ELEMENT !== 0) {
            throw new Error("Tensor " + spec.name + " has a partial element");
        }
        // bytes owns a fresh buffer at offset 0, so every typed-array view is aligned.
        const data = new ArrayType(bytes.buffer, 0, bytes.length / ArrayType.BYTES_PER_ELEMENT);
        return new ort.Tensor(spec.type, data, spec.dims);
    }

    async function fromTensor(name, tensor) {
        const data = await tensor.getData();
        if (!data || !data.buffer) throw new Error("Unsupported output tensor type: " + tensor.type);
        const view = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
        const encoded = {
            name: name,
            type: tensor.type,
            dims: Array.from(tensor.dims, Number),
            data: bytesToBase64(view),
        };
        if (typeof tensor.dispose === "function") tensor.dispose();
        return encoded;
    }

    function webGpuAvailable() {
        return !!(global.navigator && global.navigator.gpu);
    }

    async function adapterInfo(powerPreference) {
        try {
            if (!webGpuAvailable()) return ok({ adapter: null });
            const options = {};
            if (powerPreference) options.powerPreference = powerPreference;
            const adapter = await global.navigator.gpu.requestAdapter(options);
            if (!adapter) return ok({ adapter: null });
            let info = adapter.info;
            if (!info && typeof adapter.requestAdapterInfo === "function") {
                info = await adapter.requestAdapterInfo();
            }
            info = info || {};
            return ok({
                adapter: {
                    description: info.description || "",
                    vendor: info.vendor || "",
                    architecture: info.architecture || "",
                },
            });
        } catch (_) {
            return ok({ adapter: null });
        }
    }

    function allocBuffer(size) {
        try {
            if (!Number.isInteger(size) || size <= 0) throw new Error("Buffer size must be positive.");
            const id = String(nextId++);
            buffers.set(id, new Uint8Array(size));
            return ok({ buffer: id });
        } catch (error) {
            return fail(error);
        }
    }

    function writeBuffer(bufferId, offset, chunkBase64) {
        try {
            const target = buffers.get(bufferId);
            if (!target) throw new Error("Unknown model buffer " + bufferId);
            const chunk = base64ToBytes(chunkBase64);
            if (offset < 0 || offset + chunk.length > target.length) {
                throw new Error("Model buffer write is out of range.");
            }
            target.set(chunk, offset);
            return ok();
        } catch (error) {
            return fail(error);
        }
    }

    async function create(source, providersCsv, powerPreference) {
        const ort = await loadOrt();
        if (powerPreference) {
            try {
                ort.env.webgpu.powerPreference = powerPreference;
            } catch (_) {
                // Deprecated in newer ORT releases; the adapter request already carries the preference.
            }
        }
        const providers = String(providersCsv).split(",").map(function (name) {
            return name.trim();
        }).filter(Boolean);
        // ORT tries the providers in order and moves on when one cannot start (WebGPU, then WASM).
        const session = await ort.InferenceSession.create(source, {
            executionProviders: providers,
            graphOptimizationLevel: "all",
        });
        const id = String(nextId++);
        sessions.set(id, session);
        return ok({
            session: id,
            inputs: Array.from(session.inputNames),
            outputs: Array.from(session.outputNames),
            providers: providers,
        });
    }

    async function createSession(modelUri, providersCsv, powerPreference) {
        try {
            return await create(modelUri, providersCsv, powerPreference);
        } catch (error) {
            return fail(error);
        }
    }

    async function createSessionFromBuffer(bufferId, providersCsv, powerPreference) {
        try {
            const bytes = buffers.get(bufferId);
            if (!bytes) throw new Error("Unknown model buffer " + bufferId);
            try {
                return await create(bytes, providersCsv, powerPreference);
            } finally {
                buffers.delete(bufferId);
            }
        } catch (error) {
            return fail(error);
        }
    }

    async function runNow(sessionId, requestJson) {
        const feeds = {};
        try {
            const session = sessions.get(sessionId);
            if (!session) throw new Error("Unknown ONNX session " + sessionId);
            const ort = await loadOrt();
            const request = JSON.parse(requestJson);
            for (const spec of request.inputs) {
                feeds[spec.name] = toTensor(ort, spec);
            }
            const fetches = Array.isArray(request.fetches) && request.fetches.length > 0
                ? request.fetches
                : null;
            const results = fetches ? await session.run(feeds, fetches) : await session.run(feeds);
            const names = fetches || Array.from(session.outputNames);
            const outputs = [];
            for (const name of names) {
                outputs.push(await fromTensor(name, results[name]));
            }
            return ok({ outputs: outputs });
        } catch (error) {
            return fail(error);
        } finally {
            for (const name of Object.keys(feeds)) {
                try {
                    if (typeof feeds[name].dispose === "function") feeds[name].dispose();
                } catch (_) {
                    // Already released.
                }
            }
        }
    }

    // ORT Web rejects overlapping runs on one session, so runs on a session are chained.
    function run(sessionId, requestJson) {
        const previous = queues.get(sessionId) || Promise.resolve();
        const next = previous.then(function () {
            return runNow(sessionId, requestJson);
        });
        queues.set(sessionId, next);
        return next;
    }

    async function release(sessionId) {
        try {
            const pending = queues.get(sessionId);
            if (pending) await pending;
            queues.delete(sessionId);
            const session = sessions.get(sessionId);
            sessions.delete(sessionId);
            if (session) await session.release();
            return ok();
        } catch (error) {
            return fail(error);
        }
    }

    global.AiveOrt = Object.freeze({
        ready: true,
        webGpuAvailable: webGpuAvailable,
        adapterInfo: adapterInfo,
        allocBuffer: allocBuffer,
        writeBuffer: writeBuffer,
        createSession: createSession,
        createSessionFromBuffer: createSessionFromBuffer,
        run: run,
        release: release,
    });
})(globalThis);
