package com.hereliesaz.geministrator.relay

import com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor
import com.hereliesaz.geministrator.distributed.ComputeRelayServerMessage
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RelayPoolTest {
    @Test
    fun onlyEligibleWorkersReceiveOffersAndFirstClaimWins() = runBlocking {
        var now = 1_000L
        val pool = RelayPool("pool", nowEpochMillis = { now })
        val origin = RecordingPeer()
        val eligible = RecordingPeer()
        val ineligible = RecordingPeer()

        pool.register(node("origin", setOf("origin")), origin)
        pool.register(node("eligible", setOf("tests")), eligible)
        pool.register(node("ineligible", setOf("gpu")), ineligible)

        val envelope = envelope()
        pool.publish("origin", envelope)

        assertTrue(origin.messages.any { it is ComputeRelayServerMessage.LeaseAccepted })
        assertTrue(eligible.messages.any { it is ComputeRelayServerMessage.LeaseOffered })
        assertTrue(ineligible.messages.none { it is ComputeRelayServerMessage.LeaseOffered })

        pool.claim("eligible", envelope.leaseId)
        pool.claim("ineligible", envelope.leaseId)

        val claimed = origin.messages.filterIsInstance<ComputeRelayServerMessage.LeaseClaimed>().single()
        assertEquals("eligible", claimed.workerNodeId)
        assertTrue(
            ineligible.messages.filterIsInstance<ComputeRelayServerMessage.Error>()
                .any { it.code == "lease-already-claimed" || it.code == "worker-ineligible" },
        )
    }

    @Test
    fun expiredWorkerLeaseIsRequeuedAndOfferedAgain() = runBlocking {
        var now = 10_000L
        val pool = RelayPool("pool", nowEpochMillis = { now })
        val origin = RecordingPeer()
        val worker = RecordingPeer()

        pool.register(node("origin", setOf("origin")), origin)
        pool.register(node("worker", setOf("tests")), worker)
        val envelope = envelope(leaseDurationMillis = 5_000)
        pool.publish("origin", envelope)
        pool.claim("worker", envelope.leaseId)

        now += 5_001
        val offersBefore = worker.messages.count { it is ComputeRelayServerMessage.LeaseOffered }
        pool.sweepExpired()

        assertTrue(origin.messages.any { it is ComputeRelayServerMessage.LeaseRequeued })
        assertTrue(worker.messages.any {
            it is ComputeRelayServerMessage.LeaseCancelled &&
                it.reason?.contains("requeued") == true
        })
        assertTrue(worker.messages.count { it is ComputeRelayServerMessage.LeaseOffered } > offersBefore)
    }

    @Test
    fun originDisconnectCancelsOutstandingWorkerLease() = runBlocking {
        val pool = RelayPool("pool", nowEpochMillis = { 20_000L })
        val origin = RecordingPeer()
        val worker = RecordingPeer()

        pool.register(node("origin", setOf("origin")), origin)
        pool.register(node("worker", setOf("tests")), worker)
        val envelope = envelope()
        pool.publish("origin", envelope)
        pool.claim("worker", envelope.leaseId)

        pool.unregister("origin")

        assertEquals(0, pool.pendingLeaseCount())
        assertTrue(worker.messages.any {
            it is ComputeRelayServerMessage.LeaseCancelled &&
                it.reason == "Origin node disconnected"
        })
    }

    private fun node(id: String, capabilities: Set<String>) = ComputeNodeDescriptor(
        nodeId = id,
        displayName = id,
        platform = ComputePlatform.Desktop,
        architecture = "x86_64",
        logicalProcessors = 8,
        memoryMiB = 16_384,
        capabilities = capabilities,
        supportedExecutorKinds = setOf("test-runner"),
        maxParallelLeases = 2,
    )

    private fun envelope(leaseDurationMillis: Long = 30_000): DistributedTaskEnvelope {
        val projectId = ProjectId("project")
        val workflowId = WorkflowDefinitionId("workflow")
        val taskId = TaskDefinitionId("test")
        val taskRunId = TaskRunId("test-run")
        val executor = TaskExecutor.TestRunner("./gradlew test")
        val task = TaskDefinition(
            id = taskId,
            name = "Run tests",
            objective = "Run the test suite",
            roleId = null,
            executor = TaskExecutor.Distributed(
                delegate = executor,
                requirements = DistributedComputeRequirements(
                    requiredCapabilities = setOf("tests"),
                ),
            ),
        )
        val definition = WorkflowDefinition(
            id = workflowId,
            name = "Workflow",
            tasks = listOf(task),
        )
        val taskRun = TaskRun(
            id = taskRunId,
            taskDefinitionId = taskId,
            status = TaskRunStatus.Ready,
            assignedRoleId = null,
            executor = task.executor,
        )
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = projectId,
            workflowDefinitionId = workflowId,
            objective = "Test",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(taskId to taskRun),
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )
        return DistributedTaskEnvelope(
            leaseId = "lease",
            originNodeId = "origin",
            project = Project(
                id = projectId,
                name = "Project",
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
            leaseDurationMillis = leaseDurationMillis,
        )
    }

    private class RecordingPeer : RelayPeer {
        val messages = mutableListOf<ComputeRelayServerMessage>()
        override suspend fun send(message: ComputeRelayServerMessage) {
            messages += message
        }
    }
}
