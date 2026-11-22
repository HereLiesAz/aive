package com.hereliesaz.geministrator.inference

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiteRtLmContainerTest {
    @Test
    fun aFileStartingWithTheMagicIsAContainer() {
        // The first 16 bytes of LFM3-1.7B_mixed_int4_ekv2048.litertlm and LFM3-VL-2B_int4.litertlm (both read from the Hub).
        val header = "LITERTLM".encodeToByteArray() + byteArrayOf(1, 0, 0, 0, 7, 0, 0, 0)
        assertTrue(LiteRtLmContainer.looksLikeContainer(header))
    }

    @Test
    fun aTfliteFlatbufferOrAShortOrEmptyFileIsNot() {
        assertFalse(LiteRtLmContainer.looksLikeContainer(byteArrayOf(0x1C, 0, 0, 0) + "TFL3".encodeToByteArray()))
        assertFalse(LiteRtLmContainer.looksLikeContainer("LITERTL".encodeToByteArray()))
        assertFalse(LiteRtLmContainer.looksLikeContainer(ByteArray(0)))
        assertFalse(LiteRtLmContainer.looksLikeContainer("litertlm".encodeToByteArray()))
    }

    @Test
    fun theRuntimeNameIsReadLoosely() {
        assertTrue(LiteRtLmContainer.isRuntime("litert-lm"))
        assertTrue(LiteRtLmContainer.isRuntime(" LiteRT-LM "))
        assertTrue(LiteRtLmContainer.isRuntime("litertlm"))
        assertFalse(LiteRtLmContainer.isRuntime("litert"))
        assertFalse(LiteRtLmContainer.isRuntime(null))
    }
}
