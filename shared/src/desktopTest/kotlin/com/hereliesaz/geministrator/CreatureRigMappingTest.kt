package com.hereliesaz.geministrator

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.geministrator.puppet.CompiledPuppetRig
import com.hereliesaz.geministrator.puppet.PuppetRig
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreatureRigMappingTest {
    private val dir = File("src/commonMain/composeResources/files/rigs")

    private fun rig(slug: String) = PuppetRig.parse(File(dir, "$slug.rig.json").readText())

    @Test
    fun everyMappedSlugHasARigAndAtlas() {
        CreatureRigMapping.allSlugs.forEach { slug ->
            val file = File(dir, "$slug.rig.json")
            assertTrue(file.isFile, "missing rig for $slug")
            assertTrue(File(dir, rig(slug).atlas.image).isFile, "missing atlas for $slug")
        }
    }

    /**
     * The expected mapping, written out by hand. It is deliberately not derived from
     * [CreatureRigMapping.bySlugKind]: a test that reads its expectations from the table it checks
     * passes whatever the table says.
     */
    private val expectedRigForRole = mapOf(
        "orchestrator" to "orchestrator",
        "ux designer" to "ux-designer",
        "researcher" to "researcher",
        "implementation engineer" to "implementation-engineer",
        "epa representative" to "epa-representative",
        "adversarial reviewer" to "adversarial-reviewer",
        "qa engineer" to "qa-engineer",
        "release engineer" to "release-engineer",
        "antagonist" to "antagonist",
        "product manager" to "product-manager",
        "crash test dummy" to "crash-test-dummy",
        "recovery engineer" to "recovery-engineer",
        "code reviewer" to "code-reviewer",
        "hall monitor" to "hall-monitor",
        "architect" to "architect",
        "data miner" to "data-miner-sheet-3-variant",
        "knowledge keeper" to "knowledge-keeper-sheet-2-variant",
        "pattern seeker" to "pattern-seeker-sheet-1-variant",
        "translator" to "translator-sheet-1-variant",
        "connector" to "connector-sheet-1-variant",
        "scheduler" to "scheduler-sheet-3-variant",
        "optimizer" to "optimizer-sheet-3-variant",
        "simulator" to "simulator",
        "documenter" to "documenter",
        "guardian" to "guardian",
        "creative synthesizer" to "creative-synthesizer",
        "resource manager" to "resource-manager-sheet-3-variant",
        "feedback listener" to "feedback-listener",
        "decision helper" to "decision-helper",
        "flow director" to "flow-director",
        "pattern seer" to "pattern-seer",
        "communicator" to "communicator",
        "integrator" to "integrator",
        "security analyst" to "security-analyst",
        "quality guardian" to "quality-guardian",
        "documentation specialist" to "documentation-specialist",
        "test explorer" to "test-explorer",
        "deployment pilot" to "deployment-pilot",
        "monitoring sentinel" to "monitoring-sentinel",
        "feedback collector" to "feedback-collector",
        "idea catalyst" to "idea-catalyst-2",
        "process mapper" to "process-mapper",
        "strategy scout" to "strategy-scout",
        "system maintainer" to "system-maintainer",
        "sustainability advocate" to "sustainability-advocate",
        "systems analyst" to "systems-analyst",
        "database administrator" to "database-administrator",
        "network engineer" to "network-engineer",
        "site reliability engineer" to "site-reliability-engineer",
        "build engineer" to "build-engineer",
        "test automation engineer" to "test-automation-engineer",
        "threat modeler" to "threat-modeler",
        "compliance officer" to "compliance-officer",
        "migration engineer" to "migration-engineer",
        "observability engineer" to "observability-engineer",
        "business analyst" to "business-analyst",
        "content strategist" to "content-strategist",
        "support agent" to "support-agent",
        "data scientist" to "data-scientist",
        "ml engineer" to "ml-engineer",
        "legal counsel" to "legal-counsel",
    )

    @Test
    fun builtInRoleNamesResolveToTheirRig() {
        expectedRigForRole.forEach { (label, slug) ->
            assertEquals(slug, CreatureRigMapping.slugFor(label), "label '$label'")
        }
        assertEquals(expectedRigForRole.size, CreatureRigMapping.bySlugKind.size, "every mapped role is in the expected table")
        assertEquals("simulator", CreatureRigMapping.slugFor("Simulator"))
        assertEquals("ux-designer", CreatureRigMapping.slugFor("UX Designer"))
        assertEquals("data-miner-sheet-3-variant", CreatureRigMapping.slugFor("Data Miner"))
        assertEquals("idea-catalyst-2", CreatureRigMapping.slugFor("Idea Catalyst"))
    }

    @Test
    fun everyRigFileIsReachable() {
        val onDisk = dir.listFiles { f -> f.name.endsWith(".rig.json") }
            .orEmpty().map { it.name.removeSuffix(".rig.json") }.toSet()
        assertEquals(onDisk, CreatureRigMapping.allSlugs, "rig files and reachable slugs must match")
    }

    @Test
    fun galleryCoversEverySlug() {
        assertEquals(CreatureRigMapping.allSlugs, creatureRigGalleryEntries.mapNotNull(CreatureRigMapping::slugFor).toSet())
    }

    @Test
    fun everyLabelResolvesToAnExistingRig() {
        val labels = buildList {
            addAll(MascotCharacterCatalog.explicitCharacters.map { it.roleLabel })
            addAll(CreatureRigMapping.bySlugKind.keys.map(CreatureRigMapping::displayName))
            addAll(CreatureRigMapping.AUTOMATED_PROCESS_RIGS.keys)
            addAll(listOf("Python", "JavaScript", "Test Runner", "Deployment", "Repository Operation", "Nested Workflow"))
            addAll(listOf("Data Engineer", "Content Creator", "Technical Author", "Bug Reporter", "World Builder", "Storyteller", "Pathfinder"))
            addAll(listOf("", "   ", "Orchestrator", "UX Designer", "Weather Oracle", "creature", "Zorblax"))
            addAll((0 until 200).map { "Custom Role $it" })
        }
        labels.forEach { label ->
            val slug = CreatureRigMapping.slugFor(label)
            assertTrue(slug in CreatureRigMapping.allSlugs, "label '$label' -> $slug")
            assertTrue(File(dir, "$slug.rig.json").isFile, "label '$label' -> $slug has no rig file")
        }
        assertTrue(CreatureRigMapping.slugFor("") in CreatureRigMapping.GENERIC_SLUGS)
    }

    @Test
    fun everyRoleKindHasACreature() {
        val own = CreatureRigMapping.bySlugKind.keys + CreatureRigMapping.KIND_FALLBACKS.keys
        val missing = NodeCreatureRoleKind.entries - own - NodeCreatureRoleKind.Generic - NodeCreatureRoleKind.AutomatedProcess
        assertEquals(emptyList(), missing, "role kinds without a creature or fallback")
        (CreatureRigMapping.KIND_FALLBACKS.values + CreatureRigMapping.AUTOMATED_PROCESS_RIGS.values +
            CreatureRigMapping.DEFAULT_AUTOMATED_RIG).forEach {
            assertTrue(it in CreatureRigMapping.allSlugs, "fallback creature $it is not a mapped rig")
        }
    }

    @Test
    fun personasExecutorsAndUnclassifiedRolesMapExplicitly() {
        assertEquals("orchestrator", CreatureRigMapping.slugFor("Film Showrunner"))
        assertEquals("content-strategist", CreatureRigMapping.slugFor("Brand Strategist"))
        assertEquals("adversarial-reviewer", CreatureRigMapping.slugFor("Contract Reviewer"))
        assertEquals("process-mapper", CreatureRigMapping.slugFor("Python"))
        assertEquals("process-mapper", CreatureRigMapping.slugFor("JavaScript"))
        assertEquals("test-automation-engineer", CreatureRigMapping.slugFor("Test Runner"))
        assertEquals("deployment-pilot", CreatureRigMapping.slugFor("Deployment"))
        assertEquals("build-engineer", CreatureRigMapping.slugFor("Repository Operation"))
        assertEquals("flow-director", CreatureRigMapping.slugFor("Nested Workflow"))
        assertEquals("database-administrator", CreatureRigMapping.slugFor("Data Engineer"))
        assertEquals("communicator", CreatureRigMapping.slugFor("Storyteller"))
        assertEquals("strategy-scout", CreatureRigMapping.slugFor("Pathfinder"))
    }

    @Test
    fun genericRedesignRigsNeverUseLegacyNodeReferences() {
        val report = Json.parseToJsonElement(
            File("../tools/puppet-rig/slice/rigs-report.json").readText(),
        ).jsonArray
        val genericEntries = report
            .map { it.jsonObject }
            .filter { it.getValue("slug").jsonPrimitive.content in CreatureRigMapping.GENERIC_SLUGS }

        assertEquals(
            CreatureRigMapping.GENERIC_SLUGS.toSet(),
            genericEntries.map { it.getValue("slug").jsonPrimitive.content }.toSet(),
        )
        genericEntries.forEach { entry ->
            assertEquals(JsonNull, entry.getValue("ref"), entry.getValue("slug").jsonPrimitive.content)
            assertEquals(false, entry.getValue("used_ref").jsonPrimitive.boolean)
            assertEquals(0, entry.getValue("tendrils_matched").jsonPrimitive.int)
        }
    }

    @Test
    fun unknownRolesGetAStableGenericRig() {
        val labels = (0 until 200).map { "Zorblax Wrangler $it" }
        val slugs = labels.map { CreatureRigMapping.slugFor(it) }
        slugs.forEach { assertTrue(it in CreatureRigMapping.GENERIC_SLUGS, "$it") }
        assertEquals(slugs, labels.map { CreatureRigMapping.slugFor(it) })
        assertEquals(CreatureRigMapping.slugFor("Weather Oracle"), CreatureRigMapping.slugFor("  weather oracle "))
        assertEquals(CreatureRigMapping.GENERIC_SLUGS.toSet(), slugs.toSet(), "all six generic bodies get used")
    }

    @Test
    fun socketTransformMapsArtboardIntoTheZoomedNodeBox() {
        val r = rig("simulator") // 374.88 x 510.79 artboard
        val box = 200f
        val center = Offset(500f, 300f)
        // Artboard centre lands on the node centre at any zoom.
        listOf(0.7f, 1f, 2f).forEach { zoom ->
            val c = creatureRigPointToScreen(r.canvas.width / 2, r.canvas.height / 2, r, box, center, zoom)
            assertTrue(abs(c.x - center.x) < 1e-3f && abs(c.y - center.y) < 1e-3f, "$c at $zoom")
        }
        // Top-left corner: height-limited fit, scaled by content scale and zoom.
        val s = box / r.canvas.height * CREATURE_RIG_CONTENT_SCALE
        val tl = creatureRigPointToScreen(0f, 0f, r, box, center, zoom = 2f)
        assertEquals(center.x - r.canvas.width / 2 * s * 2f, tl.x, 1e-3f)
        assertEquals(center.y - r.canvas.height / 2 * s * 2f, tl.y, 1e-3f)
    }

    @Test
    fun armSocketFacesThePartner() {
        val compiled = CompiledPuppetRig(rig("simulator"))
        val pose = compiled.newBuffer()
        compiled.evaluate(compiled.stateIndex("Active"), 0.3f, pose)
        val right = pickArmSocket(compiled, pose, 1f, 0f)
        val left = pickArmSocket(compiled, pose, -1f, 0f)
        assertEquals("arm.socket.right", compiled.attachmentIds[right])
        assertEquals("arm.socket.left", compiled.attachmentIds[left])
        // Sockets sit low on the soma: nothing faces straight up, so the caller falls back.
        assertEquals(-1, pickArmSocket(compiled, pose, 0f, -1f))
    }
}
