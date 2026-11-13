package com.hereliesaz.geministrator.providers.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class GitLabWorkspaceTreeMergeTest {
    @Test
    fun priorityPathsComeFirstOnceEachAndTheCapHolds() {
        val merged = mergeTreePriority(
            priority = listOf(listOf("README.md", "build.gradle.kts"), listOf("shared/a.kt", "README.md")),
            rest = listOf("a/x.kt", "shared/a.kt", "z/y.kt"),
            max = 5,
        )
        assertEquals(listOf("README.md", "build.gradle.kts", "shared/a.kt", "a/x.kt", "z/y.kt"), merged)
        assertEquals(listOf("README.md", "build.gradle.kts"), mergeTreePriority(listOf(listOf("README.md", "build.gradle.kts")), listOf("a"), 2))
    }
}
