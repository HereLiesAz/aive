package com.hereliesaz.geministrator.relay

import com.hereliesaz.geministrator.distributed.ComputeNodeAuth
import com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor
import com.hereliesaz.geministrator.distributed.ComputeNodeEnrolmentRequest
import com.hereliesaz.geministrator.distributed.ComputeNodeSigner
import com.hereliesaz.geministrator.distributed.ComputeRelayServerMessage
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
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Per-node identity: enrolment, signed session requests, revocation and attribution. */
class RelayNodeIdentityTest {
    private val pool = "pool"
    private var now = 1_000_000L

    @Test
    fun enrolmentRecordsTheKeyAndRepeatingItWithTheSameKeyIsHarmless(): Unit = runBlocking {
        val relay = RelayPool(pool, nowEpochMillis = { now })
        val desk = TestSigner()

        val enrolled = relay.enrol(enrolment(desk, "desk"))
        relay.enrol(enrolment(desk, "desk", displayName = "Desk (renamed)"))

        val nodes = relay.enrolledNodes()
        assertEquals(listOf("desk"), nodes.map { it.nodeId })
        assertEquals("Desk (renamed)", nodes.single().displayName)
        assertEquals(now, enrolled.enrolledAtEpochMillis)
        assertEquals(16, nodes.single().publicKeyFingerprint.length)
    }

    @Test
    fun anEnrolledNodeIdCannotBeTakenOverWithAnotherKey(): Unit = runBlocking {
        val relay = RelayPool(pool, nowEpochMillis = { now })
        relay.enrol(enrolment(TestSigner(), "desk"))

        val refused = assertFailsWith<NodeEnrolmentException> { relay.enrol(enrolment(TestSigner(), "desk")) }
        assertEquals(ComputeNodeAuth.ERROR_NODE_ID_TAKEN, refused.code)
    }

    @Test
    fun enrolmentWithoutProofOfTheKeyIsRefused(): Unit = runBlocking {
        val relay = RelayPool(pool, nowEpochMillis = { now })
        val claimed = TestSigner()
        // Signed by a different key than the one it asks to enrol.
        val forged = enrolment(TestSigner(), "desk").copy(publicKey = claimed.publicKey)

        val refused = assertFailsWith<NodeEnrolmentException> { relay.enrol(forged) }
        assertEquals(ComputeNodeAuth.ERROR_BAD_SIGNATURE, refused.code)
        assertTrue(relay.enrolledNodes().isEmpty())
    }

    @Test
    fun aStaleEnrolmentIsRefused(): Unit = runBlocking {
        val relay = RelayPool(pool, nowEpochMillis = { now })
        val request = enrolment(TestSigner(), "desk", at = now - ComputeNodeAuth.MAX_CLOCK_SKEW_MILLIS - 1)

        val refused = assertFailsWith<NodeEnrolmentException> { relay.enrol(request) }
        assertEquals(ComputeNodeAuth.ERROR_STALE_REQUEST, refused.code)
    }

    @Test
    fun onlyAValidFreshSignatureByTheEnrolledKeyAuthenticates(): Unit = runBlocking {
        val relay = RelayPool(pool, nowEpochMillis = { now })
        val desk = TestSigner()
        relay.enrol(enrolment(desk, "desk"))

        assertEquals(NodeAuthentication.Authenticated, relay.authenticateWith(desk, "desk", nonce = "a".repeat(32)))
        // The same request again is a replay.
        assertEquals(NodeAuthentication.Replayed, relay.authenticateWith(desk, "desk", nonce = "a".repeat(32)))
        // Another key cannot sign for the node.
        assertEquals(NodeAuthentication.BadSignature, relay.authenticateWith(TestSigner(), "desk"))
        // A request signed for another pool does not verify here.
        assertEquals(NodeAuthentication.BadSignature, relay.authenticateWith(desk, "desk", signedForPool = "other-pool"))
        assertEquals(
            NodeAuthentication.StaleRequest,
            relay.authenticateWith(desk, "desk", at = now - ComputeNodeAuth.MAX_CLOCK_SKEW_MILLIS - 1),
        )
        assertEquals(NodeAuthentication.NotEnrolled, relay.authenticateWith(TestSigner(), "stranger"))
        assertEquals(NodeAuthentication.Malformed, relay.authenticate("desk", "not-a-number", "b".repeat(32), "c2ln"))
        assertEquals(NodeAuthentication.Malformed, relay.authenticate(null, null, null, null))
        assertTrue(relay.enrolledNodes().single().lastSeenAtEpochMillis == now)
    }

    @Test
    fun aRevokedNodeIsRejectedAndCannotEnrolAgain(): Unit = runBlocking {
        val relay = RelayPool(pool, nowEpochMillis = { now })
        val keeper = TestSigner()
        val rogue = TestSigner()
        relay.enrol(enrolment(keeper, "keeper"))
        relay.enrol(enrolment(rogue, "rogue"))
        val keeperPeer = RecordingPeer()
        val roguePeer = RecordingPeer()
        relay.register(node("keeper"), keeperPeer)
        relay.register(node("rogue"), roguePeer)

        relay.revoke("keeper", "rogue", ownerAuthority = true)

        // Its live session is told and closed, and the others see it leave.
        assertTrue(roguePeer.messages.any { it is ComputeRelayServerMessage.Error && it.code == ComputeNodeAuth.ERROR_NODE_REVOKED })
        assertEquals("Node revoked", roguePeer.closedWith)
        assertTrue(keeperPeer.messages.any { it is ComputeRelayServerMessage.NodeLeft && it.nodeId == "rogue" })
        val listed = keeperPeer.messages.filterIsInstance<ComputeRelayServerMessage.EnrolledNodes>().last().nodes
        assertEquals("keeper", listed.single { it.nodeId == "rogue" }.revokedByNodeId)
        assertTrue(listed.single { it.nodeId == "rogue" }.revoked)

        // Its key no longer opens sessions, its ID cannot come back, and it cannot register.
        assertEquals(NodeAuthentication.Revoked, relay.authenticateWith(rogue, "rogue"))
        val reEnrol = assertFailsWith<NodeEnrolmentException> { relay.enrol(enrolment(rogue, "rogue")) }
        assertEquals(ComputeNodeAuth.ERROR_NODE_REVOKED, reEnrol.code)
        assertFailsWith<NodeRevokedException> { relay.register(node("rogue"), RecordingPeer()) }

        // A revoked node cannot revoke.
        assertFailsWith<IllegalStateException> { relay.revoke("rogue", "keeper") }
    }

    @Test
    fun aNodeRevokesOnlyItselfAndOnlyTheOwnerRevokesOthersOrReEnrols(): Unit = runBlocking {
        val relay = RelayPool(pool, nowEpochMillis = { now })
        val keeper = TestSigner()
        val rogue = TestSigner()
        relay.enrol(enrolment(keeper, "keeper"))
        relay.enrol(enrolment(rogue, "rogue"))

        assertFailsWith<IllegalStateException> { relay.revoke("rogue", "keeper") }
        assertEquals(NodeAuthentication.Authenticated, relay.authenticateWith(keeper, "keeper"))

        relay.revoke("rogue", "rogue")
        assertEquals(NodeAuthentication.Revoked, relay.authenticateWith(rogue, "rogue"))
        assertFailsWith<NodeEnrolmentException> { relay.enrol(enrolment(rogue, "rogue")) }

        relay.enrol(enrolment(rogue, "rogue"), ownerAuthority = true)
        assertEquals(NodeAuthentication.Authenticated, relay.authenticateWith(rogue, "rogue"))
    }

    @Test
    fun revokingAWorkerRequeuesItsLeaseAndRevokingAnOriginCancelsItsLeases(): Unit = runBlocking {
        val relay = RelayPool(pool, nowEpochMillis = { now })
        listOf("keeper", "worker", "other").forEach { relay.enrol(enrolment(TestSigner(), it)) }
        val keeper = RecordingPeer()
        val worker = RecordingPeer()
        val other = RecordingPeer()
        relay.register(node("keeper"), keeper)
        relay.register(node("worker"), worker)
        relay.register(node("other"), other)

        val held = envelope(leaseId = "held", origin = "keeper")
        relay.publish("keeper", held)
        relay.claim("worker", held.leaseId)
        val published = envelope(leaseId = "published", origin = "worker")
        relay.publish("worker", published)
        relay.claim("other", published.leaseId)

        relay.revoke("keeper", "worker", ownerAuthority = true)

        assertTrue(keeper.messages.any { it is ComputeRelayServerMessage.LeaseRequeued && it.leaseId == "held" })
        assertTrue(other.messages.any { it is ComputeRelayServerMessage.LeaseCancelled && it.leaseId == "published" })
        // Only the requeued lease is left, waiting for another worker.
        assertEquals(1, relay.pendingLeaseCount())
    }

    @Test
    fun aCompletedLeaseIsAttributedToTheNodeThatRanIt(): Unit = runBlocking {
        val relay = RelayPool(pool, nowEpochMillis = { now })
        listOf("origin", "worker").forEach { relay.enrol(enrolment(TestSigner(), it)) }
        val origin = RecordingPeer()
        relay.register(node("origin"), origin)
        relay.register(node("worker"), RecordingPeer())

        val first = envelope(leaseId = "first", origin = "origin")
        relay.publish("origin", first)
        relay.claim("worker", first.leaseId)
        assertNull(relay.completedBy(first.leaseId))
        relay.complete("worker", first.leaseId, DistributedExecutionResult(status = TaskRunStatus.Completed))

        val second = envelope(leaseId = "second", origin = "origin")
        relay.publish("origin", second)
        relay.claim("worker", second.leaseId)
        relay.complete(
            "worker",
            second.leaseId,
            DistributedExecutionResult(status = TaskRunStatus.Failed, failureMessage = "boom"),
        )

        assertEquals("worker", relay.completedBy(first.leaseId))
        assertEquals(
            "worker",
            origin.messages.filterIsInstance<ComputeRelayServerMessage.LeaseCompleted>().first { it.leaseId == "first" }.workerNodeId,
        )
        val record = relay.enrolledNodes().single { it.nodeId == "worker" }
        assertEquals(1, record.leasesCompleted)
        assertEquals(1, record.leasesFailed)
        assertEquals(0, relay.enrolledNodes().single { it.nodeId == "origin" }.leasesCompleted)
    }

    @Test
    fun enrolmentsAndRevocationsSurviveARelayRestartWithSavedState(): Unit = runBlocking {
        val directory = Files.createTempDirectory("relay-identity")
        val keeper = TestSigner()
        val rogue = TestSigner()
        val before = RelayPool(pool, nowEpochMillis = { now }, stateStore = EncryptedFileRelayStateStore(directory, "token"))
        before.enrol(enrolment(keeper, "keeper"))
        before.enrol(enrolment(rogue, "rogue"))
        before.revoke("keeper", "rogue", ownerAuthority = true)

        val after = RelayPool(pool, nowEpochMillis = { now }, stateStore = EncryptedFileRelayStateStore(directory, "token"))

        assertEquals(NodeAuthentication.Authenticated, after.authenticateWith(keeper, "keeper"))
        assertEquals(NodeAuthentication.Revoked, after.authenticateWith(rogue, "rogue"))
        assertFalse(after.isEmpty(), "a pool with enrolled nodes is kept")
    }

    private suspend fun RelayPool.authenticateWith(
        signer: TestSigner,
        nodeId: String,
        at: Long = now,
        nonce: String = ComputeNodeAuth.newNonce(),
        signedForPool: String = pool,
    ): NodeAuthentication {
        val headers = ComputeNodeAuth.signedConnectHeaders(signer, signedForPool, nodeId, at, nonce)
        return authenticate(
            headers.getValue(ComputeNodeAuth.HEADER_NODE_ID),
            headers.getValue(ComputeNodeAuth.HEADER_TIMESTAMP),
            headers.getValue(ComputeNodeAuth.HEADER_NONCE),
            headers.getValue(ComputeNodeAuth.HEADER_SIGNATURE),
        )
    }

    private fun enrolment(
        signer: TestSigner,
        nodeId: String,
        displayName: String = nodeId,
        at: Long = now,
    ): ComputeNodeEnrolmentRequest = ComputeNodeAuth.enrolmentRequest(signer, pool, nodeId, displayName, at)

    private fun node(id: String) = ComputeNodeDescriptor(
        nodeId = id,
        displayName = id,
        platform = ComputePlatform.Desktop,
        architecture = "x86_64",
        logicalProcessors = 8,
        memoryMiB = 16_384,
        capabilities = setOf("tests"),
        supportedExecutorKinds = setOf("test-runner"),
        maxParallelLeases = 4,
    )

    private fun envelope(leaseId: String, origin: String): DistributedTaskEnvelope {
        val projectId = ProjectId("project")
        val workflowId = WorkflowDefinitionId("workflow")
        val taskId = TaskDefinitionId("test")
        val executor = TaskExecutor.TestRunner("./gradlew test")
        val requirements = DistributedComputeRequirements(requiredCapabilities = setOf("tests"))
        val task = TaskDefinition(
            id = taskId,
            name = "Run tests",
            objective = "Run the test suite",
            roleId = null,
            executor = TaskExecutor.Distributed(delegate = executor, requirements = requirements),
        )
        val taskRun = TaskRun(
            id = TaskRunId("test-run-$leaseId"),
            taskDefinitionId = taskId,
            status = TaskRunStatus.Ready,
            assignedRoleId = null,
            executor = task.executor,
        )
        return DistributedTaskEnvelope(
            leaseId = leaseId,
            originNodeId = origin,
            project = Project(id = projectId, name = "Project", createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
            definition = WorkflowDefinition(id = workflowId, name = "Workflow", tasks = listOf(task)),
            run = WorkflowRun(
                id = WorkflowRunId("run"),
                projectId = projectId,
                workflowDefinitionId = workflowId,
                objective = "Test",
                status = WorkflowRunStatus.Running,
                taskRuns = mapOf(taskId to taskRun),
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
            ),
            task = task,
            taskRun = taskRun,
            delegatedExecutor = executor,
            requirements = requirements,
            submittedAtEpochMillis = 1,
        )
    }

    private class RecordingPeer : RelayPeer {
        val messages = mutableListOf<ComputeRelayServerMessage>()
        var closedWith: String? = null

        override suspend fun send(message: ComputeRelayServerMessage) {
            messages += message
        }

        override suspend fun close(reason: String) {
            closedWith = reason
        }
    }
}

/** A software P-256 node key, as a desktop node holds one. */
internal class TestSigner(
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair(),
) : ComputeNodeSigner {
    override val publicKey: String = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private)
        update(message)
        sign()
    }
}
