package com.hereliesaz.aive

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AndroidXlsxCodecTest {
    @Test
    fun xlsxRoundTripPreservesHeadersRowsAndEscapedText() {
        val columns = listOf("name", "notes")
        val rows = listOf(
            mapOf("name" to "Ada", "notes" to "A & B < C"),
            mapOf("name" to "Grace", "notes" to "quoted \"value\""),
        )

        val bytes = AndroidXlsxCodec.serialize(
            columns = columns,
            rows = rows,
            firstRowHeaders = true,
        )
        val table = AndroidXlsxCodec.parse(
            bytes = bytes,
            firstRowHeaders = true,
            maxRows = 100,
        )

        assertEquals(columns, table.columns)
        assertEquals(rows, table.rows)
        assertFalse(table.truncated)
    }

    @Test
    fun xlsxParserHonorsRowLimit() {
        val bytes = AndroidXlsxCodec.serialize(
            columns = listOf("id"),
            rows = (1..5).map { mapOf("id" to it.toString()) },
            firstRowHeaders = true,
        )

        val table = AndroidXlsxCodec.parse(bytes, firstRowHeaders = true, maxRows = 2)

        assertEquals(2, table.rows.size)
        assertEquals(true, table.truncated)
    }
}
