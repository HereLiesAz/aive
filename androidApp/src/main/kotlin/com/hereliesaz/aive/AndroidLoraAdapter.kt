package com.hereliesaz.aive

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Android loader for Aive's safetensors LoRA graph-input adapters. */
internal class AndroidLoraAdapter private constructor(
    val tensors: Map<String, OnnxTensor>,
    val metadata: Map<String, String>,
) : AutoCloseable {
    override fun close() = tensors.values.forEach(OnnxTensor::close)

    companion object {
        const val FORMAT = "aive-lora-inputs"
        private val json = Json { ignoreUnknownKeys = true }
        private const val MAX_HEADER_BYTES = 16L * 1024 * 1024

        fun load(
            file: File,
            environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
        ): AndroidLoraAdapter {
            RandomAccessFile(file, "r").use { raf ->
                val headerLength = ByteBuffer
                    .wrap(ByteArray(8).also(raf::readFully))
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .long
                require(headerLength in 2..MAX_HEADER_BYTES) {
                    "${file.name}: invalid safetensors header"
                }
                val header = json.parseToJsonElement(
                    ByteArray(headerLength.toInt()).also(raf::readFully).decodeToString(),
                ).jsonObject
                val dataStart = 8 + headerLength
                val metadata = (header["__metadata__"] as? JsonObject)
                    ?.mapValues { it.value.jsonPrimitive.content }
                    .orEmpty()
                require(metadata["format"] == FORMAT) {
                    "${file.name} is not an $FORMAT adapter"
                }

                val tensors = linkedMapOf<String, OnnxTensor>()
                try {
                    header.forEach { (name, entry) ->
                        if (name == "__metadata__") return@forEach
                        val info = entry.jsonObject
                        val dtype = info.getValue("dtype").jsonPrimitive.content
                        val shape = info.getValue("shape").jsonArray
                            .map { it.jsonPrimitive.long }
                            .toLongArray()
                        val (begin, end) = info.getValue("data_offsets").jsonArray
                            .map { it.jsonPrimitive.long }
                        val count = shape.fold(1L, Long::times)
                        val width = when (dtype) {
                            "F16" -> 2
                            "F32" -> 4
                            else -> error("${file.name}: unsupported dtype $dtype for $name")
                        }
                        require(end - begin == count * width && dataStart + end <= raf.length()) {
                            "${file.name}: bad offsets for $name"
                        }

                        val bytes = ByteArray((end - begin).toInt())
                        raf.seek(dataStart + begin)
                        raf.readFully(bytes)
                        val raw = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        val values = FloatArray(count.toInt()) { index ->
                            if (width == 2) halfToFloat(raw.getShort(index * 2))
                            else raw.getFloat(index * 4)
                        }
                        tensors[name] = OnnxTensor.createTensor(
                            environment,
                            FloatBuffer.wrap(values),
                            shape,
                        )
                    }
                } catch (failure: Throwable) {
                    tensors.values.forEach(OnnxTensor::close)
                    throw failure
                }

                return AndroidLoraAdapter(tensors, metadata)
            }
        }

        /** IEEE 754 binary16 -> binary32. */
        internal fun halfToFloat(half: Short): Float {
            val bits = half.toInt() and 0xFFFF
            val sign = (bits and 0x8000) shl 16
            val exponent = (bits ushr 10) and 0x1F
            val mantissa = bits and 0x3FF
            val out = when {
                exponent == 0 && mantissa == 0 -> sign
                exponent == 0 -> {
                    var m = mantissa
                    var e = -1
                    do {
                        m = m shl 1
                        e++
                    } while (m and 0x400 == 0)
                    sign or ((127 - 15 - e) shl 23) or ((m and 0x3FF) shl 13)
                }
                exponent == 0x1F -> sign or 0x7F800000 or (mantissa shl 13)
                else -> sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
            }
            return Float.fromBits(out)
        }
    }
}
