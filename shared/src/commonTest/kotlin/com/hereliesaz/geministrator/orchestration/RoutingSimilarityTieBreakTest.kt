package com.hereliesaz.geministrator.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingSimilarityTieBreakTest {
    private val routing = DeterministicLocalOrchestrationUtilities

    private fun agent(id: String, description: String, rank: Int = 0, capabilities: Set<String> = setOf("code")) =
        AgentRouteCandidate(id = id, capabilities = capabilities, preferenceRank = rank, description = description)

    @Test
    fun similarityBreaksTiesAmongEqualAgents() {
        val candidates = listOf(
            agent("a-docs", "writes documentation and release notes"),
            agent("b-db", "designs database schemas and SQL migrations"),
        )
        val route = routing.routeAgent(AgentRoutingInput(candidates = candidates, taskText = "add a SQL migration for the workflow database schema"))
        assertEquals("b-db", route.selectedAgent)

        // Without task text the id decides, as before.
        assertEquals("a-docs", routing.routeAgent(AgentRoutingInput(candidates = candidates)).selectedAgent)
    }

    @Test
    fun similarityNeverOverridesRankOrCapability() {
        val ranked = listOf(
            agent("preferred", "writes documentation", rank = 0),
            agent("similar", "designs database schemas and SQL migrations", rank = 1),
        )
        assertEquals("preferred", routing.routeAgent(AgentRoutingInput(candidates = ranked, taskText = "SQL migration for the database schema")).selectedAgent)

        val incapable = listOf(
            agent("capable", "writes documentation"),
            agent("similar", "designs database schemas and SQL migrations", capabilities = setOf("docs")),
        )
        val route = routing.routeAgent(
            AgentRoutingInput(requiredCapabilities = setOf("code"), candidates = incapable, taskText = "SQL migration for the database schema"),
        )
        assertEquals("capable", route.selectedAgent)
    }

    @Test
    fun similarityBreaksTiesAmongEqualTools() {
        val tools = listOf(
            ToolCapability("a-grep", setOf("search"), description = "search file contents with regular expressions"),
            ToolCapability("b-web", setOf("search"), description = "search the web for pages and documentation"),
        )
        val route = routing.routeTool(ToolRoutingInput(operationClass = "search", capabilities = tools, taskText = "find documentation pages on the web"))
        assertEquals("b-web", route.tool)
        assertEquals("a-grep", routing.routeTool(ToolRoutingInput(operationClass = "search", capabilities = tools)).tool)
    }
}
