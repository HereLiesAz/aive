package com.hereliesaz.geministrator.distributed

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalPathScrubTest {
    @Test
    fun absolutePathsBecomeFileNamesAndOtherTextIsKept() {
        assertEquals("feature-x", scrubLocalPaths("/tmp/work/feature-x"))
        assertEquals("git -C repo status", scrubLocalPaths("git -C /home/a/repo status"))
        assertEquals("repo", scrubLocalPaths("C:\\Users\\a\\repo"))
        assertEquals("feature/x", scrubLocalPaths("feature/x"))
        assertEquals("0", scrubLocalPaths("0"))
    }
}
