package com.hereliesaz.conveyance

/**
 * What a [FormField]'s value actually is, for the two things that need to agree on a closed
 * vocabulary rather than a free-text hint: a host's platform autofill integration, and this
 * form's own recovery. Kept deliberately small -- these are the values a real device already
 * tends to hold, not an attempt to enumerate every input type a form could ever want.
 */
enum class FieldKind {
    Text,
    Name,
    Email,
    Phone,
    PostalAddress,
    Password,
    OneTimeCode,
}

/**
 * One field a [Form] collects, addressed the same way everything else in this framework is.
 *
 * [kind] exists so a host can ask the platform to fill this in rather than the person -- it's
 * their device, and a name or email address the platform already has is not a thing worth typing
 * twice. [required] separates what [Form.percentComplete] and [Form.isComplete] actually gate on
 * from a field a person may skip.
 */
data class FormField(
    val id: ElementId,
    val label: Label,
    val kind: FieldKind = FieldKind.Text,
    val required: Boolean = true,
)

/**
 * A group of [FormField]s conveyed as one element, not N unrelated ones.
 *
 * A plain field almost never appears alone. The moment a second one joins it, the collection as a
 * whole starts doing jobs no single field does by itself: it reports how much is left
 * ([percentComplete]), it groups what belongs together, and it is the one place a completion gate
 * can honestly live ([completionGate]) rather than being scattered across every field's own
 * validation. Treating that collection as its own element -- with its own [Job.Report],
 * [Job.Group], [Job.Progress] and [Job.Confirm] -- is what lets a form justify existing under
 * [Employment.Working]'s four-job minimum without inventing jobs that aren't really there: those
 * four are true of the group the moment it has more than one field, whether or not any single
 * field bothers to declare them.
 *
 * This class is deliberately platform-free: it knows the fields and whether they're filled, and
 * nothing about how a value got into one. Autofill, a saved draft, or a person's own typing all
 * look identical from here -- one more [ElementId] added to the filled set. That is also why
 * "form recovery" (surviving a process death, a lost connection, a closed tab) is not a separate
 * mechanism this class provides: recovery is exactly persisting and restoring that same filled
 * set, which is a binding's concern (see `conveyance-compose`'s `FormState`), not this class's.
 */
class Form(val id: ElementId, val fields: List<FormField>) {
    init {
        require(fields.isNotEmpty()) { "A form with no fields has nothing to fill and nothing to report." }
        val duplicates = fields.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "Two fields sharing one address ($duplicates) would let marking either one satisfy both -- " +
                "give each field its own ElementId."
        }
    }

    /** What actually gates [isComplete] and [percentComplete] -- an optional field left empty is not incompleteness. */
    val requiredFields: List<FormField> get() = fields.filter { it.required }

    /**
     * How much of this form is done, in `0f..1f`. A form with no required fields reads as already
     * complete: there is nothing left for the person to do, which is the honest reading of
     * "percent complete" rather than a division by zero waiting to happen.
     */
    fun percentComplete(filled: Set<ElementId>): Float {
        val required = requiredFields
        if (required.isEmpty()) return 1f
        return required.count { it.id in filled }.toFloat() / required.size
    }

    fun isComplete(filled: Set<ElementId>): Boolean = requiredFields.all { it.id in filled }

    /**
     * A [Gate] for whatever act submits this form -- it lives at the form's own element, since
     * that is where a person can see what is still missing, not at the submit control itself.
     * [filled] is read lazily each time [Gate.satisfied] is checked, so the same gate stays
     * correct as fields are filled in around it rather than freezing the set at construction.
     */
    fun completionGate(filled: () -> Set<ElementId>): Gate =
        Gate(id = "${id.value}.complete", livesAt = id) { isComplete(filled()) }
}
