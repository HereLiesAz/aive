package com.hereliesaz.geministrator

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

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
    fun transformUsesActualTerminalPivotAndUniformZoom() {
        val asset = NodeArmAsset(
            role = NodeArmRole.Orchestrator,
            variant = NodeArmVariant.A,
        )
        val atOneHundredPercent = assertNotNull(
            calculateNodeArmTransform(
                asset = asset,
                socket = Offset(20f, 50f),
                toward = Offset(188f, 50f),
                baseWidthPx = 200f,
                baseHeightPx = 100f,
                zoom = 1f,
                pulse = 0f,
                active = false,
                blocked = false,
            ),
        )
        assertEquals(1f, atOneHundredPercent.scaleX, 0.0001f)
        assertEquals(1f, atOneHundredPercent.scaleY, 0.0001f)
        assertEquals(0f, atOneHundredPercent.leftPx, 0.0001f)
        assertEquals(0f, atOneHundredPercent.topPx, 0.0001f)

        val atTwoHundredPercent = assertNotNull(
            calculateNodeArmTransform(
                asset = asset,
                socket = Offset(20f, 50f),
                toward = Offset(356f, 50f),
                baseWidthPx = 200f,
                baseHeightPx = 100f,
                zoom = 2f,
                pulse = 0f,
                active = false,
                blocked = false,
            ),
        )
        assertEquals(2f, atTwoHundredPercent.scaleX, 0.0001f)
        assertEquals(2f, atTwoHundredPercent.scaleY, 0.0001f)
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
