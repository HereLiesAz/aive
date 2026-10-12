package com.hereliesaz.conveyance

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Chrome text that cannot become instruction, because there is no longer anywhere to write an
 * instruction down and have it compile. This used to be a linter finding, discovered whenever
 * someone next ran the audit; it is a constructor now, discovered the moment the string is written.
 */
class LabelTest {

    @Test
    fun `a sentence cannot become a label`() {
        assertFailsWith<IllegalArgumentException> { Label("Tap here to send your invoice.") }
    }

    @Test
    fun `a long phrase cannot become a label even without punctuation`() {
        assertFailsWith<IllegalArgumentException> { Label("Your file is being uploaded") }
    }

    @Test
    fun `a word that tells the reader what to do cannot become a label`() {
        assertFailsWith<IllegalArgumentException> { Label("Tap") }
        assertFailsWith<IllegalArgumentException> { Label("Choose a recipient") }
    }

    @Test
    fun `a blank label cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> { Label("   ") }
    }

    /**
     * Sentence punctuation used to be checked only at the very end of the string, which let two
     * imperative fragments joined by a period through untouched: "Save." reads as a sentence, but
     * so does "Save. Exit" -- it is just two of them.
     */
    @Test
    fun `a sentence hiding in the middle of the string still cannot become a label`() {
        assertFailsWith<IllegalArgumentException> { Label("Save. Exit") }
        assertFailsWith<IllegalArgumentException> { Label("Wait! Continue") }
    }

    /**
     * A non-breaking space used to fuse two words into one opaque token, because the split regex
     * was ASCII-only whitespace -- hiding both the true word count and any TELLS word buried
     * inside the fused token from every check that follows.
     */
    @Test
    fun `a non-breaking space cannot hide a word count or a word that tells`() {
        val nonBreaking = "Delete account now"
        assertFailsWith<IllegalArgumentException> { Label(nonBreaking) }
    }

    @Test
    fun `a verb is an invitation and a noun names a thing`() {
        Label("Send")
        Label("Invoices")
        Label("Overdue invoices")
        assertTrue(true, "None of the above threw.")
    }

    @Test
    fun `a label reads back as its own text`() {
        assertTrue(Label("Send").toString() == "Send")
    }
}
