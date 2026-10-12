package com.hereliesaz.geministrator.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitRefNamesTest {
    @Test
    fun ordinaryBranchNamesPass() {
        listOf("main", "feature/repo-links", "aive/opencode-12-34", "release-1.0", "fix_bug").forEach {
            assertEquals(it, GitRefNames.requireSafe(" $it "))
        }
    }

    @Test
    fun refspecsAndOptionsAreRejected() {
        listOf(
            "", "-force", "+main", "HEAD:main", "src:dst", "main~1", "a^b", "a b", "a?b", "a*b", "a[b", "a\\b",
            "a..b", "a@{1}", "@", "/main", "main/", "main.", "a//b", ".hidden", "feature/.x", "main.lock", "a\nb",
        ).forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { GitRefNames.requireSafe(name) }
        }
    }
}
