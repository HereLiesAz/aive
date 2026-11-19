package com.hereliesaz.geministrator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A tripwire for THREAT_MODEL, Chat bots (invariant 8): nothing under `chat/` may call anything that decides an approval
 * gate, lifts a spend stop, starts or stops a run other than through the control host, or reads the app's own key stores.
 * The behavioural proof is in ChatBotServiceTest and ChatHandlerAndNotifierTest.
 */
class ChatApprovalBoundaryTest {
    private val forbidden = listOf(
        "approveTask", "ApprovalGateCoordinator", "WorkflowApprovalService", "liftSpendStop", "liftSpendPause",
        "ProgressiveDelegation", "recordPlanRejection", "PlanRejectionService", "\\.approve\\(", "\\.reject\\(", "\\.applying\\(",
        "ApprovalDecisionIntent", "approveDelegatedGates", "approvalGates\\.(put|resolve|decide|update)",
        "cancelActiveRun", "launchSavedWorkflow", "ProviderCredential", "RepositoryCredential", "CredentialTool",
    ).map { Regex(it) }

    private fun chatSources(): List<File> {
        val dir = File("src/commonMain/kotlin/com/hereliesaz/geministrator/chat")
        val files = dir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.size >= 8, "chat sources not found under ${dir.absolutePath}")
        return files
    }

    @Test
    fun noChatSourceCanDecideAGateOrReachARunOutsideTheHost() {
        for (file in chatSources()) {
            val code = file.readLines().filterNot { line ->
                val t = line.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
            }
            for (pattern in forbidden) {
                val hit = code.firstOrNull { pattern.containsMatchIn(it) }
                assertTrue(hit == null, "${file.name} uses ${pattern.pattern}: $hit")
            }
        }
    }

    @Test
    fun theCommandSetHasNoApprovalVerb() {
        val verbs = Regex("approve|reject|release|decide|grant", RegexOption.IGNORE_CASE)
        val source = File("src/commonMain/kotlin/com/hereliesaz/geministrator/chat/ChatCommand.kt").readLines()
            .filter { it.trim().startsWith("data ") || it.trim().startsWith("sealed ") }
        assertTrue(source.none { verbs.containsMatchIn(it) }, source.toString())
    }

    @Test
    fun theNoticeNamesNoAnsweredStrangerAndNoTokenIsLogged() {
        for (file in chatSources()) {
            val code = file.readText()
            assertTrue(!Regex("println\\(|Log\\.|logger", RegexOption.IGNORE_CASE).containsMatchIn(code), "${file.name} logs")
        }
    }
}
