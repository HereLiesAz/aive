package com.hereliesaz.geministrator.azphalt

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

class AzphaltWorkflowConformanceTest {
    @Test
    fun rejectsForbiddenRootExecutionSurface() {
        val errors = AzphaltWorkflowConformance.validate(baseManifest().copy(entry = JsonPrimitive("code.js")))
        assertTrue(errors.any { "root field entry" in it })
    }

    @Test
    fun rejectsExecutablePayloadAnywhereInWorkflowPackage() {
        val errors = AzphaltWorkflowConformance.validate(
            baseManifest().copy(files = baseManifest().files + ("scripts/install.sh" to "sha256-deadbeef")),
        )
        assertTrue(errors.any { "executable payload" in it })
    }

    @Test
    fun rejectsDuplicateDependencyIdVersionPair() {
        val dependency = AzphaltWorkflowDependency("com.example.tool", ">=1.0.0", required = true)
        val manifest = baseManifest().copy(
            workflow = baseManifest().workflow!!.copy(dependencies = listOf(dependency, dependency)),
        )
        val errors = AzphaltWorkflowConformance.validate(manifest)
        assertTrue(errors.any { "duplicate id/version pair" in it })
    }

    @Test
    fun rejectsNonDeclarativeScreenAndUnsafeBackslashPath() {
        val manifest = baseManifest().copy(
            files = baseManifest().files + mapOf(
                "screens/panel.html" to "sha256-one",
                "agents\\builder.json" to "sha256-two",
            ),
            workflow = baseManifest().workflow!!.copy(
                agents = listOf(AzphaltWorkflowAgentEntry("builder", path = "agents\\builder.json")),
                screens = listOf(AzphaltWorkflowScreenEntry("panel", path = "screens/panel.html")),
            ),
        )
        val errors = AzphaltWorkflowConformance.validate(manifest)
        assertTrue(errors.any { "safe relative payload path" in it })
        assertTrue(errors.any { "declarative JSON/YAML" in it })
    }

    private fun baseManifest() = AzphaltManifest(
        azphalt = "0.1",
        id = "com.example.release",
        name = "Release",
        version = "1.0.0",
        kind = "workflow",
        license = "MIT",
        compat = ">=0.1",
        files = mapOf(
            "LICENSE" to "sha256-license",
            "workflows/release.json" to "sha256-workflow",
        ),
        workflow = AzphaltWorkflowManifest(
            format = HAIVE_WORKFLOW_FORMAT,
            definitions = listOf(
                AzphaltWorkflowPayloadEntry("release", path = "workflows/release.json"),
            ),
        ),
    )
}
