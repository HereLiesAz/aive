package com.hereliesaz.geministrator.azphalt

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Adaptive Tutoring package teaches from the material in three ungated steps, and its manifest matches its files. */
class AdaptiveTutoringPackageTest {
    private val dir = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/azphalt-packages/adaptive-tutoring") }.first { it.isDirectory }
    private val json = Json { ignoreUnknownKeys = true; allowStructuredMapKeys = true }

    @Test
    fun manifestDigestsMatchTheFiles() {
        val files = json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject.getValue("files").jsonObject
        for ((path, digest) in files) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(dir, path).readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals("sha256-$actual", digest.jsonPrimitive.content, path)
        }
    }

    @Test
    fun itSurveysThenPlansThenTeachesWithoutWaitingOnAnApproval() {
        val workflow = json.decodeFromString(WorkflowDefinition.serializer(), File(dir, "workflows/adaptive-tutoring.json").readText())
        val role = json.decodeFromString(RoleDefinition.serializer(), File(dir, "roles/learning-coach.json").readText())
        assertEquals(3, workflow.tasks.size)
        assertEquals(listOf(0, 1, 1), workflow.tasks.map { it.dependsOn.size })
        assertTrue(workflow.tasks.all { it.approvalPolicy == ApprovalPolicy.None && it.roleId == role.id })
        assertTrue("README" in role.instructions || "repository" in role.instructions)
    }
}
