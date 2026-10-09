package com.hereliesaz.geministrator.relay

import com.hereliesaz.geministrator.distributed.DistributedExecutionResult
import com.hereliesaz.geministrator.distributed.DistributedTaskEnvelope
import com.hereliesaz.geministrator.domain.ComputePlatform
import com.hereliesaz.geministrator.domain.DistributedComputeRequirements
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayStateStoreTest {
    @Test
    fun encryptedStoreRoundTripsWithoutPlaintextEnvelope() {
        val directory = Files.createTempDirectory("aive-relay-state")
        val store = EncryptedFileRelayStateStore(directory, "shared-relay-token")
        val lease = PersistedRelayLease(
            envelope = envelope("lease-sensitive"),
            originNodeId = "origin",
        )

        store.save("private-pool", listOf(lease))

        val bytes = Files.readAllBytes(store.stateFile("private-pool"))
        val raw = bytes.toString(StandardCharsets.ISO_8859_1)
        assertFalse(raw.contains("Sensitive project objective"))
        assertFalse(raw.contains("private-pool"))
        assertFalse(raw.contains("lease-sensitive"))

        val loaded = store.load("private-pool")
        assertEquals(1, loaded.size)
        assertEquals("lease-sensitive", loaded.single().envelope.leaseId)
        assertEquals("Sensitive project objective", loaded.single().envelope.run.objective)
    }

    @Test
    fun wrongRelayTokenCannotDecryptPersistedState() {
        val directory = Files.createTempDirectory("aive-relay-state")
        EncryptedFileRelayStateStore(directory, "correct-token").save(
            "pool",
            listOf(PersistedRelayLease(envelope("lease"), "origin")),
        )

        val wrong = EncryptedFileRelayStateStore(directory, "wrong-token")
        assertFailsWith<AEADBadTagException> {
            wrong.load("pool")
        }
    }

    @Test
    fun ciphertextTamperingFailsAuthentication() {
        val directory = Files.createTempDirectory("aive-relay-state")
        val store = EncryptedFileRelayStateStore(directory, "token")
        store.save("pool", listOf(PersistedRelayLease(envelope("lease"), "origin")))
        val path = store.stateFile("pool")
        val bytes = Files.readAllBytes(path)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        Files.write(path, bytes)

        assertFailsWith<AEADBadTagException> {
            store.load("pool")
        }
    }

    @Test
    fun deletingLastLeaseRemovesStateFile() {
        val directory = Files.createTempDirectory("aive-relay-state")
        val store = EncryptedFileRelayStateStore(directory, "token")
        store.save("pool", listOf(PersistedRelayLease(envelope("lease"), "origin")))
        assertTrue(Files.exists(store.stateFile("pool")))

        store.save("pool", emptyList())

        assertFalse(Files.exists(store.stateFile("pool")))
    }

    @Test
    fun terminalResultRoundTripsWithArtifactsAndStatus() {
        val directory = Files.createTempDirectory("aive-relay-state")
        val store = EncryptedFileRelayStateStore(directory, "token")
        val result = DistributedExecutionResult(
            status = TaskRunStatus.Completed,
            progressMessage = "done",
        )
        store.save(
            "pool",
            listOf(
                PersistedRelayLease(
                    envelope = envelope("complete"),
                    originNodeId = "origin",
                    workerNodeId = "worker",
                    result = result,
                    completedAtEpochMillis = 1234L,
                ),
            ),
        )

        val loaded = store.load("pool").single()
        assertEquals("worker", loaded.workerNodeId)
        assertEquals(TaskRunStatus.Completed, loaded.result?.status)
        assertEquals(1234L, loaded.completedAtEpochMillis)
    }

    private fun envelope(leaseId: String): DistributedTaskEnvelope {
        val projectId = ProjectId("project")
        val workflowId = WorkflowDefinitionId("workflow")
        val taskId = TaskDefinitionId("task")
        val executor = TaskExecutor.TestRunner("./gradlew test")
        val task = TaskDefinition(
            id = taskId,
            name = "Task",
            objective = "Sensitive project objective",
            roleId = null,
            executor = TaskExecutor.Distributed(
                delegate = executor,
                requirements = DistributedComputeRequirements(requiredCapabilities = setOf("tests")),
            ),
        )
        val definition = WorkflowDefinition(
            id = workflowId,
            name = "Workflow",
            tasks = listOf(task),
        )
        val taskRun = TaskRun(
            id = TaskRunId("task-run"),
            taskDefinitionId = taskId,
            status = TaskRunStatus.Ready,
            assignedRoleId = null,
            executor = task.executor,
        )
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = projectId,
            workflowDefinitionId = workflowId,
            objective = "Sensitive project objective",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(taskId to taskRun),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )
        return DistributedTaskEnvelope(
            leaseId = leaseId,
            originNodeId = "origin",
            project = Project(
                id = projectId,
                name = "Sensitive Project",
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
            ),
            definition = definition,
            run = run,
            task = task,
            taskRun = taskRun,
            delegatedExecutor = executor,
            requirements = DistributedComputeRequirements(requiredCapabilities = setOf("tests")),
            submittedAtEpochMillis = 1,
        )
    }
}
