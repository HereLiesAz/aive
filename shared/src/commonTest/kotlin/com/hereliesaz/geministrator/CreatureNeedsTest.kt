package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.domain.RoleDefinition
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CreatureNeedsTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer
    private val roles = listOf(engineer, qa)

    private fun task(id: String, role: RoleDefinition, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "Do $id",
        roleId = role.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(),
        executor = TaskExecutor.RoleAgent(role.id),
    )

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("needs"),
        name = "Needs",
        tasks = listOf(
            task("plan", engineer),
            task("build", engineer, "plan"),
            task("review", qa, "build"),
        ),
    )

    private fun run(vararg tasks: Triple<String, TaskRunStatus, Pair<String, Int>>) = WorkflowRun(
        id = WorkflowRunId("run"),
        projectId = ProjectId("p"),
        workflowDefinitionId = definition.id,
        objective = "o",
        status = WorkflowRunStatus.Running,
        taskRuns = tasks.associate { (id, status, providerAndAttempt) ->
            val t = definition.tasks.first { it.id.value == id }
            t.id to TaskRun(
                id = TaskRunId("r-$id"),
                taskDefinitionId = t.id,
                status = status,
                attempt = providerAndAttempt.second,
                assignedRoleId = t.roleId,
                assignedProviderId = AgentProviderId(providerAndAttempt.first),
                providerRunId = ProviderRunId("session-$id"),
                artifacts = listOf(ArtifactRef(ArtifactId("artifact-$id"), ArtifactKind.Research,
                    TaskRunId("r-$id"), id, textContent = "work", createdAtEpochMillis = 1L)),
            )
        },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    /** Explicit evidence per exact session, not inferred from its role. */
    private fun reports(run: WorkflowRun) = run.taskRuns.values.filter { it.status != TaskRunStatus.Ready }.map { task ->
        ProviderUsageRecorded(run.id,task.taskDefinitionId,requireNotNull(task.assignedProviderId),
            requireNotNull(task.providerRunId),model="model-a",modelConsistent=true,occurredAtEpochMillis=2L,
            deliveredArtifactIds = when (task.taskDefinitionId.value) {
                "build" -> setOf("artifact-plan")
                "review" -> setOf("artifact-build")
                else -> emptySet()
            })
    }

    @Test
    fun readingANearClonesOutputIsEcho() {
        // build reads plan; delivery to QA does not show that build received any feedback.
        val r=run(
            Triple("plan", TaskRunStatus.Completed, "a" to 1),
            Triple("build", TaskRunStatus.Completed, "a" to 1),
            Triple("review", TaskRunStatus.Running, "b" to 1),
        )
        val needs=assertNotNull(creatureNeeds(TaskDefinitionId("build"),definition,r,roles,reports(r)))
        assertEquals(100, needs.echo)
        assertEquals(EchoState.Grounded, needs.echoState)
        assertNull(needs.echoHundredths)
        assertEquals("plan", needs.nearClone)
        assertEquals(0, needs.selfReviews)
        assertEquals(0, needs.reviews)
        assertEquals(100, needs.outputUsed)
        assertEquals(0, needs.reviewApprovals)
    }

    @Test
    fun retriesAndFailureCostIntegrity() {
        val needs = assertNotNull(
            creatureNeeds(
                TaskDefinitionId("build"),
                definition,
                run(Triple("build", TaskRunStatus.Failed, "a" to 3)),
                roles,
            ),
        )
        assertEquals(30, needs.integrity)
        assertNull(needs.outputUsed)
    }

    @Test
    fun budgetIsWhatTheAgentHasLeftAcrossTheRun() {
        val budgeted = engineer.copy(tokenBudget = 1_000)
        val spent = run(
            Triple("plan", TaskRunStatus.Completed, "a" to 1),
            Triple("build", TaskRunStatus.Running, "a" to 1),
        ).let { r ->
            r.copy(
                taskRuns = r.taskRuns.mapValues { (_, t) ->
                    t.copy(usage = com.hereliesaz.geministrator.domain.TaskUsage(inputTokens = 200, outputTokens = 50))
                },
            )
        }
        val needs = assertNotNull(creatureNeeds(TaskDefinitionId("build"), definition, spent, listOf(budgeted, qa)))
        // plan and build are the same agent: 500 of 1000 spent
        assertEquals(50, needs.budget)
        assertEquals(250L, needs.tokensSpent)
    }

    @Test
    fun budgetIsNotReportedAndTheLastTaskHasNoDownstream() {
        val needs = assertNotNull(creatureNeeds(TaskDefinitionId("review"), definition, run(), roles))
        assertNull(needs.budget)
        assertNull(needs.tokensSpent)
        assertNull(needs.outputUsed)
        assertEquals(0, needs.echo)
    }

    @Test
    fun clonedRoleIdsAndCosmeticNamesDoNotAlterConfigurationKinship() {
        val clone=engineer.copy(id=com.hereliesaz.geministrator.domain.RoleDefinitionId("new-id"),
            name="Different name",parents=listOf(engineer.id),enabled=false)
        assertEquals(100,configurationKinship(engineer,"provider-a",clone,"provider-a","model-a","model-a"))
        assertEquals(80,configurationKinship(engineer,"provider-a",clone,"provider-b","model-a","model-a"))
        assertEquals(70,configurationKinship(engineer,"provider-a",clone,"provider-a","model-a","model-b"))
        assertEquals(70,configurationKinship(engineer,"provider-a",clone.copy(instructions="Different instructions"),"provider-a","model-a","model-a"))
        assertEquals(80,configurationKinship(engineer,"provider-a",clone.copy(toolCeiling=listOf("read")),"provider-a","model-a","model-a"))
        assertNull(configurationKinship(engineer,null,clone,null,"model-a","model-a"))
        assertNull(configurationKinship(engineer,"provider-a",clone,"provider-a",null,"model-a"))
    }

    @Test
    fun echoUsesRunSnapshotRatherThanLaterRosterAndIgnoresFuturePaths() {
        val clone=engineer.copy(id=com.hereliesaz.geministrator.domain.RoleDefinitionId("clone"),name="Clone")
        val d=definition.copy(tasks=listOf(task("plan",clone),task("build",engineer,"plan"),task("review",qa,"build")))
        val plan=d.tasks[0]
        val build=d.tasks[1]
        val review=d.tasks[2]
        val r=run(
            Triple("plan",TaskRunStatus.Completed,"a" to 1),
            Triple("build",TaskRunStatus.Completed,"a" to 1),
            Triple("review",TaskRunStatus.Ready,"b" to 1),
        ).let { original -> original.copy(roleSnapshot=listOf(clone,engineer,qa),
            taskRuns=original.taskRuns + (plan.id to original.taskRuns.getValue(plan.id).copy(assignedRoleId=clone.id))) }
        val needs=assertNotNull(creatureNeeds(build.id,d,r,listOf(engineer.copy(instructions="Edited after launch"),qa),reports(r)))
        assertEquals("plan",needs.nearClone)
        assertEquals(100,needs.echo)
        assertEquals(1,needs.echoComparablePaths)
        assertEquals(0,needs.reviews)
        val unknown=r.copy(taskRuns=r.taskRuns+(plan.id to r.taskRuns.getValue(plan.id).copy(assignedProviderId=null)))
        val missing=assertNotNull(creatureNeeds(build.id,d,unknown,roles,reports(r)))
        assertNull(missing.nearClone)
        assertEquals(0,missing.echoComparablePaths)
    }

    @Test
    fun programmaticDependencyIsNotACloneEvenWithSameRoleAndProvider() {
        val d=definition.copy(tasks=definition.tasks.map {
            if(it.id.value=="plan") it.copy(executor=TaskExecutor.TestRunner("./gradlew test")) else it
        })
        val r=run(Triple("plan",TaskRunStatus.Completed,"a" to 1),Triple("build",TaskRunStatus.Running,"a" to 1))
        val needs=assertNotNull(creatureNeeds(TaskDefinitionId("build"),d,r,roles,reports(r)))
        assertNull(needs.nearClone)
        assertEquals(1,needs.echoComparablePaths)
        assertEquals(0,needs.echo)
    }

    @Test
    fun schedulingAndUnknownOrStaleDeliveryNeverCountAsExposure() {
        val r = run(Triple("plan", TaskRunStatus.Completed, "a" to 1),
            Triple("build", TaskRunStatus.Completed, "a" to 1),
            Triple("review", TaskRunStatus.Running, "b" to 1))
        val build = TaskDefinitionId("build")
        val events = reports(r)
        val review = events.single { it.taskDefinitionId.value == "review" }
        val own = events.single { it.taskDefinitionId == build }
        fun needs(evidence: List<ProviderUsageRecorded>) =
            assertNotNull(creatureNeeds(build, definition, r, roles, evidence))
        val without = events.map { it.copy(deliveredArtifactIds = null) }
        assertNull(needs(without).outputUsed)
        assertEquals(0, needs(without).echoComparablePaths)
        assertNull(needs(listOf(own, review.copy(providerRunId = ProviderRunId("old")))).outputUsed)
        assertNull(needs(listOf(own, review.copy(tokensAvoided = 5))).outputUsed)
        assertEquals(0, needs(listOf(own, review.copy(deliveredArtifactIds = setOf("unrelated")))).outputUsed)
        assertEquals(100, needs(events + review).outputUsed)
        assertEquals(1, needs(events + review).echoComparablePaths)
    }

    @Test
    fun reviewOutcomesRequireDeliveryAndAnUnambiguousFinishedVerdict() {
        val r = run(Triple("plan", TaskRunStatus.Completed, "a" to 1),
            Triple("build", TaskRunStatus.Completed, "a" to 1),
            Triple("review", TaskRunStatus.Completed, "b" to 1))
        val build = TaskDefinitionId("build")
        val review = TaskDefinitionId("review")
        val reviewer = r.taskRuns.getValue(review)
        val artifact = reviewer.artifacts.single().copy(kind = ArtifactKind.Verification,
            textContent = "VERDICT: approve")
        val evidence = reports(r)
        fun withReview(vararg artifacts: ArtifactRef, status: TaskRunStatus = TaskRunStatus.Completed,
            reason: BlockingReason? = null) = r.copy(taskRuns = r.taskRuns + (review to
            reviewer.copy(artifacts = artifacts.toList(), status = status, blockingReason = reason)))
        fun needs(run: WorkflowRun, d: WorkflowDefinition = definition,
            reports: List<ProviderUsageRecorded> = evidence) =
            assertNotNull(creatureNeeds(build, d, run, roles, reports))

        val approved = needs(withReview(artifact))
        assertEquals(1, approved.reviewApprovals)
        assertEquals(1, approved.reviews)
        assertEquals(100, approved.echo) // outgoing verdict was never delivered back to build
        assertEquals(0, needs(withReview(artifact), reports = emptyList()).reviewApprovals)
        assertEquals(0, needs(withReview(artifact, status = TaskRunStatus.Running)).reviewApprovals)
        assertEquals(0, needs(withReview(artifact, status = TaskRunStatus.Failed)).reviewApprovals)
        val rejected = artifact.copy(textContent = "VERDICT: reject")
        val blocked = BlockingReason("gate-verdict-blocked", "The gate turned work back.")
        assertEquals(1, needs(withReview(rejected, status = TaskRunStatus.Failed, reason = blocked)).reviewRejections)
        assertEquals(0, needs(withReview(artifact, status = TaskRunStatus.Failed, reason = blocked)).reviewApprovals)
        assertEquals(1, needs(withReview(artifact.copy(textContent = "VERDICT: revise"))).reviewRevisions)
        assertEquals(0, needs(withReview(artifact, rejected.copy(id = ArtifactId("other-review")))).reviews)
        assertEquals(0, needs(withReview(artifact.copy(textContent = "Looks good"))).reviewApprovals)
        val multiple = definition.copy(tasks = definition.tasks.map {
            if (it.id == review) it.copy(dependsOn = setOf(build, TaskDefinitionId("plan"))) else it
        })
        assertEquals(0, needs(withReview(artifact), d = multiple).reviewApprovals)
        val stale = evidence.map {
            if (it.taskDefinitionId == review) it.copy(providerRunId = ProviderRunId("prior")) else it
        }
        assertEquals(0, needs(withReview(artifact), reports = stale).reviewApprovals)
        val extra = evidence.map {
            if (it.taskDefinitionId == review) it.copy(deliveredArtifactIds = setOf("artifact-build", "unrelated")) else it
        }
        assertEquals(0, needs(withReview(artifact), reports = extra).reviewApprovals)
    }

    @Test
    fun hallMonitorVerdictMustNameTheDeliveredReport() {
        val r = run(Triple("build", TaskRunStatus.Completed, "a" to 1),
            Triple("review", TaskRunStatus.Completed, "b" to 1))
        val build = TaskDefinitionId("build")
        val review = TaskDefinitionId("review")
        val producer = r.taskRuns.getValue(build)
        val reviewer = r.taskRuns.getValue(review)
        val report = producer.artifacts.single().copy(kind = ArtifactKind.HallMonitorReport,
            metadata = mapOf("hall-monitor-report-id" to "report-42"))
        val verdict = reviewer.artifacts.single().copy(kind = ArtifactKind.HallMonitorReview,
            metadata = mapOf("hall-monitor-report-id" to "report-42", "hall-monitor-verdict" to "pass"))
        fun approvals(outcome: ArtifactRef): Int {
            val current = r.copy(taskRuns = r.taskRuns + mapOf(
                build to producer.copy(artifacts = listOf(report)),
                review to reviewer.copy(artifacts = listOf(outcome))))
            return assertNotNull(creatureNeeds(build, definition, current, roles, reports(current))).reviewApprovals
        }
        assertEquals(1, approvals(verdict))
        assertEquals(0, approvals(verdict.copy(metadata = mapOf("hall-monitor-verdict" to "pass"))))
        assertEquals(0, approvals(verdict.copy(metadata = verdict.metadata + ("hall-monitor-report-id" to "other"))))
    }

    @Test
    fun cumulativeEchoUsesRatesAndDoesNotCountARepeatedRunOrReportTwice() {
        val first = run(Triple("plan", TaskRunStatus.Completed, "a" to 1),
            Triple("build", TaskRunStatus.Completed, "a" to 1))
            .copy(roleSnapshot = roles, definitionSnapshot = definition, status = WorkflowRunStatus.Completed)
        val evidence = reports(first)
        val history = EchoHistory().advance(first, evidence + evidence)
        assertEquals(60, history.balances[engineer.id])
        assertEquals(10_000, EchoHistory(balances = mapOf(engineer.id to 9_990)).advance(first, evidence).balances[engineer.id])
        assertEquals(history, history.advance(first, evidence))
        val second = first.copy(id = WorkflowRunId("second"))
        val next = history.advance(second, reports(second))
        assertEquals(120, next.balances[engineer.id])
        val clone = engineer.copy(id = com.hereliesaz.geministrator.domain.RoleDefinitionId("clone"))
        val distinctRole = first.copy(id = WorkflowRunId("third"), roleSnapshot = roles + clone,
            taskRuns = first.taskRuns.mapValues { (id, value) ->
                if (id.value == "plan") value.copy(assignedRoleId = clone.id) else value
            })
        val withClone = next.advance(distinctRole, reports(distinctRole))
        assertEquals(160, withClone.balances[engineer.id])
        val challenge = distinctRole.copy(id = WorkflowRunId("fourth"), roleSnapshot = roles + clone.copy(instructions = "Different"))
        val challenged = withClone.advance(challenge, reports(challenge))
        assertEquals(80, challenged.balances[engineer.id])
        assertEquals(0, EchoHistory().advance(challenge, reports(challenge)).balances[engineer.id])
        val missing = first.copy(id = WorkflowRunId("missing"))
        assertEquals(challenged.balances, challenged.advance(missing, reports(missing).map { it.copy(deliveredArtifactIds = null) }).balances)
        assertEquals(emptyMap(), EchoHistory().advance(first.copy(definitionSnapshot = null), evidence).balances)
        assertEquals(emptyMap(), EchoHistory().advance(first, evidence.map { it.copy(modelConsistent = false) }).balances)
        assertEquals(emptyMap(), EchoHistory().advance(first, evidence.map { it.copy(providerRunId = ProviderRunId("stale")) }).balances)
        val needs = assertNotNull(creatureNeeds(TaskDefinitionId("build"), definition, first, roles, evidence, next.balances))
        assertEquals(120, needs.echoHundredths)
        assertEquals(EchoState.Grounded, needs.echoState)
    }

    @Test
    fun aRecordedSoloHatchInheritsOnlyItsBirthBalance() {
        val source = run(Triple("plan", TaskRunStatus.Completed, "a" to 1),
            Triple("build", TaskRunStatus.Completed, "a" to 1)).let { r ->
            r.copy(status = WorkflowRunStatus.Completed, roleSnapshot = roles, definitionSnapshot = definition,
                taskRuns = r.taskRuns.mapValues { (_, task) -> task.copy(executor = TaskExecutor.RoleAgent(engineer.id)) })
        }
        val child = assertNotNull(hatchFromRun(source, roles))
        val first = EchoHistory().advance(source, reports(source))
        val later = source.copy(id = WorkflowRunId("later"))
        val history = first.advance(later, reports(later))
        assertEquals(120, history.balances[engineer.id])
        assertNull(history.balances[child.id])
        val inherited = history.withBirths(listOf(child))
        assertEquals(60, inherited.balances[child.id])
        assertEquals(inherited, inherited.withBirths(listOf(child)))
        assertNull(history.withBirths(listOf(child.copy(hatchedFromRunId = "wrong"))).balances[child.id])
    }

    @Test
    fun hybridEchoInheritsActualBirthEvidenceWithoutInventingUnknownBalances() {
        val a = engineer.id
        val b = qa.id
        val c = com.hereliesaz.geministrator.domain.RoleDefinitionId("third")
        val source = WorkflowRunId("birth")
        val close = EchoBirthSeed(source, setOf(a,b), mapOf(a to 7_000, b to 6_000),
            mapOf(setOf(a,b) to 100))
        assertEquals(10_000, close.inherited(listOf(a,b)))
        assertNull(close.inherited(listOf(a,a)))
        val hybrid = EchoBirthSeed(source, setOf(a,b,c), mapOf(a to 1200,b to 2400,c to 3600),
            mapOf(setOf(a,b) to 80))
        assertEquals(600, hybrid.inherited(listOf(a,b)))
        assertNull(hybrid.inherited(listOf(a,c)))
        assertNull(hybrid.copy(balances = mapOf(a to 1200,b to 2400)).inherited(listOf(a,b)))
    }

    @Test
    fun actualHybridLineageInheritsOnlyOnceFromItsBirthRun() {
        val clone = engineer.copy(id = com.hereliesaz.geministrator.domain.RoleDefinitionId("clone"))
        val plan = TaskDefinitionId("plan")
        val build = TaskDefinitionId("build")
        val original = run(Triple("plan", TaskRunStatus.Completed, "a" to 1),
            Triple("build", TaskRunStatus.Completed, "a" to 1))
        val source = original.copy(status = WorkflowRunStatus.Completed,
            roleSnapshot = listOf(engineer, clone), definitionSnapshot = definition,
            taskRuns = original.taskRuns + (plan to original.taskRuns.getValue(plan).copy(assignedRoleId = clone.id)))
        val evidence = reports(source)
        val child = assertNotNull(hatchFromRun(source, source.roleSnapshot, reports = evidence))
        val settled = EchoHistory(balances = mapOf(engineer.id to 2_000, clone.id to 3_000)).advance(source, evidence)
        assertNull(settled.balances[child.id])
        // The consumer receives the clone's output once (+40); both known parent balances sum.
        val inherited = settled.withBirths(listOf(child))
        assertEquals(5_040, inherited.balances[child.id])
        assertEquals(inherited, inherited.withBirths(listOf(child)))
        assertEquals(inherited, inherited.advance(source, evidence))
        assertNull(settled.withBirths(listOf(child.copy(hatchedFromRunId = "wrong"))).balances[child.id])
    }

    @Test
    fun onlyUnambiguousCurrentSessionModelReportsEstablishKinship() {
        val r=run(Triple("plan",TaskRunStatus.Completed,"a" to 1),Triple("build",TaskRunStatus.Running,"a" to 1))
        val plan=TaskDefinitionId("plan")
        val build=TaskDefinitionId("build")
        val events=reports(r)
        val source=events.single { it.taskDefinitionId==plan }
        val target=events.single { it.taskDefinitionId==build }
        assertEquals(mapOf(plan to "model-a",build to "model-a"),reportedSessionModels(r,events))
        fun needs(reports: List<ProviderUsageRecorded>)=assertNotNull(creatureNeeds(build,definition,r,roles,reports))
        assertEquals(100,needs(events).echo)
        assertEquals(0,needs(emptyList()).echoComparablePaths)
        assertNull(needs(emptyList()).nearClone)
        val different=listOf(source.copy(model="model-b"),target)
        assertEquals(1,needs(different).echoComparablePaths)
        assertEquals(0,needs(different).echo)
        assertNull(needs(different).nearClone)
        for(unreliable in listOf(
            source.copy(modelConsistent=null),source.copy(modelConsistent=false),
            source.copy(model=null),source.copy(tokensAvoided=20),
            source.copy(workflowRunId=WorkflowRunId("other-run")),
            source.copy(providerRunId=ProviderRunId("prior-attempt")),
            source.copy(providerId=AgentProviderId("different-provider")),
        )) assertEquals(0,needs(listOf(unreliable,target)).echoComparablePaths)
        assertEquals(0,needs(events+source.copy(model="model-b")).echoComparablePaths)
    }
}
