package com.hereliesaz.conveyance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FormTest {

    private val name = FormField(ElementId("form.name"), Label("Name"))
    private val email = FormField(ElementId("form.email"), Label("Email"), kind = FieldKind.Email)
    private val newsletter = FormField(ElementId("form.newsletter"), Label("Newsletter"), required = false)
    private val signup = Form(ElementId("form.signup"), listOf(name, email, newsletter))

    @Test
    fun `a form with no fields refuses to exist`() {
        assertFailsWith<IllegalArgumentException> { Form(ElementId("empty"), emptyList()) }
    }

    @Test
    fun `two fields sharing one address refuse to form a Form`() {
        val duplicate = FormField(ElementId("form.name"), Label("Alias"))
        assertFailsWith<IllegalArgumentException> { Form(ElementId("form.dup"), listOf(name, duplicate)) }
    }

    @Test
    fun `percentComplete counts only required fields`() {
        assertEquals(0f, signup.percentComplete(emptySet()))
        assertEquals(0.5f, signup.percentComplete(setOf(name.id)))
        assertEquals(1f, signup.percentComplete(setOf(name.id, email.id)))
        // The optional field filled alone changes nothing -- it was never part of the denominator.
        assertEquals(0f, signup.percentComplete(setOf(newsletter.id)))
        assertEquals(1f, signup.percentComplete(setOf(name.id, email.id, newsletter.id)))
    }

    @Test
    fun `a form with no required fields reads as already complete`() {
        val optional = Form(ElementId("form.optional"), listOf(newsletter))
        assertEquals(1f, optional.percentComplete(emptySet()))
        assertTrue(optional.isComplete(emptySet()))
    }

    @Test
    fun `isComplete ignores the optional field`() {
        assertFalse(signup.isComplete(setOf(name.id)))
        assertTrue(signup.isComplete(setOf(name.id, email.id)))
        assertTrue(signup.isComplete(setOf(name.id, email.id, newsletter.id)))
    }

    @Test
    fun `the completion gate lives at the form's own element`() {
        val gate = signup.completionGate { emptySet() }
        assertEquals(signup.id, gate.livesAt)
    }

    @Test
    fun `the completion gate reads the filled set fresh each time, not just at construction`() {
        var filled = emptySet<ElementId>()
        val gate = signup.completionGate { filled }
        assertFalse(gate.satisfied)
        filled = setOf(name.id, email.id)
        assertTrue(gate.satisfied, "the gate should reflect fields filled in after it was built")
    }
}
