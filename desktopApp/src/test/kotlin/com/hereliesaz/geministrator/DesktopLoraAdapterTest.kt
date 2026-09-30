package com.hereliesaz.geministrator

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopLoraAdapterTest {
    @Test
    fun halfPrecisionWidensExactly() {
        val cases = mapOf(
            0x0000 to 0f, 0x3C00 to 1f, 0xC000 to -2f, 0x3555 to 0.33325195f,
            0x7BFF to 65504f, 0x0001 to 5.9604645e-8f, 0x03FF to 6.097555e-5f,
        )
        cases.forEach { (bits, expected) ->
            assertEquals(expected, DesktopLoraAdapter.halfToFloat(bits.toShort()), "0x%04x".format(bits))
        }
        assertEquals(Float.POSITIVE_INFINITY, DesktopLoraAdapter.halfToFloat(0x7C00.toShort()))
    }

    /** Same layout the notebook's `save_adapter_file` writes. */
    @Test
    fun readsNotebookSafetensors() {
        val file = safetensors(
            metadata = """{"format":"aive-lora-inputs","base":"orchestration:base:int8"}""",
            name = "onnx::MatMul_1",
            halves = shortArrayOf(0x3C00, 0xC000.toShort(), 0x0000, 0x3800),
        )
        DesktopLoraAdapter.load(file).use { adapter ->
            assertEquals("orchestration:base:int8", adapter.metadata["base"])
            val tensor = adapter.tensors.getValue("onnx::MatMul_1")
            assertEquals(listOf(2L, 2L), tensor.info.shape.toList())
            val values = FloatArray(4).also { tensor.floatBuffer.get(it) }
            assertEquals(listOf(1f, -2f, 0f, 0.5f), values.toList())
        }
    }

    @Test
    fun rejectsOtherSafetensors() {
        val file = safetensors(metadata = """{"format":"pt"}""", name = "w", halves = shortArrayOf(0))
        assertFailsWith<IllegalArgumentException> { DesktopLoraAdapter.load(file) }
    }

    private fun safetensors(metadata: String, name: String, halves: ShortArray): File {
        val shape = if (halves.size == 4) "[2,2]" else "[${halves.size}]"
        val header = """{"__metadata__":$metadata,"$name":{"dtype":"F16","shape":$shape,"data_offsets":[0,${halves.size * 2}]}}"""
            .encodeToByteArray()
        val buffer = ByteBuffer.allocate(8 + header.size + halves.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(header.size.toLong()).put(header)
        halves.forEach(buffer::putShort)
        return File.createTempFile("adapter", ".safetensors").apply {
            deleteOnExit()
            writeBytes(buffer.array())
        }
    }
}
