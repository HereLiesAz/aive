package com.hereliesaz.geministrator.memory.procedural

/**
 * Parameter schema generalization uses explicit typed declarations from a trusted executor
 * adapter. A redacted trace contains no argument values, so values are never reconstructed
 * from stdout, transcript text, or an imagined command line.
 */
object ProcedureParameterizer {
    private val unsafeNames = Regex(
        "(?i)(password|secret|token|credential|api.?key|private.?key|shell|script|command|eval|stdin|env|authorization)"
    )

    /**
     * All observed variants must agree on types; varying presence makes a slot optional.
     * Return null when a name/type conflict or unsafe command-like parameter is detected.
     */
    fun generalize(declarations: List<List<ProcedureParameter>>): List<ProcedureParameter>? {
        if (declarations.isEmpty() || declarations.size > 128) return null
        if (declarations.any { it.size > 32 || it.map { p -> p.name }.distinct().size != it.size }) return null
        val names = declarations.flatMap { row -> row.map { it.name } }.distinct().sorted()
        if (names.size > 32 || names.any { unsafeNames.containsMatchIn(it) }) return null
        return names.map { name ->
            val present = declarations.mapNotNull { list -> list.singleOrNull { it.name == name } }
            if (present.map { it.type }.distinct().size != 1) return null
            val first = present.first()
            first.copy(
                required = present.size == declarations.size && present.all { it.required },
                maxLength = present.minOf { it.maxLength },
            )
        }
    }
}

/** Only host-registered typed operations can be bound to observed patterns; not shell text. */
data class ProcedureOperationDescriptor(
    val taskDefinitionId: String,
    val executorKind: String,
    val operationId: String,
    val sideEffect: ProcedureSideEffect,
    val parameterSchemas: List<List<ProcedureParameter>> = listOf(emptyList()),
) {
    init {
        require(taskDefinitionId.isNotBlank() && taskDefinitionId.length <= 256)
        require(executorKind.isNotBlank() && executorKind.length <= 64)
        require(operationId.matches(Regex("[a-z][a-z0-9.-]{0,127}"))) {
            "A procedure operation must be a registered symbolic identifier, never a command"
        }
        require(parameterSchemas.isNotEmpty() && parameterSchemas.size <= 128)
    }
}

object ProcedureCompatibility {
    /**
     * This does not assert that a step is approved or executable. It merely rejects obvious
     * mismatches before forming an immutable proposal for an independent verifier.
     */
    fun proposalSteps(
        pattern: ProcedurePattern,
        descriptors: Map<String, ProcedureOperationDescriptor>,
    ): Pair<List<ProcedureStep>, List<ProcedureParameter>>? {
        if (pattern.steps.isEmpty() || pattern.steps.size > 32) return null
        if (pattern.conflictingOrFailedAttempts != 0) return null
        val selected = pattern.steps.map { step ->
            val descriptor = descriptors[step.taskDefinitionId] ?: return null
            if (descriptor.taskDefinitionId != step.taskDefinitionId ||
                descriptor.executorKind != step.executorKind ||
                descriptor.sideEffect != ProcedureSideEffect.ReadOnly
            ) return null
            descriptor
        }
        val commonParameters = ProcedureParameterizer.generalize(selected.flatMap { it.parameterSchemas })
            ?: return null
        // A sequence observed on a clock is not a verified dependency. No dependencies are inferred.
        val steps = selected.mapIndexed { index, descriptor ->
            ProcedureStep(
                id = "step${index + 1}",
                executorKind = descriptor.executorKind,
                operationId = descriptor.operationId,
                sideEffect = descriptor.sideEffect,
                // Bind only the declared typed slots. No literal arguments or command strings.
                parameterBindings = descriptor.parameterSchemas.flatten()
                    .map { it.name }.distinct().associateWith { it },
            )
        }
        return steps to commonParameters
    }
}
