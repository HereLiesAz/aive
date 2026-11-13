package com.hereliesaz.geministrator.distributed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LeaseMessageIdTest {
    @Test
    fun leaseMessagesNameTheirLeaseAndOthersDoNot() {
        assertEquals("l1", leaseMessageId(ComputeRelayServerMessage.LeaseAccepted("l1")))
        assertNull(leaseMessageId(ComputeRelayServerMessage.NodeLeft("n")))
    }
}
