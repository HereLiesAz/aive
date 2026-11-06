package com.hereliesaz.geministrator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A tripwire for THREAT_MODEL, Control API: nothing under `control/` may call anything that
 * decides an approval gate, lifts a spend stop, or reads a key store. The behavioural proof is in
 * RuntimeControlHostTest; this keeps a later edit from quietly adding such a call.
 */
class ControlApprovalBoundaryTest {
    private val forbidden = listOf(
        "approveTask", "ApprovalGateCoordinator", "WorkflowApprovalService", "liftSpendStop", "liftSpendPause",
        "ProgressiveDelegation", "recordPlanRejection", "PlanRejectionService", "\\.approve\\(", "\\.reject\\(", "\\.applying\\(",
        "ApprovalDecisionIntent", "CredentialStore", "SecretStore", "approveDelegatedGates",
    ).map { Regex(it) }

    @Test
    fun noControlSourceCanDecideAGateOrReadAKey() {
        val dir = File("src/commonMain/kotlin/com/hereliesaz/geministrator/control")
        val files = dir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.size >= 5, "control sources not found under ${dir.absolutePath}")
        for (file in files) {
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
    fun theHostInterfaceHasNoMemberThatDecidesAnApproval() {
        val verbs = Regex("approve|reject|resolve|decide|release|lift|grant|allow", RegexOption.IGNORE_CASE)
        val names = com.hereliesaz.geministrator.control.ControlHost::class.java.methods.map { it.name }
        assertTrue(names.none { verbs.containsMatchIn(it) && it != "listApprovals" }, "ControlHost members: $names")
    }
}
