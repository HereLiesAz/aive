package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NodeArmAssetsTest {
    @Test
    fun builtInRoleLabelsResolveToDistinctArmFamilies() {
        assertEquals(NodeArmRole.Orchestrator, NodeArmAssets.roleFor("Orchestrator"))
        assertEquals(NodeArmRole.ProductManager, NodeArmAssets.roleFor("Product Manager"))
        assertEquals(NodeArmRole.Researcher, NodeArmAssets.roleFor("Researcher"))
        assertEquals(NodeArmRole.Architect, NodeArmAssets.roleFor("Architect"))
        assertEquals(NodeArmRole.EpaRepresentative, NodeArmAssets.roleFor("EPA Representative"))
        assertEquals(NodeArmRole.UxDesigner, NodeArmAssets.roleFor("UX Designer"))
        assertEquals(NodeArmRole.ImplementationEngineer, NodeArmAssets.roleFor("Implementation Engineer"))
        assertEquals(NodeArmRole.CrashTestDummy, NodeArmAssets.roleFor("Crash Test Dummy"))
        assertEquals(NodeArmRole.QaEngineer, NodeArmAssets.roleFor("QA Engineer"))
        assertEquals(NodeArmRole.AdversarialReviewer, NodeArmAssets.roleFor("Adversarial Reviewer"))
        assertEquals(NodeArmRole.CodeReviewer, NodeArmAssets.roleFor("Code Reviewer"))
        assertEquals(NodeArmRole.RecoveryEngineer, NodeArmAssets.roleFor("Recovery Engineer"))
        assertEquals(NodeArmRole.ReleaseEngineer, NodeArmAssets.roleFor("Release Engineer"))
        assertEquals(NodeArmRole.Antagonist, NodeArmAssets.roleFor("Antagonist"))
        assertEquals(NodeArmRole.HallMonitor, NodeArmAssets.roleFor("Hall Monitor"))
    }

    @Test
    fun relationshipEndpointsUseOppositeStableVariants() {
        val key = "research->implementation:Dependency"
        val source = NodeArmAssets.variantFor(key, endpointIndex = 0)
        val destination = NodeArmAssets.variantFor(key, endpointIndex = 1)
        assertNotEquals(source, destination)
        assertEquals(source, NodeArmAssets.variantFor(key, endpointIndex = 0))
        assertEquals(destination, NodeArmAssets.variantFor(key, endpointIndex = 1))
    }
}
