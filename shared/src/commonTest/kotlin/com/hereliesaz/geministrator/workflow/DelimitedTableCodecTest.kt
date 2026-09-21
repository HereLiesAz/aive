package com.hereliesaz.geministrator.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DelimitedTableCodecTest {
    @Test
    fun csvHandlesQuotesCommasNewlinesAndDuplicateHeaders() {
        val table = DelimitedTableCodec.parse(
            text = "name,name,notes\nAda,Lovelace,\"line one\nline two\"\nGrace,Hopper,\"compiler, navy\"",
            delimiter = ',',
            firstRowHeaders = true,
            maxRows = 10,
        )

        assertEquals(listOf("name", "name_2", "notes"), table.columns)
        assertEquals("Ada", table.rows[0]["name"])
        assertEquals("Lovelace", table.rows[0]["name_2"])
        assertEquals("line one\nline two", table.rows[0]["notes"])
        assertEquals("compiler, navy", table.rows[1]["notes"])
        assertFalse(table.truncated)
    }

    @Test
    fun parseMarksTruncatedWithoutExposingMoreThanLimit() {
        val table = DelimitedTableCodec.parse(
            text = "id\n1\n2\n3",
            delimiter = ',',
            firstRowHeaders = true,
            maxRows = 2,
        )

        assertEquals(2, table.rows.size)
        assertTrue(table.truncated)
    }

    @Test
    fun serializeRoundTripsSpreadsheetRows() {
        val text = DelimitedTableCodec.serialize(
            columns = listOf("name", "note"),
            rows = listOf(
                mapOf("name" to "Ada", "note" to "a,b"),
                mapOf("name" to "Grace", "note" to "quoted \"value\""),
            ),
            delimiter = ',',
            firstRowHeaders = true,
        )
        val parsed = DelimitedTableCodec.parse(text, ',', true, 10)

        assertEquals("a,b", parsed.rows[0]["note"])
        assertEquals("quoted \"value\"", parsed.rows[1]["note"])
    }

    @Test
    fun tsvWithoutHeadersGetsStableColumnNames() {
        val table = DelimitedTableCodec.parse(
            text = "Ada\t36\nGrace\t85",
            delimiter = '\t',
            firstRowHeaders = false,
            maxRows = 10,
        )

        assertEquals(listOf("column_1", "column_2"), table.columns)
        assertEquals("85", table.rows[1]["column_2"])
    }
}
