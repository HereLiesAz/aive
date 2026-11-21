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
                // The one owner-gated call (THREAT_MODEL invariant 8): RuntimeControlHost.approveCreature approves, never rejects.
                val allowed = file.name == "RuntimeControlHost.kt" && pattern.pattern == "approveTask"
                val hit = code.filter { pattern.containsMatchIn(it) }.let { if (allowed) it.drop(1) else it }.firstOrNull()
                assertTrue(hit == null, "${file.name} uses ${pattern.pattern}: $hit")
            }
        }
    }

    @Test
    fun theHostInterfaceHasNoMemberThatDecidesAnApprovalButTheOneOwnerGatedApprove() {
        val verbs = Regex("approve|reject|resolve|decide|release|lift|grant|allow", RegexOption.IGNORE_CASE)
        // Since #1006 a plan may be approved over the control API, but only through one member, and only when the owner has
        // allowed it (AIVE_ALLOW_REMOTE_APPROVAL): approveCreature, gated by remoteApprovalAllowed. Nothing else decides.
        val allowed = setOf("listApprovals", "approveCreature", "getRemoteApprovalAllowed")
        // The compiler adds synthetic accessors and default-method bridges (access$x$jd, x$suspendImpl): same member, not a new one.
        val names = com.hereliesaz.geministrator.control.ControlHost::class.java.methods
            .map { it.name.removePrefix("access$").substringBefore('$') }
            .toSet()
        assertTrue(names.none { verbs.containsMatchIn(it) && it !in allowed }, "ControlHost members: $names")
        assertTrue("approveCreature" in names, "the one owner-gated approve is gone: $names")
    }
}
