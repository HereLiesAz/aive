package com.hereliesaz.geministrator.relay

import com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor
import com.hereliesaz.geministrator.distributed.ComputeRelayServerMessage
import com.hereliesaz.geministrator.distributed.DistributedExecutionProgress
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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun originDisconnectKeepsWorkerRunningAndReplaysCompletionOnReconnect() = runBlocking {
        var now = 20_000L
        val pool = RelayPool("pool", nowEpochMillis = { now })
        val origin = RecordingPeer()
        val worker = RecordingPeer()

        pool.register(node("origin", setOf("origin")), origin)
        pool.register(node("worker", setOf("tests")), worker)
        val envelope = envelope()
        pool.publish("origin", envelope)
        pool.claim("worker", envelope.leaseId)
        pool.progress(
            "worker",
            envelope.leaseId,
            DistributedExecutionProgress(
                status = TaskRunStatus.Running,
                progress = 0.5f,
                message = "Halfway",
            ),
        )

        pool.unregister("origin", origin)

        assertEquals(1, pool.pendingLeaseCount())
        assertTrue(worker.messages.none {
            it is ComputeRelayServerMessage.LeaseCancelled &&
                it.reason == "Origin node disconnected"
        })

        now += 1_000
        pool.complete(
            "worker",
            envelope.leaseId,
            DistributedExecutionResult(
                status = TaskRunStatus.Completed,
                progressMessage = "Done",
            ),
        )
        assertEquals(0, pool.pendingLeaseCount())

        val resumedOrigin = RecordingPeer()
        pool.register(node("origin", setOf("origin")), resumedOrigin)
        pool.replayOriginLeases("origin", resumedOrigin)

        assertTrue(resumedOrigin.messages.any {
            it is ComputeRelayServerMessage.LeaseCompleted &&
                it.leaseId == envelope.leaseId &&
                it.result.status == TaskRunStatus.Completed
        })
    }

    @Test
    fun pendingLeaseSurvivesOriginDisconnectAndIsOfferedToLaterWorker() = runBlocking {
        val pool = RelayPool("pool", nowEpochMillis = { 22_000L })
        val origin = RecordingPeer()

        pool.register(node("origin", setOf("origin")), origin)
        val envelope = envelope()
        pool.publish("origin", envelope)
        pool.unregister("origin", origin)

        val lateWorker = RecordingPeer()
        pool.register(node("worker", setOf("tests")), lateWorker)

        assertEquals(1, pool.pendingLeaseCount())
        assertTrue(lateWorker.messages.any {
            it is ComputeRelayServerMessage.LeaseOffered && it.envelope.leaseId == envelope.leaseId
        })
    }

    @Test
    fun retainedTerminalLeaseExpiresAfterResumeWindow() = runBlocking {
        var now = 40_000L
        val pool = RelayPool("pool", nowEpochMillis = { now })
        val origin = RecordingPeer()
        val worker = RecordingPeer()

        pool.register(node("origin", setOf("origin")), origin)
        pool.register(node("worker", setOf("tests")), worker)
        val envelope = envelope()
        pool.publish("origin", envelope)
        pool.claim("worker", envelope.leaseId)
        pool.unregister("origin", origin)
        pool.complete(
            "worker",
            envelope.leaseId,
            DistributedExecutionResult(status = TaskRunStatus.Completed),
        )
        pool.unregister("worker", worker)

        assertTrue(!pool.isEmpty())
        now += 24L * 60L * 60L * 1_000L + 1L
        pool.sweepExpired()
        assertTrue(pool.isEmpty())
    }

    @Test
    fun unfinishedLeaseRequeuesAfterRelayProcessRestart() = runBlocking {
        val directory = Files.createTempDirectory("aive-relay-test")
        val store = EncryptedFileRelayStateStore(directory, "relay-secret")
        val first = RelayPool("pool", nowEpochMillis = { 50_000L }, stateStore = store)
        val origin = RecordingPeer()
        val worker = RecordingPeer()

        first.register(node("origin", setOf("origin")), origin)
        first.register(node("worker", setOf("tests")), worker)
        val envelope = envelope("restart-pending")
        first.publish("origin", envelope)
        first.claim("worker", envelope.leaseId)

        val restarted = RelayPool("pool", nowEpochMillis = { 51_000L }, stateStore = store)
        val resumedOrigin = RecordingPeer()
        val replacementWorker = RecordingPeer()
        restarted.register(node("origin", setOf("origin")), resumedOrigin)
        restarted.replayOriginLeases("origin", resumedOrigin)
        restarted.register(node("worker-2", setOf("tests")), replacementWorker)

        assertEquals(1, restarted.pendingLeaseCount())
        assertTrue(resumedOrigin.messages.any {
            it is ComputeRelayServerMessage.LeaseAccepted && it.leaseId == envelope.leaseId
        })
        assertTrue(resumedOrigin.messages.none {
            it is ComputeRelayServerMessage.LeaseClaimed && it.leaseId == envelope.leaseId
        })
        assertTrue(replacementWorker.messages.any {
            it is ComputeRelayServerMessage.LeaseOffered && it.envelope.leaseId == envelope.leaseId
        })
    }

    @Test
    fun completedLeaseReplaysAfterRelayProcessRestart() = runBlocking {
        val directory = Files.createTempDirectory("aive-relay-test")
        val store = EncryptedFileRelayStateStore(directory, "relay-secret")
        val first = RelayPool("pool", nowEpochMillis = { 60_000L }, stateStore = store)
        val origin = RecordingPeer()
        val worker = RecordingPeer()

        first.register(node("origin", setOf("origin")), origin)
        first.register(node("worker", setOf("tests")), worker)
        val envelope = envelope("restart-complete")
        first.publish("origin", envelope)
        first.claim("worker", envelope.leaseId)
        first.complete(
            "worker",
            envelope.leaseId,
            DistributedExecutionResult(
                status = TaskRunStatus.Completed,
                progressMessage = "Finished before restart",
            ),
        )

        val restarted = RelayPool("pool", nowEpochMillis = { 61_000L }, stateStore = store)
        val resumedOrigin = RecordingPeer()
        restarted.register(node("origin", setOf("origin")), resumedOrigin)
        restarted.replayOriginLeases("origin", resumedOrigin)

        assertEquals(0, restarted.pendingLeaseCount())
        val completed = resumedOrigin.messages
            .filterIsInstance<ComputeRelayServerMessage.LeaseCompleted>()
            .single { it.leaseId == envelope.leaseId }
        assertEquals(TaskRunStatus.Completed, completed.result.status)
        assertEquals("Finished before restart", completed.result.progressMessage)
    }

    @Test
    fun staleConnectionCannotUnregisterReplacementNode() = runBlocking {
        val pool = RelayPool("pool", nowEpochMillis = { 25_000L })
        val oldPeer = RecordingPeer()
        val replacementPeer = RecordingPeer()
        val worker = RecordingPeer()

        pool.register(node("origin", setOf("origin")), oldPeer)
        pool.register(node("worker", setOf("tests")), worker)
        pool.register(node("origin", setOf("origin")), replacementPeer)

        assertFalse(pool.isCurrentPeer("origin", oldPeer))
        assertTrue(pool.isCurrentPeer("origin", replacementPeer))

        pool.unregister("origin", oldPeer)
        val envelope = envelope()
        pool.publish("origin", envelope)

        assertTrue(replacementPeer.messages.any {
            it is ComputeRelayServerMessage.LeaseAccepted && it.leaseId == envelope.leaseId
        })
        assertTrue(worker.messages.none {
            it is ComputeRelayServerMessage.NodeLeft && it.nodeId == "origin"
        })
    }

    @Test
    fun completingLeaseImmediatelyReoffersPendingWorkWhenCapacityFrees() = runBlocking {
        var now = 30_000L
        val pool = RelayPool("pool", nowEpochMillis = { now })
        val origin = RecordingPeer()
        val worker = RecordingPeer()

        pool.register(node("origin", setOf("origin")), origin)
        pool.register(
            node("worker", setOf("tests")).copy(maxParallelLeases = 1),
            worker,
        )

        val first = envelope("lease-1")
        val second = envelope("lease-2")
        pool.publish("origin", first)
        pool.claim("worker", first.leaseId)

        val offersBeforeSecond = worker.messages.count {
            it is ComputeRelayServerMessage.LeaseOffered && it.envelope.leaseId == second.leaseId
        }
        pool.publish("origin", second)
        assertEquals(
            offersBeforeSecond,
            worker.messages.count {
                it is ComputeRelayServerMessage.LeaseOffered && it.envelope.leaseId == second.leaseId
            },
        )

        now += 1_000
        pool.complete(
            "worker",
            first.leaseId,
            DistributedExecutionResult(status = TaskRunStatus.Completed),
        )

        assertTrue(worker.messages.any {
            it is ComputeRelayServerMessage.LeaseOffered && it.envelope.leaseId == second.leaseId
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

    private fun envelope(
        leaseId: String = "lease",
        leaseDurationMillis: Long = 30_000,
    ): DistributedTaskEnvelope {
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
            leaseId = leaseId,
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
