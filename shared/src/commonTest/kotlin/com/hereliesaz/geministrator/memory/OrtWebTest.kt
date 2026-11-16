package com.hereliesaz.geministrator.memory

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class OrtWebTest {
    private val cpu = MemoryComputeDevice("wasm", "WebAssembly CPU", MemoryComputeDeviceType.CPU)
    private val gpu = MemoryComputeDevice("webgpu", "WebGPU adapter", MemoryComputeDeviceType.GPU)

    private fun selection(device: MemoryComputeDevice) =
        MemoryComputeSelection(MemoryComputePreference.AUTO, device, cpuFallbackEnabled = true)

    @Test
    fun tensorsRoundTripEveryNumericType() {
        val floats = floatArrayOf(1.5f, -2.25f, 0f, Float.MAX_VALUE)
        assertContentEquals(floats, OrtTensor.floats(listOf(2, 2), floats).toFloatArray())
        val doubles = doubleArrayOf(PI_VALUE, -1e300)
        assertContentEquals(doubles, OrtTensor.doubles(listOf(2), doubles).toDoubleArray())
        val ints = intArrayOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE)
        assertContentEquals(ints, OrtTensor.ints(listOf(4), ints).toIntArray())
        val longs = longArrayOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 101L)
        assertContentEquals(longs, OrtTensor.longs(listOf(1, 5), longs).toLongArray())
    }

    @Test
    fun tensorBytesAreLittleEndian() {
        val bytes = OrtTensor.longs(listOf(1), longArrayOf(1L)).rawBytes()
        assertContentEquals(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0), bytes)
        assertContentEquals(byteArrayOf(0, 0, -128, 63), OrtTensor.floats(listOf(1), floatArrayOf(1f)).rawBytes())
    }

    @Test
    fun tensorRejectsShapeAndTypeMismatches() {
        assertFailsWith<IllegalArgumentException> { OrtTensor.floats(listOf(2, 2), floatArrayOf(1f)) }
        assertFailsWith<IllegalArgumentException> { OrtTensor.floats(listOf(-1), floatArrayOf()) }
        assertFailsWith<IllegalArgumentException> { OrtTensor.floats(listOf(1), floatArrayOf(1f)).toLongArray() }
        assertEquals(0, OrtTensor.floats(listOf(0, 3), floatArrayOf()).elementCount)
    }

    @Test
    fun dtypeNamesMatchOrtWeb() {
        assertEquals(OrtTensorType.Int64, OrtTensorType.fromOrtName("int64"))
        assertEquals(OrtTensorType.Float32, OrtTensorType.fromOrtName("float32"))
        assertEquals(OrtTensorType.Bool, OrtTensorType.fromOrtName("bool"))
        assertNull(OrtTensorType.fromOrtName("float16"))
        assertNull(OrtTensorType.fromOrtName("string"))
        assertEquals(8, OrtTensorType.Int64.bytesPerElement)
    }

    @Test
    fun webGpuIsTriedBeforeWasmAndCpuNeverTriesWebGpu() {
        assertEquals(listOf("webgpu", "wasm"), OrtWebExecutionProviders.forSelection(selection(gpu)))
        assertEquals(listOf("wasm"), OrtWebExecutionProviders.forSelection(selection(cpu)))
        assertEquals("high-performance", OrtWebExecutionProviders.powerPreference(MemoryComputePreference.HIGH_PERFORMANCE))
        assertEquals("low-power", OrtWebExecutionProviders.powerPreference(MemoryComputePreference.LOW_POWER))
        assertEquals("", OrtWebExecutionProviders.powerPreference(MemoryComputePreference.AUTO))
    }

    @Test
    fun webPlatformPrefersWebGpuThenWasm() {
        assertEquals(listOf("WebGPU", "WASM"), MemoryMicroAgentPlatform.Web.preferredExecutionProviders())
    }

    @Test
    fun executionReportDoesNotClaimAccelerationItCannotObserve() {
        val gpuReport = ortWebExecutionReport(selection(gpu), runSucceeded = true)
        assertEquals(MemoryExecutionVerification.SessionConfigured, gpuReport.verification)
        assertFalse(gpuReport.isHardwareAccelerated)
        val cpuReport = ortWebExecutionReport(selection(cpu), runSucceeded = true)
        assertEquals(MemoryExecutionVerification.ProfiledRun, cpuReport.verification)
        assertEquals(false, cpuReport.cpuFallbackObserved)
        val unrun = ortWebExecutionReport(selection(cpu), runSucceeded = false)
        assertEquals(MemoryExecutionVerification.SessionConfigured, unrun.verification)
    }

    @Test
    fun runRequestCarriesNamesTypesDimsAndBase64Data() {
        val json = OrtWebWire.encodeRun(
            mapOf("input_ids" to OrtTensor.longs(listOf(1, 2), longArrayOf(7L, 9L))),
            fetches = listOf("last_hidden_state"),
        )
        val parsed = OrtWebWire.parseEnvelope("""{"ok":true,"request":$json}""")
        val request = parsed["request"].toString()
        assertTrue("\"name\":\"input_ids\"" in request)
        assertTrue("\"type\":\"int64\"" in request)
        assertTrue("\"dims\":[1,2]" in request)
        assertTrue("\"fetches\":[\"last_hidden_state\"]" in request)
        assertTrue(Base64.Default.encode(OrtTensor.longs(listOf(1, 2), longArrayOf(7L, 9L)).rawBytes()) in request)
    }

    @Test
    fun runResultDecodesNamedOutputs() {
        val data = Base64.Default.encode(OrtTensor.floats(listOf(1, 2), floatArrayOf(0.5f, -1f)).rawBytes())
        val outputs = OrtWebWire.decodeRunResult(
            """{"ok":true,"outputs":[{"name":"embedding","type":"float32","dims":[1,2],"data":"$data"}]}""",
        )
        val tensor = outputs.getValue("embedding")
        assertEquals(listOf(1, 2), tensor.shape)
        assertContentEquals(floatArrayOf(0.5f, -1f), tensor.toFloatArray())
    }

    @Test
    fun runResultRejectsUnsupportedTypesAndWrongSizes() {
        val unsupported = assertFailsWith<OrtWebException> {
            OrtWebWire.decodeRunResult("""{"ok":true,"outputs":[{"name":"x","type":"float16","dims":[1],"data":"AAA="}]}""")
        }
        assertEquals(OrtWebFailureKind.UnsupportedTensorType, unsupported.kind)
        val wrongSize = assertFailsWith<OrtWebException> {
            OrtWebWire.decodeRunResult("""{"ok":true,"outputs":[{"name":"x","type":"float32","dims":[4],"data":"AAAA"}]}""")
        }
        assertEquals(OrtWebFailureKind.Protocol, wrongSize.kind)
    }

    @Test
    fun errorsFromTheShimAreMappedToFailureKinds() {
        fun kindOf(message: String) = assertFailsWith<OrtWebException> {
            OrtWebWire.parseEnvelope("""{"ok":false,"error":"$message"}""")
        }.kind
        assertEquals(OrtWebFailureKind.OutOfMemory, kindOf("Out of memory while allocating"))
        assertEquals(OrtWebFailureKind.BackendUnavailable, kindOf("no available backend found. ERR: [webgpu] RuntimeError"))
        assertEquals(OrtWebFailureKind.BackendUnavailable, kindOf("Failed to fetch dynamically imported module: ort.webgpu.min.mjs"))
        assertEquals(OrtWebFailureKind.ModelLoad, kindOf("Failed to fetch"))
        assertEquals(OrtWebFailureKind.UnsupportedTensorType, kindOf("Unsupported tensor type: string"))
        assertEquals(OrtWebFailureKind.Unknown, kindOf("something else"))
        assertEquals(
            "boom",
            assertFailsWith<OrtWebException> { OrtWebWire.parseEnvelope("""{"ok":false,"error":"boom"}""") }.message,
        )
    }

    @Test
    fun malformedShimAnswersAreProtocolErrors() {
        assertEquals(
            OrtWebFailureKind.Protocol,
            assertFailsWith<OrtWebException> { OrtWebWire.parseEnvelope("not json") }.kind,
        )
        assertEquals(
            OrtWebFailureKind.Protocol,
            assertFailsWith<OrtWebException> { OrtWebWire.parseEnvelope("[1]") }.kind,
        )
        assertEquals(
            OrtWebFailureKind.Protocol,
            assertFailsWith<OrtWebException> { OrtWebWire.decodeSessionInfo("""{"ok":true}""") }.kind,
        )
    }

    @Test
    fun sessionAndAdapterAnswersDecode() {
        val info = OrtWebWire.decodeSessionInfo(
            """{"ok":true,"session":"3","inputs":["input_ids","attention_mask"],"outputs":["logits"],"providers":["webgpu","wasm"]}""",
        )
        assertEquals("3", info.sessionId)
        assertEquals(listOf("input_ids", "attention_mask"), info.inputNames)
        assertEquals(listOf("logits"), info.outputNames)
        assertEquals(listOf("webgpu", "wasm"), info.providers)
        assertNull(OrtWebWire.decodeAdapterInfo("""{"ok":true,"adapter":null}"""))
        val adapter = OrtWebWire.decodeAdapterInfo(
            """{"ok":true,"adapter":{"description":"Test GPU","vendor":"","architecture":"gen12"}}""",
        )
        assertEquals("Test GPU", adapter?.description)
        assertNull(adapter?.vendor)
        assertEquals("gen12", adapter?.architecture)
        assertEquals("7", OrtWebWire.decodeBufferId("""{"ok":true,"buffer":"7"}"""))
    }

    private companion object {
        const val PI_VALUE = 3.141592653589793
    }
}
