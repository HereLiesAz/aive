package com.hereliesaz.geministrator.distributed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DistributedComputeWorkerTest {
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
