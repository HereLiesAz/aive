package com.hereliesaz.aive

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedReadTest {
    @Test
    fun aBodyOverTheCapIsRefused() {
        assertEquals("abc", readBoundedText(ByteArrayInputStream("abc".toByteArray()), 10))
        assertFailsWith<IllegalStateException> { readBoundedText(ByteArrayInputStream(ByteArray(11)), 10) }
    }
}
