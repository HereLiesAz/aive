package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskRunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskOutputValidatorRegistryTest {
    private fun artifact(text: String) = ArtifactRef(
        id = ArtifactId("a"),
        kind = ArtifactKind.Specification,
        taskRunId = TaskRunId("r"),
        label = "out",
        textContent = text,
        createdAtEpochMillis = 0,
    )

    private val registry = TaskOutputValidatorRegistry.Empty.with("short") { artifacts ->
        artifacts.mapNotNull { if ((it.textContent?.length ?: 0) > 5) "Too long." else null }
    }

    @Test
    fun anUnregisteredIdIsAViolationNotAPass() {
        val problems = TaskOutputValidatorRegistry.Empty.problems("nope", listOf(artifact("x")))
        assertEquals(listOf("Unknown output check 'nope'."), problems)
    }

    @Test
    fun aRegisteredValidatorPassesAndFails() {
        assertTrue(registry.problems("short", listOf(artifact("ok"))).isEmpty())
        assertEquals(listOf("Too long."), registry.problems("short", listOf(artifact("far too long"))))
    }

    @Test
    fun blankProblemsAreDropped() {
        val blank = TaskOutputValidatorRegistry.Empty.with("blank") { listOf(" ") }
        assertTrue(blank.problems("blank", emptyList()).isEmpty())
    }

    @Test
    fun theMessageNamesWhatToFix() {
        assertEquals("The output was turned back: A. B.", taskOutputRejectedMessage(listOf("A.", "B.")))
    }
}
