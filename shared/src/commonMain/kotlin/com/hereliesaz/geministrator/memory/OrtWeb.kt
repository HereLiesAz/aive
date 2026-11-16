package com.hereliesaz.geministrator.memory

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Platform-free half of the browser ONNX Runtime Web bridge (the Wasm half is BrowserOrt.wasmJs.kt
 * and webApp/src/webMain/resources/aive-ort.js). Everything that crosses the Kotlin/Wasm <-> JS
 * boundary is a String carrying JSON, so tensor encoding, provider order, error mapping and
 * execution reports live here and run in plain unit tests on every target.
 */

/** Element types the bridge moves across the boundary (ORT Web's own type names). */
enum class OrtTensorType(val ortName: String, val bytesPerElement: Int) {
    Float32("float32", 4),
    Float64("float64", 8),
    Int32("int32", 4),
    Int64("int64", 8),
    Uint8("uint8", 1),
    Int8("int8", 1),
    Bool("bool", 1),
    ;

    companion object {
        fun fromOrtName(name: String): OrtTensorType? = entries.firstOrNull { it.ortName == name }
    }
}

/** A dense tensor held as little-endian bytes (what the browser's typed arrays use). */
class OrtTensor private constructor(
    val type: OrtTensorType,
    val shape: List<Int>,
    private val raw: ByteArray,
) {
    init {
        require(shape.all { it >= 0 }) { "Tensor dimensions must not be negative: $shape" }
        require(raw.size.toLong() == countOf(shape) * type.bytesPerElement) {
            "Tensor of shape $shape and type ${type.ortName} needs ${countOf(shape) * type.bytesPerElement} bytes, got ${raw.size}"
        }
    }

    val elementCount: Int get() = countOf(shape).toInt()

    fun rawBytes(): ByteArray = raw.copyOf()

    fun toFloatArray(): FloatArray {
        require(type == OrtTensorType.Float32) { "Tensor is ${type.ortName}, not float32" }
        return FloatArray(elementCount) { Float.fromBits(readInt(raw, it * 4)) }
    }

    fun toDoubleArray(): DoubleArray {
        require(type == OrtTensorType.Float64) { "Tensor is ${type.ortName}, not float64" }
        return DoubleArray(elementCount) { Double.fromBits(readLong(raw, it * 8)) }
    }

    fun toIntArray(): IntArray {
        require(type == OrtTensorType.Int32) { "Tensor is ${type.ortName}, not int32" }
        return IntArray(elementCount) { readInt(raw, it * 4) }
    }

    fun toLongArray(): LongArray {
        require(type == OrtTensorType.Int64) { "Tensor is ${type.ortName}, not int64" }
        return LongArray(elementCount) { readLong(raw, it * 8) }
    }

    override fun toString(): String = "OrtTensor(${type.ortName}, shape=$shape)"

    companion object {
        private fun countOf(shape: List<Int>): Long {
            var count = 1L
            for (dimension in shape) {
                count *= dimension.toLong()
                require(count <= Int.MAX_VALUE / 8) { "Tensor of shape $shape is too large" }
            }
            return count
        }

        fun fromBytes(type: OrtTensorType, shape: List<Int>, bytes: ByteArray): OrtTensor =
            OrtTensor(type, shape.toList(), bytes.copyOf())

        fun floats(shape: List<Int>, values: FloatArray): OrtTensor {
            val bytes = ByteArray(values.size * 4)
            values.forEachIndexed { index, value -> writeInt(bytes, index * 4, value.toRawBits()) }
            return OrtTensor(OrtTensorType.Float32, shape.toList(), bytes)
        }

        fun doubles(shape: List<Int>, values: DoubleArray): OrtTensor {
            val bytes = ByteArray(values.size * 8)
            values.forEachIndexed { index, value -> writeLong(bytes, index * 8, value.toRawBits()) }
            return OrtTensor(OrtTensorType.Float64, shape.toList(), bytes)
        }

        fun ints(shape: List<Int>, values: IntArray): OrtTensor {
            val bytes = ByteArray(values.size * 4)
            values.forEachIndexed { index, value -> writeInt(bytes, index * 4, value) }
            return OrtTensor(OrtTensorType.Int32, shape.toList(), bytes)
        }

        fun longs(shape: List<Int>, values: LongArray): OrtTensor {
            val bytes = ByteArray(values.size * 8)
            values.forEachIndexed { index, value -> writeLong(bytes, index * 8, value) }
            return OrtTensor(OrtTensorType.Int64, shape.toList(), bytes)
        }

        private fun writeInt(target: ByteArray, offset: Int, value: Int) {
            for (i in 0 until 4) target[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }

        private fun writeLong(target: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) target[offset + i] = ((value ushr (8 * i)) and 0xFFL).toByte()
        }

        private fun readInt(source: ByteArray, offset: Int): Int {
            var value = 0
            for (i in 0 until 4) value = value or ((source[offset + i].toInt() and 0xFF) shl (8 * i))
            return value
        }

        private fun readLong(source: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) value = value or ((source[offset + i].toLong() and 0xFFL) shl (8 * i))
            return value
        }
    }
}

enum class OrtWebFailureKind {
    /** No usable ORT backend (WebGPU or WASM), or the runtime files did not load. */
    BackendUnavailable,
    OutOfMemory,
    /** The model could not be fetched or parsed. */
    ModelLoad,
    UnsupportedTensorType,
    /** The shim answered something that is not the agreed JSON. */
    Protocol,
    Unknown,
}

class OrtWebException(
    val kind: OrtWebFailureKind,
    message: String,
) : RuntimeException(message)

/** What a created session reports. [providers] is the requested order; ORT Web does not say which one ran. */
data class OrtWebSessionInfo(
    val sessionId: String,
    val inputNames: List<String>,
    val outputNames: List<String>,
    val providers: List<String>,
)

data class OrtWebAdapterInfo(
    val description: String?,
    val vendor: String?,
    val architecture: String?,
)

/** Execution-provider order for browser sessions: WebGPU first when a GPU was selected, then WASM. */
object OrtWebExecutionProviders {
    const val WEBGPU = "webgpu"
    const val WASM = "wasm"

    fun forSelection(selection: MemoryComputeSelection): List<String> =
        if (selection.device.deviceType == MemoryComputeDeviceType.GPU) listOf(WEBGPU, WASM) else listOf(WASM)

    fun powerPreference(preference: MemoryComputePreference): String = when (preference) {
        MemoryComputePreference.HIGH_PERFORMANCE -> "high-performance"
        MemoryComputePreference.LOW_POWER -> "low-power"
        else -> ""
    }
}

/**
 * What a browser session honestly knows. ORT Web falls back from WebGPU to WASM by itself and does not
 * report which provider executed, so a GPU selection stays [MemoryExecutionVerification.SessionConfigured];
 * a successful WASM-only run is proof of the CPU path.
 */
fun ortWebExecutionReport(selection: MemoryComputeSelection, runSucceeded: Boolean): MemoryExecutionReport =
    if (runSucceeded && selection.device.deviceType == MemoryComputeDeviceType.CPU) {
        MemoryExecutionReport(
            selection = selection,
            actualDevices = listOf(selection.device),
            cpuFallbackObserved = false,
            acceleratedNodeFraction = 0f,
            verification = MemoryExecutionVerification.ProfiledRun,
        )
    } else {
        MemoryExecutionReport(
            selection = selection,
            verification = MemoryExecutionVerification.SessionConfigured,
        )
    }

/** The JSON the Kotlin side and aive-ort.js exchange. Every shim answer is `{"ok":true,...}` or `{"ok":false,"error":"..."}`. */
@OptIn(ExperimentalEncodingApi::class)
object OrtWebWire {
    fun classifyFailure(message: String): OrtWebFailureKind {
        val text = message.lowercase()
        return when {
            "out of memory" in text || "oom" in text.split(' ', ':', ',', '.', '(', ')') ||
                "failed to allocate" in text || "allocation failed" in text -> OrtWebFailureKind.OutOfMemory
            "unsupported tensor" in text || "unsupported output tensor" in text -> OrtWebFailureKind.UnsupportedTensorType
            "no available backend" in text || "webgpu" in text || "failed to load ort" in text ||
                "failed to resolve module" in text || "importing a module script failed" in text ||
                "failed to fetch dynamically imported module" in text -> OrtWebFailureKind.BackendUnavailable
            "failed to fetch" in text || "failed to load model" in text || "protobuf" in text ||
                "404" in text || "networkerror" in text -> OrtWebFailureKind.ModelLoad
            else -> OrtWebFailureKind.Unknown
        }
    }

    fun encodeRun(inputs: Map<String, OrtTensor>, fetches: List<String>): String = buildJsonObject {
        put(
            "inputs",
            buildJsonArray {
                inputs.forEach { (name, tensor) ->
                    add(
                        buildJsonObject {
                            put("name", JsonPrimitive(name))
                            put("type", JsonPrimitive(tensor.type.ortName))
                            put("dims", buildJsonArray { tensor.shape.forEach { add(JsonPrimitive(it)) } })
                            put("data", JsonPrimitive(Base64.Default.encode(tensor.rawBytes())))
                        },
                    )
                }
            },
        )
        put("fetches", buildJsonArray { fetches.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    /** Parses a shim answer and throws [OrtWebException] for `ok:false` or malformed text. */
    fun parseEnvelope(json: String): JsonObject {
        val element = try {
            Json.parseToJsonElement(json)
        } catch (failure: SerializationException) {
            throw OrtWebException(OrtWebFailureKind.Protocol, "ONNX Runtime Web shim returned malformed JSON")
        }
        val envelope = element as? JsonObject
            ?: throw OrtWebException(OrtWebFailureKind.Protocol, "ONNX Runtime Web shim returned a non-object answer")
        val ok = (envelope["ok"] as? JsonPrimitive)?.booleanOrNull
        if (ok != true) {
            val message = (envelope["error"] as? JsonPrimitive)?.contentOrNull ?: "ONNX Runtime Web failed"
            throw OrtWebException(classifyFailure(message), message)
        }
        return envelope
    }

    fun decodeSessionInfo(json: String): OrtWebSessionInfo {
        val envelope = parseEnvelope(json)
        return OrtWebSessionInfo(
            sessionId = (envelope["session"] as? JsonPrimitive)?.contentOrNull
                ?: throw OrtWebException(OrtWebFailureKind.Protocol, "Session answer has no session id"),
            inputNames = strings(envelope["inputs"]),
            outputNames = strings(envelope["outputs"]),
            providers = strings(envelope["providers"]),
        )
    }

    fun decodeBufferId(json: String): String =
        (parseEnvelope(json)["buffer"] as? JsonPrimitive)?.contentOrNull
            ?: throw OrtWebException(OrtWebFailureKind.Protocol, "Buffer answer has no buffer id")

    fun decodeAdapterInfo(json: String): OrtWebAdapterInfo? {
        val adapter = parseEnvelope(json)["adapter"] as? JsonObject ?: return null
        fun field(name: String): String? = (adapter[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return OrtWebAdapterInfo(field("description"), field("vendor"), field("architecture"))
    }

    fun decodeRunResult(json: String): Map<String, OrtTensor> {
        val outputs = parseEnvelope(json)["outputs"] as? JsonArray
            ?: throw OrtWebException(OrtWebFailureKind.Protocol, "Run answer has no outputs")
        val result = LinkedHashMap<String, OrtTensor>()
        for (item in outputs) {
            val entry = item as? JsonObject
                ?: throw OrtWebException(OrtWebFailureKind.Protocol, "Run output is not an object")
            val name = (entry["name"] as? JsonPrimitive)?.contentOrNull
                ?: throw OrtWebException(OrtWebFailureKind.Protocol, "Run output has no name")
            val typeName = (entry["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val type = OrtTensorType.fromOrtName(typeName)
                ?: throw OrtWebException(
                    OrtWebFailureKind.UnsupportedTensorType,
                    "Unsupported output tensor type '$typeName' for $name",
                )
            val dims = (entry["dims"] as? JsonArray)?.map {
                (it as? JsonPrimitive)?.intOrNull
                    ?: throw OrtWebException(OrtWebFailureKind.Protocol, "Output $name has a non-integer dimension")
            } ?: throw OrtWebException(OrtWebFailureKind.Protocol, "Output $name has no dims")
            val data = (entry["data"] as? JsonPrimitive)?.contentOrNull
                ?: throw OrtWebException(OrtWebFailureKind.Protocol, "Output $name has no data")
            val bytes = try {
                Base64.Default.decode(data)
            } catch (failure: IllegalArgumentException) {
                throw OrtWebException(OrtWebFailureKind.Protocol, "Output $name has invalid base64 data")
            }
            result[name] = try {
                OrtTensor.fromBytes(type, dims, bytes)
            } catch (failure: IllegalArgumentException) {
                throw OrtWebException(OrtWebFailureKind.Protocol, failure.message ?: "Output $name has the wrong size")
            }
        }
        return result
    }

    private fun strings(element: kotlinx.serialization.json.JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}
