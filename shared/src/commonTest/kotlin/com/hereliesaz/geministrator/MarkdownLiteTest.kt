package com.hereliesaz.geministrator

import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownLiteTest {
    @Test
    fun anAgentsPackingListReadsAsAList() {
        val shown = markdownLite("## FOOTWEAR\n- [ ] **Walking shoes** - 1\n- Cash for `tips`\n* plain *aside*")
        assertEquals("FOOTWEAR\n☐ Walking shoes - 1\n• Cash for tips\n• plain aside", shown.text)
        val bold = shown.spanStyles.filter { it.item.fontWeight == FontWeight.Black }.map { shown.text.substring(it.start, it.end) }
        assertTrue("FOOTWEAR" in bold)
        assertTrue("Walking shoes" in bold)
    }

    @Test
    fun whatIsNotMarkdownStaysAsWritten() {
        assertEquals("2 * 3 = 6, file_name_here", markdownLite("2 * 3 = 6, file_name_here").text)
    }
}
