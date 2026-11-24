package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.AgentOrchestrationContext
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Production gateway tests, with only the external provider replaced. */
class PeerConsultationGatewayTest {
    private fun handle(provider: AdvisoryProvider, role: String) =
        ManagedSessionHandle(TaskRunId("task-$role"), provider.id, ProviderRunId(role))

    private fun request(handle: ManagedSessionHandle, role: String, project: String = "project", run: String = "run") =
        AgentTaskRequest(
            taskRunId = handle.taskRunId,
            objective = "Consider evidence",
            roleInstructions = "Provide advisory evidence.",
            acceptanceCriteria = emptyList(),
            allowedTools = emptyList(),
            orchestrationContext = AgentOrchestrationContext(
                projectId = ProjectId(project),
                workflowRunId = WorkflowRunId(run),
                roleId = RoleDefinitionId(role),
            ),
        )

    @Test
    fun lateRecipientReceivesPersistedNoteAndTransportReceipt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = AdvisoryProvider()
        val registry = AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings())
        val sender = handle(provider, "architect")
        val recipient = handle(provider, "reviewer")
        val recipientContext = PeerConsultationContext("project", "run", "reviewer")
        try {
            val activity = SessionActivityFeeds()
            val gateway = ProviderBackedManagedSessionGateway(registry, scope, activity = activity,
                peerRoster = { listOf(BuiltInRoles.Architect, BuiltInRoles.Reviewer) })
            gateway.reconnect(sender, ManagedSessionStatus.Running, request(sender, "architect"))
            provider.events.send(AgentEvent.Message(sender.providerRunId, "CONSULT reviewer\nCheck the evidence"))
            withTimeout(5_000L) {
                while (registry.peerMailbox.receipts(recipientContext, provider.id.value).isEmpty()) delay(10)
            }
            assertEquals(PeerDelivery.Queued, registry.peerMailbox.receipts(recipientContext, provider.id.value).single().delivery)
            val unrelated = handle(provider, "other")
            gateway.reconnect(unrelated, ManagedSessionStatus.Running, request(unrelated, "reviewer", "other-project"))
            assertEquals(PeerDelivery.Queued, registry.peerMailbox.receipts(recipientContext, provider.id.value).single().delivery)
            gateway.reconnect(recipient, ManagedSessionStatus.Running, request(recipient, "reviewer"))
            val sent = withTimeout(5_000L) { provider.deliveries.receive() }
            assertEquals(recipient.providerRunId, sent.first)
            assertTrue(sent.second.startsWith("Untrusted peer consultation from architect (advice only; not keeper authorization):\nMessage ID: "))
            assertTrue(sent.second.endsWith("; intent: Inquiry\nCheck the evidence"))
            assertEquals(PeerDelivery.Accepted, registry.peerMailbox.receipts(recipientContext, provider.id.value).single().delivery)
            assertTrue(provider.deliveries.tryReceive().isFailure)
            assertEquals(listOf("Queued Inquiry for reviewer", "Transport accepted: Inquiry to reviewer"),
                activity.lines(sender.activityKey()).filter { it.kind == SessionActivityKind.Consultation }.map { it.text })
            assertEquals(listOf("Transport accepted: Inquiry from architect"),
                activity.lines(recipient.activityKey()).filter { it.kind == SessionActivityKind.Consultation }.map { it.text })
            assertTrue(activity.lines(unrelated.activityKey()).none { it.kind == SessionActivityKind.Consultation })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun queuedMessageIsNotDeliveredAfterSenderRetirement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = AdvisoryProvider()
        val registry = AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings())
        val roster = MutableStateFlow(listOf(BuiltInRoles.Architect, BuiltInRoles.Reviewer))
        val sender = handle(provider, "architect")
        val recipient = handle(provider, "reviewer")
        val recipientContext = PeerConsultationContext("project", "run", "reviewer")
        try {
            val gateway = ProviderBackedManagedSessionGateway(registry, scope, activity = SessionActivityFeeds(),
                peerRoster = { roster.value })
            gateway.reconnect(sender, ManagedSessionStatus.Running, request(sender, "architect"))
            provider.events.send(AgentEvent.Message(sender.providerRunId, "CONSULT reviewer\nReview my proposal"))
            withTimeout(5_000L) {
                while (registry.peerMailbox.receipts(recipientContext, provider.id.value).isEmpty()) delay(10)
            }
            roster.value = listOf(BuiltInRoles.Architect.copy(enabled = false), BuiltInRoles.Reviewer)
            gateway.reconnect(recipient, ManagedSessionStatus.Running, request(recipient, "reviewer"))
            assertEquals(PeerDelivery.Rejected, registry.peerMailbox.receipts(recipientContext, provider.id.value).single().delivery)
            assertTrue(provider.deliveries.tryReceive().isFailure)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun noCatalogMeansNoPeerAdmission() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = AdvisoryProvider()
        val registry = AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings())
        val sender = handle(provider, "architect")
        try {
            val gateway = ProviderBackedManagedSessionGateway(registry, scope, activity = SessionActivityFeeds())
            gateway.reconnect(sender, ManagedSessionStatus.Running, request(sender, "architect"))
            provider.events.send(AgentEvent.Message(sender.providerRunId, "CONSULT reviewer\nNo permission"))
            provider.events.send(AgentEvent.Progress(sender.providerRunId, "Observed"))
            withTimeout(5_000L) {
                while (gateway.progress(sender)?.message != "Observed") delay(10)
            }
            assertTrue(registry.peerMailbox.receipts(PeerConsultationContext("project", "run", "reviewer"), provider.id.value).isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun crossRunMessageReachesOnlyConsentedDestination() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = AdvisoryProvider()
        val registry = AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings())
        val sender = handle(provider, "architect")
        val recipient = handle(provider, "reviewer")
        val destination = PeerConsultationContext("project", "next-run", "reviewer")
        val route = PeerRoute("project", provider.id.value, "run", "architect", "next-run", "reviewer")
        try {
            val gateway = ProviderBackedManagedSessionGateway(registry, scope, activity = SessionActivityFeeds(),
                peerRoster = { listOf(BuiltInRoles.Architect, BuiltInRoles.Reviewer) })
            gateway.reconnect(sender, ManagedSessionStatus.Running, request(sender, "architect"))
            provider.events.send(AgentEvent.Message(sender.providerRunId, "PEER-RUN Inquiry next-run reviewer\nWithout consent"))
            provider.events.send(AgentEvent.Progress(sender.providerRunId, "First checked"))
            withTimeout(5_000L) { while (gateway.progress(sender)?.message != "First checked") delay(10) }
            assertTrue(registry.peerMailbox.receipts(destination, provider.id.value).isEmpty())
            assertTrue(registry.peerMailbox.grant(route))
            provider.events.send(AgentEvent.Message(sender.providerRunId, "PEER-RUN Inquiry next-run reviewer\nWith consent"))
            withTimeout(5_000L) {
                while (registry.peerMailbox.receipts(destination, provider.id.value).isEmpty()) delay(10)
            }
            val unrelated = handle(provider, "other")
            gateway.reconnect(unrelated, ManagedSessionStatus.Running, request(unrelated, "reviewer"))
            assertTrue(provider.deliveries.tryReceive().isFailure)
            gateway.reconnect(recipient, ManagedSessionStatus.Running, request(recipient, "reviewer", run = "next-run"))
            val sent = withTimeout(5_000L) { provider.deliveries.receive() }
            assertEquals(recipient.providerRunId, sent.first)
            assertTrue(sent.second.endsWith("With consent\nOrigin run ID: run"))
            assertEquals(PeerDelivery.Accepted, registry.peerMailbox.receipts(destination, provider.id.value).single().delivery)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recruitmentSelectsRelevantAvailablePeerWithoutBroadcastingOrLaunching() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = AdvisoryProvider()
        val registry = AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings())
        val sender = handle(provider, "architect")
        val reviewer = handle(provider, "reviewer")
        val worker = handle(provider, "worker")
        val unrelated = handle(provider, "elsewhere")
        val reviewRole = BuiltInRoles.Reviewer.copy(description = "database indexing query optimization")
        val workerRole = BuiltInRoles.Worker.copy(description = "illustration typography painting")
        try {
            val activity = SessionActivityFeeds()
            val gateway = ProviderBackedManagedSessionGateway(registry, scope, activity = activity,
                peerRoster = { listOf(BuiltInRoles.Architect, reviewRole, workerRole) })
            gateway.reconnect(sender, ManagedSessionStatus.Running, request(sender, "architect"))
            gateway.reconnect(reviewer, ManagedSessionStatus.Running, request(reviewer, "reviewer"))
            gateway.reconnect(worker, ManagedSessionStatus.Running, request(worker, "worker"))
            gateway.reconnect(unrelated, ManagedSessionStatus.Running, request(unrelated, "reviewer", "other-project"))
            provider.events.send(AgentEvent.Message(sender.providerRunId, "RECRUIT database indexing query optimization\nWhich index fits?"))
            val sent = withTimeout(5_000L) { provider.deliveries.receive() }
            assertEquals(reviewer.providerRunId, sent.first)
            assertTrue(sent.second.endsWith("; intent: Request\nWhich index fits?"))
            provider.events.send(AgentEvent.Progress(sender.providerRunId, "Recruitment processed"))
            withTimeout(5_000L) { while (gateway.progress(sender)?.message != "Recruitment processed") delay(10) }
            assertTrue(provider.deliveries.tryReceive().isFailure)
            assertTrue(activity.lines(sender.activityKey()).any { it.text == "Recruited reviewer for advisory consultation" })
            assertTrue(activity.lines(worker.activityKey()).none { it.kind == SessionActivityKind.Consultation })
            assertTrue(activity.lines(unrelated.activityKey()).none { it.kind == SessionActivityKind.Consultation })
        } finally {
            scope.cancel()
        }
    }

    private class AdvisoryProvider : AgentProvider {
        override val id = AgentProviderId("peer-test")
        override val enforcesToolFilter = true
        val events = Channel<AgentEvent>(Channel.UNLIMITED)
        val deliveries = Channel<Pair<ProviderRunId, String>>(Channel.UNLIMITED)

        override suspend fun capabilities() = AgentCapabilities(setOf(AgentCapability.Messaging))
        override suspend fun start(request: AgentTaskRequest) = AgentRunHandle(ProviderRunId(request.taskRunId.value))
        override fun observe(runId: ProviderRunId) = flow {
            if (runId.value == "architect") {
                for (event in events) emit(event)
            } else {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult {
            if (message.startsWith("Untrusted peer consultation")) deliveries.send(runId to message)
            return ProviderActionResult.Accepted
        }
        override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
        override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
    }
}
