package com.hereliesaz.geministrator.distributed

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
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalEncodingApi::class)
class ComputeNodeIdentityTest {
    // An engine left open outlives the test and can report its failure to whichever test runs next
    // (kotlinx.coroutines.test.UncaughtExceptionsBeforeTest), so every client a test made is closed.
    private val opened = mutableListOf<HttpClient>()

    private fun trackedClient(
        engine: io.ktor.client.engine.HttpClientEngine,
        config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {},
    ): HttpClient = HttpClient(engine, config).also { opened += it }

    @AfterTest
    fun closeOpenedClients() {
        opened.forEach { it.close() }
        opened.clear()
    }

    @Test
    fun signedConnectHeadersCarryTheNodeTimestampNonceAndASignatureOfThoseFields() {
        val headers = ComputeNodeAuth.signedConnectHeaders(FakeSigner, "pool", "desk", 1_234L, "0123456789abcdef")

        assertEquals("desk", headers[ComputeNodeAuth.HEADER_NODE_ID])
        assertEquals("1234", headers[ComputeNodeAuth.HEADER_TIMESTAMP])
        assertEquals("0123456789abcdef", headers[ComputeNodeAuth.HEADER_NONCE])
        val signed = Base64.Default.decode(headers.getValue(ComputeNodeAuth.HEADER_SIGNATURE)).decodeToString()
        assertEquals("signed:aive-compute-connect\nv$DISTRIBUTED_COMPUTE_PROTOCOL_VERSION\npool\ndesk\n1234\n0123456789abcdef", signed)
    }

    @Test
    fun aFieldCannotSmuggleALineIntoASignedMessage() {
        assertFailsWith<IllegalArgumentException> { ComputeNodeAuth.connectMessage("pool", "desk\nother", 1L, "0123456789abcdef") }
        assertFailsWith<IllegalArgumentException> { ComputeNodeAuth.connectMessage("pool", "desk", 1L, "short") }
        assertFalse(ComputeNodeAuth.isValidField(" "))
        assertTrue(ComputeNodeAuth.isValidNonce(ComputeNodeAuth.newNonce()))
    }

    @Test
    fun enrolmentGoesToTheHttpEndpointWithThePoolTokenAndAProofOfTheKey() = runTest {
        var seenUrl = ""
        var seenAuthorization: String? = null
        var seenBody = ""
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            seenAuthorization = request.headers[HttpHeaders.Authorization]
            seenBody = (request.body as TextContent).text
            respond(
                """{"nodeId":"desk","enrolledAtEpochMillis":7}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val enroller = ComputeNodeEnroller(trackedClient(engine), "wss://relay.example/", "pool", "pool-token", FakeSigner, { 99L })

        val response = enroller.enrol("desk", "Desk")

        assertEquals("desk", response.nodeId)
        assertEquals("https://relay.example/v1/compute/pool/enrol", seenUrl)
        assertEquals("Bearer pool-token", seenAuthorization)
        val request = RelayDistributedComputeClient.defaultJson.decodeFromString(ComputeNodeEnrolmentRequest.serializer(), seenBody)
        assertEquals(FakeSigner.publicKey, request.publicKey)
        assertEquals(99L, request.timestampEpochMillis)
        assertEquals(
            "signed:" + ComputeNodeAuth.enrolmentMessage("pool", "desk", "Desk", FakeSigner.publicKey, 99L).decodeToString(),
            Base64.Default.decode(request.signature).decodeToString(),
        )
    }

    @Test
    fun aClientEnrolsOnceAndRemembersIt() = runTest {
        var enrolments = 0
        val engine = MockEngine {
            enrolments++
            respond(
                """{"nodeId":"desk","enrolledAtEpochMillis":7}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val ledger = InMemoryComputeNodeEnrolmentLedger()
        val client = client(trackedClient(engine), ledger)

        client.ensureEnrolled()
        client.ensureEnrolled()
        assertEquals(1, enrolments)

        // The relay forgetting the node (it answers node-not-enrolled) makes the next connection enrol again.
        ledger.forget()
        client.ensureEnrolled()
        assertEquals(2, enrolments)
    }

    @Test
    fun aRevokedIdentityStopsTheClient() = runTest {
        val engine = MockEngine {
            respond(
                """{"code":"node-revoked","message":"Node ID desk was revoked"}""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val ledger = InMemoryComputeNodeEnrolmentLedger()
        val client = client(trackedClient(engine), ledger)

        val refused = assertFailsWith<ComputeNodeEnrolmentException> { client.ensureEnrolled() }

        assertEquals(ComputeNodeAuth.ERROR_NODE_REVOKED, refused.code)
        assertTrue(client.revoked.value)
        assertFalse(ledger.isEnrolled(ComputeNodeEnrolmentLedger.fingerprint("wss://relay.example", "pool", "desk", FakeSigner.publicKey)))
    }

    @Test
    fun theOriginRecordsWhichNodeRanAFinishedLease() = runTest {
        val gateway = FakeGateway(
            DistributedLeaseState(leaseId = "lease", phase = DistributedLeasePhase.Completed, workerNodeId = "desk"),
        )
        val integration = DistributedComputeExecutorIntegration(gateway)

        val completed = integration.reconcile(context(externalRunId = "lease"))
        assertEquals(TaskRunStatus.Completed, completed.status)
        assertEquals("Remote execution completed (ran on desk)", completed.progressMessage)

        gateway.state = DistributedLeaseState(
            leaseId = "lease",
            phase = DistributedLeasePhase.Failed,
            workerNodeId = "phone",
            failureMessage = "boom",
        )
        assertEquals("boom (ran on phone)", integration.reconcile(context(externalRunId = "lease")).progressMessage)
    }

    private fun client(httpClient: HttpClient, ledger: ComputeNodeEnrolmentLedger) = RelayDistributedComputeClient(
        httpClient = httpClient,
        relayBaseUrl = "wss://relay.example",
        poolId = "pool",
        bearerToken = "pool-token",
        initialNode = ComputeNodeDescriptor(
            nodeId = "desk",
            displayName = "Desk",
            platform = ComputePlatform.Desktop,
            architecture = "x86_64",
            logicalProcessors = 4,
            memoryMiB = 8_192,
        ),
        signer = FakeSigner,
        enrolmentLedger = ledger,
        nowEpochMillis = { 1_000L },
    )

    private fun context(externalRunId: String): TaskExecutorContext {
        val projectId = ProjectId("project")
        val workflowId = WorkflowDefinitionId("workflow")
        val taskId = TaskDefinitionId("test")
        val executor = TaskExecutor.Distributed(
            delegate = TaskExecutor.TestRunner("./gradlew test"),
            requirements = DistributedComputeRequirements(),
        )
        val task = TaskDefinition(id = taskId, name = "Run tests", objective = "Run the tests", roleId = null, executor = executor)
        val taskRun = TaskRun(
            id = TaskRunId("test-run"),
            taskDefinitionId = taskId,
            status = TaskRunStatus.Running,
            assignedRoleId = null,
            executor = executor,
        ).copy(externalRunId = externalRunId)
        return TaskExecutorContext(
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
            executor = executor,
            nowEpochMillis = 2,
        )
    }

    private class FakeGateway(var state: DistributedLeaseState) : DistributedComputeGateway {
        override val localNodeId: String = "origin"
        override fun isAvailable(): Boolean = true
        override fun leaseState(leaseId: String): DistributedLeaseState? = state.takeIf { it.leaseId == leaseId }
        override suspend fun submit(envelope: DistributedTaskEnvelope) = Unit
        override suspend fun cancel(leaseId: String, reason: String?) = Unit
    }

    /** Signs by tagging the message, so a test can read back exactly what was signed. */
    private object FakeSigner : ComputeNodeSigner {
        override val publicKey: String = "UFVCTElDLUtFWQ=="
        override fun sign(message: ByteArray): ByteArray = "signed:".encodeToByteArray() + message
    }
}
