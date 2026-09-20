package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.orchestration.*

internal class RecordingLocalOrchestrationUtilities(
    private val delegate: LocalOrchestrationUtilityFamily = DeterministicLocalOrchestrationUtilities,
) : LocalOrchestrationUtilityFamily {
    var memoryQueryCalls: Int = 0
    var contextPackingCalls: Int = 0
    var agentRoutingCalls: Int = 0
    var toolRoutingCalls: Int = 0
    var handoffCalls: Int = 0
    var escalationCalls: Int = 0
    var completionCalls: Int = 0
    var executionSummaryCalls: Int = 0
    var verificationPlanningCalls: Int = 0

    override fun composeMemoryQueries(input: MemoryQueryInput): MemoryQueryPlan {
        memoryQueryCalls += 1
        return delegate.composeMemoryQueries(input)
    }

    override fun packContext(input: ContextPackingInput): ContextPackingPlan {
        contextPackingCalls += 1
        return delegate.packContext(input)
    }

    override fun routeAgent(input: AgentRoutingInput): AgentRoute {
        agentRoutingCalls += 1
        return delegate.routeAgent(input)
    }

    override fun routeTool(input: ToolRoutingInput): ToolRoute {
        toolRoutingCalls += 1
        return delegate.routeTool(input)
    }

    override fun composeHandoff(input: HandoffInput): HandoffPacket {
        handoffCalls += 1
        return delegate.composeHandoff(input)
    }

    override fun evaluateEscalation(input: CapabilityAssessment): EscalationResult {
        escalationCalls += 1
        return delegate.evaluateEscalation(input)
    }

    override fun evaluateCompletion(input: CompletionInput): CompletionResult {
        completionCalls += 1
        return delegate.evaluateCompletion(input)
    }

    override fun summarizeExecution(
        definition: com.hereliesaz.geministrator.domain.WorkflowDefinition,
        run: com.hereliesaz.geministrator.domain.WorkflowRun,
    ): ExecutionStateSummary {
        executionSummaryCalls += 1
        return delegate.summarizeExecution(definition, run)
    }

    override fun planVerification(input: VerificationPlanningInput): VerificationPlan {
        verificationPlanningCalls += 1
        return delegate.planVerification(input)
    }
}
