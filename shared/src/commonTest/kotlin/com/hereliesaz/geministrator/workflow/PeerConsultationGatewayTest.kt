package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.memory.InMemoryMemoryStore
import com.hereliesaz.geministrator.memory.MEMORY_MESSAGE_MARKER
import com.hereliesaz.geministrator.memory.MemoryBanks
import com.hereliesaz.geministrator.memory.MemoryEngineProvider
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryLayerSettingsStore
import com.hereliesaz.geministrator.memory.MemoryNode
import com.hereliesaz.geministrator.memory.MemoryNodeId
import com.hereliesaz.geministrator.memory.MemoryNodeKind
import com.hereliesaz.geministrator.memory.MemorySessionObserver
import com.hereliesaz.geministrator.memory.MemorySnapshot
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.russhwolf.settings.MapSettings
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.AgentOrchestrationContext
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.CompletableDeferred
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
    fun grantedWakeClaimsBeforeInferenceAndReturnsOnlyToOriginWithMemory() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = AdvisoryProvider()
        val registry = AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings())
        val sender = handle(provider, "architect")
        val recipientContext = PeerConsultationContext("project", "run", "reviewer")
        val route = PeerRoute("project", provider.id.value, "run", "architect", "run", "reviewer", activation = true)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val observer = object : MemorySessionObserver {
            override suspend fun onSessionStarted(handle: ManagedSessionHandle, request: AgentTaskRequest) = Unit
            override suspend fun onSessionEvent(handle: ManagedSessionHandle, event: AgentEvent) = Unit
            override suspend fun onSessionFinished(handle: ManagedSessionHandle, status: ManagedSessionStatus) = Unit
            override suspend fun annotateUserMessage(handle: ManagedSessionHandle, text: String): String =
                text + "\nRecipient cue #evidence"
        }
        try {
            val gateway = ProviderBackedManagedSessionGateway(registry, scope, memoryObserver = observer,
                activity = SessionActivityFeeds(), peerRoster = { listOf(BuiltInRoles.Architect, BuiltInRoles.Reviewer) })
            gateway.dormantSpecialistAnswer = { mail, allowed ->
                assertEquals(PeerDelivery.Claimed, mail.delivery)
                assertEquals("Check evidence", mail.text)
                assertTrue(allowed())
                entered.complete(Unit)
                resume.await()
                DormantSpecialistAnswer("a".repeat(64), "Observed advisory answer")
            }
            gateway.reconnect(sender, ManagedSessionStatus.Running, request(sender, "architect"))
            assertTrue(registry.peerMailbox.grant(route))
            provider.events.send(AgentEvent.Message(sender.providerRunId, "WAKE reviewer\nCheck evidence"))
            withTimeout(5_000L) { entered.await() }
            provider.events.send(AgentEvent.Progress(sender.providerRunId, "Sender continued"))
            withTimeout(5_000L) { while (gateway.progress(sender)?.message != "Sender continued") delay(10) }
            assertEquals(PeerDelivery.Claimed, registry.peerMailbox.receipts(recipientContext, provider.id.value).single().delivery)
            resume.complete(Unit)
            val sent = withTimeout(5_000L) { provider.deliveries.receive() }
            assertEquals(sender.providerRunId, sent.first)
            assertTrue(sent.second.contains("Untrusted dormant specialist assessment from reviewer"))
            assertTrue(sent.second.endsWith("Observed advisory answer\nRecipient cue #evidence"))
            withTimeout(5_000L) {
                while (registry.peerMailbox.receipts(recipientContext, provider.id.value).single().delivery != PeerDelivery.Accepted) delay(10)
            }
            assertEquals("a".repeat(64), registry.peerMailbox.receipts(recipientContext, provider.id.value).single().recipientSpecialist)
        } finally { scope.cancel() }
    }

    @Test
    fun revokedWakeDoesNotReturnAnAlreadyStartedAnswer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = AdvisoryProvider()
        val registry = AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings())
        val sender = handle(provider, "architect")
        val recipientContext = PeerConsultationContext("project", "run", "reviewer")
        val route = PeerRoute("project", provider.id.value, "run", "architect", "run", "reviewer", activation = true)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        try {
            val gateway = ProviderBackedManagedSessionGateway(registry, scope, activity = SessionActivityFeeds(),
                peerRoster = { listOf(BuiltInRoles.Architect, BuiltInRoles.Reviewer) })
            gateway.dormantSpecialistAnswer = { _, _ ->
                entered.complete(Unit)
                resume.await()
                DormantSpecialistAnswer("a".repeat(64), "Must not be delivered")
            }
            gateway.reconnect(sender, ManagedSessionStatus.Running, request(sender, "architect"))
            assertTrue(registry.peerMailbox.grant(route))
            provider.events.send(AgentEvent.Message(sender.providerRunId, "WAKE reviewer\nCheck evidence"))
            withTimeout(5_000L) { entered.await() }
            registry.peerMailbox.revoke(route)
            resume.complete(Unit)
            withTimeout(5_000L) {
                while (registry.peerMailbox.receipts(recipientContext, provider.id.value).single().delivery != PeerDelivery.Rejected) delay(10)
            }
            assertTrue(provider.deliveries.tryReceive().isFailure)
        } finally { scope.cancel() }
    }

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
            withTimeout(5_000L) {
                while (registry.peerMailbox.receipts(recipientContext, provider.id.value).single().delivery != PeerDelivery.Accepted) delay(10)
            }
            withTimeout(5_000L) {
                while (activity.lines(sender.activityKey()).none { it.text == "Transport accepted: Inquiry to reviewer" }) delay(10)
            }
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
            withTimeout(5_000L) {
                while (registry.peerMailbox.receipts(recipientContext, provider.id.value).single().delivery != PeerDelivery.Rejected) delay(10)
            }
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
            withTimeout(5_000L) {
                while (registry.peerMailbox.receipts(destination, provider.id.value).single().delivery != PeerDelivery.Accepted) delay(10)
            }
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

    @Test
    fun recruitmentUsesExactManagedInstanceExperienceWithoutWideningScope() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = AdvisoryProvider()
        val registry = AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings())
        val sender = handle(provider, "architect")
        val reviewer = handle(provider, "reviewer")
        val worker = handle(provider, "worker")
        val reviewRole = BuiltInRoles.Reviewer.copy(description = "database indexing query optimization")
        val workerRole = BuiltInRoles.Worker.copy(description = "illustration typography painting")
        // Use the same persisted session binding contract as the real gateway's start path.
        val key = com.hereliesaz.geministrator.Sha256.hex(listOf(worker.taskRunId.value, worker.providerId.value,
            worker.providerRunId.value).joinToString("") { "${it.length}:$it" }.encodeToByteArray())
        val identity = registry.specialistProfiles.bindManaged(key, workerRole, provider.id.value)
        val directory = object : com.hereliesaz.geministrator.memory.MemoryPromptContextProvider {
            override suspend fun recallFor(request: AgentTaskRequest,
                queryPlan: com.hereliesaz.geministrator.orchestration.MemoryQueryPlan) =
                com.hereliesaz.geministrator.memory.MemoryPromptRecall()
            override suspend fun managedExpertise(projectId: String, runId: String, question: String):
                List<com.hereliesaz.geministrator.memory.CollectiveExpertiseMatch> {
                assertEquals("project", projectId)
                assertEquals("run", runId)
                assertEquals("database indexing", question)
                return listOf(com.hereliesaz.geministrator.memory.CollectiveExpertiseMatch("worker", identity, 1))
            }
        }
        try {
            val gateway = ProviderBackedManagedSessionGateway(registry, scope, activity = SessionActivityFeeds(),
                peerRoster = { listOf(BuiltInRoles.Architect, reviewRole, workerRole) }, memoryContext = directory)
            gateway.reconnect(sender, ManagedSessionStatus.Running, request(sender, "architect"))
            gateway.reconnect(reviewer, ManagedSessionStatus.Running, request(reviewer, "reviewer"))
            gateway.reconnect(worker, ManagedSessionStatus.Running, request(worker, "worker"))
            provider.events.send(AgentEvent.Message(sender.providerRunId, "RECRUIT database indexing\nWhich index fits?"))
            val sent = withTimeout(5_000L) { provider.deliveries.receive() }
            assertEquals(worker.providerRunId, sent.first)
            assertTrue(sent.second.endsWith("; intent: Request\nWhich index fits?"))
            assertTrue(provider.deliveries.tryReceive().isFailure)
        } finally { scope.cancel() }
    }

    @Test
    fun slowPeerTransportDoesNotBlockSenderAndReleaseCancelsDelivery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = AdvisoryProvider(blockDelivery = true)
        val registry = AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings())
        val sender = handle(provider, "architect")
        val recipient = handle(provider, "reviewer")
        val recipientContext = PeerConsultationContext("project", "run", "reviewer")
        try {
            val gateway = ProviderBackedManagedSessionGateway(registry, scope, activity = SessionActivityFeeds(),
                peerRoster = { listOf(BuiltInRoles.Architect, BuiltInRoles.Reviewer) })
            gateway.reconnect(sender, ManagedSessionStatus.Running, request(sender, "architect"))
            gateway.reconnect(recipient, ManagedSessionStatus.Running, request(recipient, "reviewer"))
            provider.events.send(AgentEvent.Message(sender.providerRunId, "CONSULT reviewer\nCheck evidence"))
            withTimeout(5_000L) { provider.sendEntered.await() }
            provider.events.send(AgentEvent.Progress(sender.providerRunId, "Sender continued"))
            withTimeout(5_000L) { while (gateway.progress(sender)?.message != "Sender continued") delay(10) }
            assertEquals(PeerDelivery.Claimed, registry.peerMailbox.receipts(recipientContext, provider.id.value).single().delivery)
            gateway.release(recipient)
            withTimeout(5_000L) { provider.sendExited.await() }
            assertTrue(provider.deliveries.tryReceive().isFailure)
            assertTrue(registry.peerMailbox.claim(recipientContext, provider.id.value).isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun workingGroupFormsOnlyAfterActualVolunteerReply() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = AdvisoryProvider()
        val registry = AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings())
        val sender = handle(provider, "architect")
        val recipient = handle(provider, "reviewer")
        try {
            val gateway = ProviderBackedManagedSessionGateway(registry, scope, activity = SessionActivityFeeds(),
                peerRoster = { listOf(BuiltInRoles.Architect, BuiltInRoles.Reviewer) })
            gateway.reconnect(sender, ManagedSessionStatus.Running, request(sender, "architect"))
            gateway.reconnect(recipient, ManagedSessionStatus.Running, request(recipient, "reviewer"))
            provider.events.send(AgentEvent.Message(sender.providerRunId, "INVITE reviewer\nReview this design"))
            val invite = withTimeout(5_000L) { provider.deliveries.receive() }
            assertEquals(recipient.providerRunId, invite.first)
            assertTrue(invite.second.contains("action: Invite"))
            val messageId = invite.second.substringAfter("Message ID: ").substringBefore(';')
            val groupId = invite.second.substringAfter("Working group: ").substringBefore(';')
            provider.reviewerEvents.send(AgentEvent.Message(recipient.providerRunId,
                "VOLUNTEER architect $messageId\nI can help"))
            val answer = withTimeout(5_000L) { provider.deliveries.receive() }
            assertEquals(sender.providerRunId, answer.first)
            assertTrue(answer.second.contains("action: Volunteer"))
            withTimeout(5_000L) {
                while (registry.peerMailbox.receipts(PeerConsultationContext("project", "run", "architect"),
                        provider.id.value).none { it.groupAction == PeerGroupAction.Volunteer && it.delivery == PeerDelivery.Accepted }) delay(10)
            }
            provider.events.send(AgentEvent.Message(sender.providerRunId, "GROUP-NOTE reviewer $groupId\nDesign finding"))
            val note = withTimeout(5_000L) { provider.deliveries.receive() }
            assertEquals(recipient.providerRunId, note.first)
            assertTrue(note.second.contains("action: Note"))
            assertTrue(note.second.endsWith("Design finding"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun peerDeliveryIncludesActualRecipientMemoryWithoutKeeperAttribution() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = AdvisoryProvider()
        val registry = AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings())
        val sender = handle(provider, "architect")
        val recipient = handle(provider, "reviewer")
        val keeperMessages = Channel<String>(Channel.UNLIMITED)
        val store = InMemoryMemoryStore(MemorySnapshot(nodes = listOf(
            MemoryNode(MemoryNodeId("tag-keystore"), MemoryNodeKind.NounTag, "keystore", createdAtEpochMillis = 0),
        )))
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(mapOf("run" to store)),
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = scope,
            nowEpochMillis = { 1_000L },
        )
        controller.setDefaultAttentionLevel(1f)
        val observer = object : MemorySessionObserver by controller.observer {
            override suspend fun onKeeperMessage(handle: ManagedSessionHandle, text: String) {
                keeperMessages.send(text)
            }
        }
        try {
            val gateway = ProviderBackedManagedSessionGateway(registry, scope, memoryObserver = observer,
                activity = SessionActivityFeeds(), peerRoster = { listOf(BuiltInRoles.Architect, BuiltInRoles.Reviewer) })
            gateway.reconnect(sender, ManagedSessionStatus.Running, request(sender, "architect"))
            gateway.reconnect(recipient, ManagedSessionStatus.Running, request(recipient, "reviewer"))
            provider.events.send(AgentEvent.Message(sender.providerRunId, "CONSULT reviewer\nWhere is the keystore?"))
            val delivered = withTimeout(5_000L) { provider.deliveries.receive() }
            assertEquals(recipient.providerRunId, delivered.first)
            assertTrue(delivered.second.contains("Where is the keystore?\n\n$MEMORY_MESSAGE_MARKER #keystore"), delivered.second)
            assertTrue(keeperMessages.tryReceive().isFailure)
            val receipt = registry.peerMailbox.receipts(PeerConsultationContext("project", "run", "reviewer"), provider.id.value).single()
            assertEquals("Where is the keystore?", receipt.text)
        } finally {
            scope.cancel()
        }
    }

    private class AdvisoryProvider(private val blockDelivery: Boolean = false) : AgentProvider {
        override val id = AgentProviderId("peer-test")
        override val enforcesToolFilter = true
        val events = Channel<AgentEvent>(Channel.UNLIMITED)
        val reviewerEvents = Channel<AgentEvent>(Channel.UNLIMITED)
        val deliveries = Channel<Pair<ProviderRunId, String>>(Channel.UNLIMITED)
        val sendEntered = CompletableDeferred<Unit>()
        val sendExited = CompletableDeferred<Unit>()

        override suspend fun capabilities() = AgentCapabilities(setOf(AgentCapability.Messaging))
        override suspend fun start(request: AgentTaskRequest) = AgentRunHandle(ProviderRunId(request.taskRunId.value))
        override fun observe(runId: ProviderRunId) = flow {
            if (runId.value == "architect") {
                for (event in events) emit(event)
            } else if (runId.value == "reviewer") {
                for (event in reviewerEvents) emit(event)
            } else {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult {
            if (message.startsWith("Untrusted peer consultation") || message.startsWith("Untrusted dormant specialist assessment")) {
                if (blockDelivery) {
                    sendEntered.complete(Unit)
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally { sendExited.complete(Unit) }
                }
                deliveries.send(runId to message)
            }
            return ProviderActionResult.Accepted
        }
        override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
        override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
    }
}
