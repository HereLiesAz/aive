package com.hereliesaz.geministrator.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EntireOperationsTest {
    @Test
    fun parsesTrailAndMirror() {
        assertEquals(LocalGitOperation.EntireTrail("Fix login"), LocalGitOperation.parse("entire-trail: Fix login"))
        assertEquals(LocalGitOperation.EntireMirror("/gh/octocat/hello-world"), LocalGitOperation.parse("entire-mirror:/gh/octocat/hello-world"))
        assertEquals(LocalGitOperation.EntireMirror("/et/acme/web"), LocalGitOperation.parse("entire-mirror:/et/acme/web"))
    }

    @Test
    fun refusesWhatEntireWouldNotTake() {
        assertFailsWith<IllegalArgumentException> { LocalGitOperation.parse("entire-trail:") }
        assertFailsWith<IllegalArgumentException> { LocalGitOperation.parse("entire-mirror:https://github.com/a/b") }
        assertFailsWith<IllegalArgumentException> { LocalGitOperation.parse("entire-mirror:/gh/a/b --cluster x") }
    }
}
