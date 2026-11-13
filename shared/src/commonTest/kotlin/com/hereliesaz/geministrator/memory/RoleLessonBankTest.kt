package com.hereliesaz.geministrator.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoleLessonBankTest {
    @Test
    fun bankIdIsScopedToTheRole() {
        assertEquals("role:reviewer-tax", roleBankId(" reviewer-tax "))
        assertEquals("", roleBankId("  "))
    }

    @Test
    fun oneRequestPerDistinctLessonScopedToTheRoleBank() {
        val requests = roleLessonRequests(
            "reviewer-tax",
            listOf("Check the filing year (source: IRS, fetched 2026-01-02)", " ", "Check the filing year (source: IRS, fetched 2026-01-02)", "Cite the form (source: Pub 17, fetched 2026-01-03)"),
            100L,
        )
        assertEquals(2, requests.size)
        requests.forEach {
            assertEquals("role:reviewer-tax", it.scope.workflowRunId)
            assertEquals("reviewer-tax", it.scope.roleId)
            assertEquals(ROLE_LESSON_LABEL, it.label)
            assertTrue("source:" in it.text && "fetched" in it.text)
        }
    }

    @Test
    fun blankRoleBanksNothing() {
        assertTrue(roleLessonRequests("", listOf("x"), 1L).isEmpty())
    }

    @Test
    fun lessonsAreReadBackFromEpisodesByLabel() {
        fun episode(id: String, label: String, text: String, at: Long) = MemoryEpisode(
            id = MemoryEpisodeId(id), sourceSessionId = "s", userPrompt = text, createdAtEpochMillis = at,
            chunks = listOf(MemorySourceChunk(MemoryChunkId("$id:c"), MemoryEpisodeId(id), 0, MemorySourceKind.AgentNote, label, text)),
        )
        val snapshot = MemorySnapshot(
            episodes = listOf(
                episode("e2", ROLE_LESSON_LABEL, "second", 2),
                episode("e1", ROLE_LESSON_LABEL, "first", 1),
                episode("e3", "Agent note", "not a lesson", 3),
            ),
        )
        assertEquals(listOf("first", "second"), roleLessonsIn(snapshot))
    }
}
