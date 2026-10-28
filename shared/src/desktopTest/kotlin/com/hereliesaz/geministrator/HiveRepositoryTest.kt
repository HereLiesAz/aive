package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RepositorySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HiveRepositoryTest {
    @Test
    fun blankIsNone() = assertNull(hiveRepository("  "))

    @Test
    fun shorthandIsGitHub() {
        val ref = hiveRepository("hereliesaz/aive")!!
        assertEquals(RepositorySource.GitHub, ref.source)
        assertEquals("aive", ref.name)
    }

    @Test
    fun gitlabUrlIsGitLab() =
        assertEquals(RepositorySource.GitLab, hiveRepository("https://gitlab.com/a/b")!!.source)
}
