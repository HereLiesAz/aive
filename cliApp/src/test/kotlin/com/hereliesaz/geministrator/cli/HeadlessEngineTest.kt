package com.hereliesaz.geministrator.cli

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.mcpbridge.ControlUnreachable
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HeadlessEngineTest {
    @Test fun argumentValidationIsStrict() {
        assertEquals(HeadlessRunOptions("Ship", null, "Headless"), HeadlessRunArguments.parse(listOf("--objective", "Ship")))
        assertEquals(HeadlessRunOptions("Inspect", "audit", "Ops"), HeadlessRunArguments.parse(
            listOf("--hive", "audit", "--objective", "Inspect", "--project", "Ops"),
        ))
        assertFailsWith<IllegalArgumentException> { HeadlessRunArguments.parse(emptyList()) }
        assertFailsWith<IllegalArgumentException> { HeadlessRunArguments.parse(listOf("--objective", "x", "--objective", "y")) }
        assertFailsWith<IllegalArgumentException> { HeadlessRunArguments.parse(listOf("--hive")) }
        assertFailsWith<IllegalArgumentException> { HeadlessRunArguments.parse(listOf("--secret", "no")) }
    }

    @Test fun providerSecretsComeOnlyFromEnvironmentAndNeverFromAConfigFile() {
        val keys = HeadlessCredentials.providers(mapOf(
            "OPENAI_API_KEY" to "  test-openai  ",
            "ANTHROPIC_API_KEY" to " ",
            "GITHUB_TOKEN" to "gh-secret",
        ))
        assertEquals(mapOf("openai" to "test-openai"), keys)
        assertEquals(mapOf("github" to "gh-secret"), HeadlessCredentials.repositories(mapOf("GITHUB_TOKEN" to "gh-secret")))
        assertTrue("gh-secret" !in keys.values)
    }

    @Test fun localControlServerUsesTheHeadlessStateAndRejectsBadCredentials(): Unit = runBlocking {
        val directory = Files.createTempDirectory("aive-headless-control-").toFile()
        try {
            HeadlessEngine.open(directory, emptyMap()).use { engine ->
                HeadlessControlServer.start(engine, directory, port = 0).use { server ->
                    val client = HeadlessConnection.transport(directory, emptyMap())
                    val status = client.send("GET", "/status", null)
                    assertEquals(200, status.status)
                    assertEquals("The Aive", Json.parseToJsonElement(status.body)
                        .let { it as kotlinx.serialization.json.JsonObject }["result"]
                        .let { it as kotlinx.serialization.json.JsonObject }["app"]?.jsonPrimitive?.content)
                    val wrong = com.hereliesaz.geministrator.mcpbridge.HttpControlTransport(server.address, { "wrong-token" })
                    assertEquals(401, wrong.send("GET", "/status", null).status)
                }
            }
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test fun separateEngineProcessCannotOwnTheSameStateDirectory(): Unit = runBlocking {
        val directory = Files.createTempDirectory("aive-headless-lock-").toFile()
        try {
            HeadlessEngine.open(directory, emptyMap()).use {
                assertFailsWith<IllegalStateException> {
                    runBlocking { HeadlessEngine.open(directory, emptyMap()) }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test fun humanApprovalTaskActuallyRunsAndSurvivesRestartWithoutDesktopApp(): Unit = runBlocking {
        val directory = Files.createTempDirectory("aive-headless-run-").toFile()
        try {
            val runId = HeadlessEngine.open(directory, emptyMap()).use { engine ->
                val hive = engine.controlHost.createHive(buildJsonObject {
                    put("name", "Human gate")
                    put("creatures", buildJsonArray {
                        add(buildJsonObject { put("id", "gate"); put("name", "Approve"); put("kind", "approval") })
                    })
                })
                val id = hive["id"]!!.jsonPrimitive.content
                val run = engine.controlHost.startRun(buildJsonObject {
                    put("hiveId", id)
                    put("objective", "Check the approval boundary")
                    put("projectName", "Headless test")
                })
                val runId = WorkflowRunId(run["id"]!!.jsonPrimitive.content)
                withTimeout(15_000) {
                    while (engine.runtime.persistence.runs.get(runId)?.taskRuns
                        ?.get(TaskDefinitionId("gate"))?.status != TaskRunStatus.AwaitingApproval
                    ) delay(100)
                }
                // The HTTP control surface cannot decide this gate; the owner terminal calls the runtime.
                engine.runtime.approveTask(TaskDefinitionId("gate"))
                withTimeout(15_000) {
                    while (engine.runtime.persistence.runs.get(runId)?.status != WorkflowRunStatus.Completed) {
                        delay(100)
                    }
                }
                runId
            }
            HeadlessEngine.open(directory, emptyMap()).use { reopened ->
                assertEquals(WorkflowRunStatus.Completed, reopened.runtime.persistence.runs.get(runId)?.status)
                assertEquals("The Aive", reopened.controlHost.status()["app"]?.jsonPrimitive?.content)
            }
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test fun clientDoesNotFallBackToTheGraphicalDesktopControlServer() {
        val directory = Files.createTempDirectory("aive-headless-discovery-").toFile()
        try {
            assertFailsWith<ControlUnreachable> { HeadlessConnection.transport(directory, emptyMap()) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
