package com.hereliesaz.geministrator.distributed

import com.hereliesaz.geministrator.domain.ScriptLanguage
import com.hereliesaz.geministrator.domain.ScriptRunner
import com.hereliesaz.geministrator.domain.TaskExecutor
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DistributedComputeWorkerTest {
    @Test
    fun remoteLeasesRefuseGitHubActionsExecutors() {
        assertNotNull(remoteLeaseExecutorRefusal(TaskExecutor.GitHubAction(workflow = "ci.yml")))
        assertNotNull(
            remoteLeaseExecutorRefusal(
                TaskExecutor.Script(ScriptLanguage.Python, "print(1)", ScriptRunner.GitHubActions("ci.yml")),
            ),
        )
        assertNull(
            remoteLeaseExecutorRefusal(
                TaskExecutor.Script(ScriptLanguage.Python, "print(1)", ScriptRunner.LocalSandbox),
            ),
        )
    }

    @Test
    fun disconnectedRelayInvalidatesClaimedWork() {
        assertEquals(
            "Distributed relay disconnected while the lease was running",
            workerLeaseInvalidationReason(
                connected = false,
                state = DistributedLeaseState(
                    leaseId = "lease",
                    phase = DistributedLeasePhase.Running,
                    workerNodeId = "worker-a",
                ),
                nodeId = "worker-a",
            ),
        )
    }

    @Test
    fun cancelledOrRequeuedLeaseInvalidatesWorker() {
        assertEquals(
            "stop now",
            workerLeaseInvalidationReason(
                connected = true,
                state = DistributedLeaseState(
                    leaseId = "lease",
                    phase = DistributedLeasePhase.Cancelled,
                    workerNodeId = "worker-a",
                    progressMessage = "stop now",
                ),
                nodeId = "worker-a",
            ),
        )
        assertEquals(
            "worker lease expired",
            workerLeaseInvalidationReason(
                connected = true,
                state = DistributedLeaseState(
                    leaseId = "lease",
                    phase = DistributedLeasePhase.Pending,
                    progressMessage = "worker lease expired",
                ),
                nodeId = "worker-a",
            ),
        )
    }

    @Test
    fun reassignedLeaseInvalidatesOldWorker() {
        assertEquals(
            "Distributed lease moved to another worker",
            workerLeaseInvalidationReason(
                connected = true,
                state = DistributedLeaseState(
                    leaseId = "lease",
                    phase = DistributedLeasePhase.Running,
                    workerNodeId = "worker-b",
                ),
                nodeId = "worker-a",
            ),
        )
    }

    @Test
    fun currentlyOwnedAndTerminalLeasesDoNotCancelExecutionGuard() {
        assertNull(
            workerLeaseInvalidationReason(
                connected = true,
                state = DistributedLeaseState(
                    leaseId = "lease",
                    phase = DistributedLeasePhase.Verifying,
                    workerNodeId = "worker-a",
                ),
                nodeId = "worker-a",
            ),
        )
        assertNull(
            workerLeaseInvalidationReason(
                connected = true,
                state = DistributedLeaseState(
                    leaseId = "lease",
                    phase = DistributedLeasePhase.Completed,
                    workerNodeId = "worker-a",
                ),
                nodeId = "worker-a",
            ),
        )
    }
}
