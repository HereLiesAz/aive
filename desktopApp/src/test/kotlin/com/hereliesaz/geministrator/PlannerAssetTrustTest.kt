package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlannerAssetTrustTest {
    private val base = "https://github.com/HereLiesAz/aive/releases/download/orchestration-layer-epoch8/"

    @Test
    fun onlyPlainNamesInThisProjectsReleasesAreTrusted() {
        assertTrue(isTrustedPlannerAsset("haive-orch_planner-int8-epoch8.tar.gz.part001", base + "x.part001"))
        assertFalse(isTrustedPlannerAsset("../x", base + "x"))
        assertFalse(isTrustedPlannerAsset("a/b", base + "x"))
        assertFalse(isTrustedPlannerAsset("x", "https://example.com/x"))
        assertFalse(isTrustedPlannerAsset("x", "http://github.com/HereLiesAz/aive/releases/download/x"))
    }
}
