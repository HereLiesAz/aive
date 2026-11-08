package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EscalationPolicy
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.workflow.improvement.SelfImprovementLoop
import com.hereliesaz.geministrator.workflow.schedule.ScheduleCadence

/** What a suite hive suggests when a keeper puts it on a schedule: how often, and what to ask of it. */
data class SuggestedSchedule(val cadence: ScheduleCadence, val objective: String, val withEvidence: Boolean = false)

/** One hive of the suite; [schedule] is set on the ones meant to run by themselves. */
data class CodingSuiteEntry(
    val definition: WorkflowDefinition,
    val kind: Kind,
    val schedule: SuggestedSchedule? = null,
) {
    /** What it does to the repository, which decides whether it may run unattended. */
    enum class Kind {
        /** Changes code and stops at your approval before anything is released. Run on demand. */
        Delivers,

        /** Reads and reports; changes nothing. Safe to run on a schedule. */
        Reports,
    }
}

/**
 * The coding hives that come with the app (docs/Coding-Workflows.md): delivering a feature, fixing a
 * bug, reviewing, refactoring, testing, updating dependencies, auditing security, syncing docs,
 * preparing a release, answering an incident, and mapping a codebase; and the ones that report
 * on a schedule. Plain definitions over the built-in roles: they ask for no provider, no
 * repository operation and no executor beyond an agent and a person's approval, so they validate
 * everywhere and run on whatever the keeper connected.
 *
 * Two rules shape them. A hive that changes code always stops at a person's approval before its
 * release, and the code-changing step is itself approved first. A hive meant to run on a schedule
 * only reads and reports: nothing in it waits on a person, so an unattended run never stalls, and a
 * fix that follows from its report is a separate hive a person starts.
 */
object CodingWorkflowSuite {
    private val impl = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer
    private val review = BuiltInRoles.CodeReviewer
    private val release = BuiltInRoles.ReleaseEngineer

    fun all(): List<CodingSuiteEntry> = listOf(
        featureDelivery(), bugFix(), codeReview(), safeRefactor(), testCoverage(), dependencyUpdate(),
        securityAudit(), docsSync(), releasePrep(), incidentHotfix(), codebaseMap(),
        nightlyDependencyAudit(), dailyCiTriage(), weeklyTestHealth(), weeklySecurityScan(),
        weeklyDocsDrift(), weeklyTechDebt(), selfImprovement(),
    )

    fun definitions(): List<WorkflowDefinition> = all().map { it.definition }

    // ---- On demand: change code, behind approvals ----

    private fun featureDelivery() = delivers(
        "feature-delivery", "Feature delivery",
        "From a request to a reviewed, verified change: requirements, design, a challenge to the plan, " +
            "implementation, independent verification and review, then your release approval.",
        step(
            "requirements", "Pin down the requirements", BuiltInRoles.ProductManager, RoleAuthority.ProposePlan,
            "Turn the run's objective into precise requirements and acceptance criteria. Do not widen the scope.",
            criteria = listOf("Every requirement is testable", "Out-of-scope items are named"),
        ),
        step(
            "design", "Design the change", BuiltInRoles.Architect, RoleAuthority.ProposePlan,
            "Propose the smallest design that meets the requirements inside the project's existing structure: files, interfaces, risks.",
            after = listOf("requirements"),
        ),
        step(
            "challenge", "Challenge the plan", BuiltInRoles.AdversarialReviewer, RoleAuthority.RejectPlan,
            "Find the missing steps, hidden assumptions and weak verification in the requirements and design. Approve only when objections are resolved.",
            after = listOf("design"),
        ),
        step(
            "implement", "Implement", impl, RoleAuthority.Implement,
            "Implement the approved design and nothing else, satisfying the acceptance criteria and the approved tests.",
            after = listOf("challenge"), humanApproval = true,
        ),
        step(
            "verify", "Verify independently", qa, RoleAuthority.Verify,
            "Try to falsify the completion claim against the requirements, acceptance criteria and tests. Report what you ran and what you saw.",
            after = listOf("implement"),
        ),
        step(
            "code-review", "Review the code", review, RoleAuthority.ReviewCode,
            "Review correctness, side effects, maintainability and fit with the approved design.",
            after = listOf("verify"),
        ),
        releaseGate("release-approval", "Approve release", after = listOf("code-review")),
        testDesign = TestDesignPolicy.BeforeAndAfterImplementation,
    )

    private fun bugFix() = delivers(
        "bug-fix", "Bug fix",
        "Reproduce first, find the cause, fix it, and leave a regression test: the run's objective is the bug report.",
        step(
            "reproduce", "Reproduce the bug", qa, RoleAuthority.Verify,
            "Reproduce the reported bug and state the exact steps, the expected result and the observed one. If it cannot be reproduced, say what was tried.",
            criteria = listOf("A failing case or a clear account of why none could be made"),
        ),
        step(
            "diagnose", "Find the root cause", BuiltInRoles.RecoveryEngineer, RoleAuthority.DiagnoseFailure,
            "Find the root cause, not the symptom. Name the file and the reasoning, and propose the smallest safe fix.",
            after = listOf("reproduce"),
        ),
        step(
            "fix", "Fix it", impl, RoleAuthority.Implement,
            "Apply the smallest fix for the diagnosed cause. Do not refactor around it.",
            after = listOf("diagnose"), humanApproval = true,
        ),
        step(
            "regression-test", "Add a regression test", BuiltInRoles.CrashTestDummy, RoleAuthority.AuthorTests,
            "Add a test that fails without the fix and passes with it. Do not weaken any existing test.",
            after = listOf("fix"),
        ),
        step(
            "verify", "Verify the fix", qa, RoleAuthority.Verify,
            "Run the reproduction and the new test, and the tests nearby. Report what you ran and what you saw.",
            after = listOf("regression-test"),
        ),
        step(
            "code-review", "Review the fix", review, RoleAuthority.ReviewCode,
            "Check that the fix addresses the cause, changes nothing else, and is covered by the test.",
            after = listOf("verify"),
        ),
        releaseGate("release-approval", "Approve the fix", after = listOf("code-review")),
        testDesign = TestDesignPolicy.None,
    )

    private fun safeRefactor() = delivers(
        "safe-refactor", "Safe refactor",
        "Pin down today's behaviour with tests, change the structure, and prove the behaviour did not change.",
        step(
            "characterize", "Pin down current behaviour", BuiltInRoles.CrashTestDummy, RoleAuthority.AuthorTests,
            "Write characterization tests for the code the objective names, so its present behaviour is fixed before anything changes.",
        ),
        step(
            "plan", "Plan the refactor", BuiltInRoles.Architect, RoleAuthority.ProposePlan,
            "Describe the refactor in small steps, each of which leaves the tests green. Name what must not change.",
            after = listOf("characterize"),
        ),
        step(
            "refactor", "Refactor", impl, RoleAuthority.Implement,
            "Carry out the plan. Change structure only; no behaviour, no new features. The characterization tests must stay as they are.",
            after = listOf("plan"), humanApproval = true,
        ),
        step(
            "verify", "Prove nothing changed", qa, RoleAuthority.Verify,
            "Run the characterization tests and the existing suite; compare the behaviour before and after.",
            after = listOf("refactor"),
        ),
        step(
            "code-review", "Review the result", review, RoleAuthority.ReviewCode,
            "Check the refactor kept to the plan, left behaviour alone, and is easier to read than before.",
            after = listOf("verify"),
        ),
        releaseGate("release-approval", "Approve the refactor", after = listOf("code-review")),
        testDesign = TestDesignPolicy.None,
    )

    private fun testCoverage() = delivers(
        "test-coverage", "Raise test coverage",
        "Find what is untested and matters, write tests for it, and prove they test something.",
        step(
            "survey", "Find the gaps", qa, RoleAuthority.Verify,
            "Survey the area the objective names: what is untested, what is risky, in order of importance. Do not write tests yet.",
        ),
        step(
            "write-tests", "Write the tests", BuiltInRoles.CrashTestDummy, RoleAuthority.AuthorTests,
            "Write tests for the top gaps. Test behaviour, not implementation details. Do not change production code.",
            after = listOf("survey"), humanApproval = true,
        ),
        step(
            "verify", "Check the tests bite", qa, RoleAuthority.Verify,
            "Run the new tests. For the important ones, show that they fail when the behaviour they guard is broken.",
            after = listOf("write-tests"),
        ),
        step(
            "code-review", "Review the tests", review, RoleAuthority.ReviewCode,
            "Check the tests are clear, deterministic and fast, and do not duplicate what exists.",
            after = listOf("verify"),
        ),
        releaseGate("release-approval", "Approve the tests", after = listOf("code-review")),
        testDesign = TestDesignPolicy.None,
    )

    private fun dependencyUpdate() = delivers(
        "dependency-update", "Update dependencies",
        "Audit what is outdated or vulnerable, update it in small steps, and prove the build and tests still hold.",
        step(
            "audit", "Audit dependencies", BuiltInRoles.Researcher, null,
            "List the dependencies that are outdated or have known vulnerabilities, with the version to move to, the changelog notes that matter and the risk of each move.",
        ),
        step(
            "update", "Update them", impl, RoleAuthority.Implement,
            "Update the dependencies the audit recommends, lowest risk first, adapting code only where an update forces it.",
            after = listOf("audit"), humanApproval = true,
        ),
        step(
            "verify", "Verify the build and tests", qa, RoleAuthority.Verify,
            "Build and run the tests on every target the project has. Report failures and the update that caused each.",
            after = listOf("update"),
        ),
        step(
            "code-review", "Review the changes", review, RoleAuthority.ReviewCode,
            "Check each update is justified, nothing was pinned or loosened without reason, and lockfiles agree.",
            after = listOf("verify"),
        ),
        releaseGate("release-approval", "Approve the updates", after = listOf("code-review")),
        testDesign = TestDesignPolicy.None,
    )

    private fun docsSync() = delivers(
        "docs-sync", "Sync the docs with the code",
        "Find where the docs and the code disagree, and correct the docs.",
        step(
            "find-drift", "Find the drift", BuiltInRoles.Researcher, null,
            "Compare the docs the objective names with the code. List each claim that is wrong, stale or missing, with the file and line that shows it.",
        ),
        step(
            "update-docs", "Correct the docs", impl, RoleAuthority.Implement,
            "Correct the documentation for each listed item. Change docs only; if the code is wrong, say so instead of changing it.",
            after = listOf("find-drift"), humanApproval = true,
        ),
        step(
            "code-review", "Review the corrections", review, RoleAuthority.ReviewCode,
            "Check every correction is true to the code and nothing else was changed.",
            after = listOf("update-docs"),
        ),
        releaseGate("release-approval", "Approve the doc changes", after = listOf("code-review")),
        testDesign = TestDesignPolicy.None,
    )

    private fun releasePrep() = delivers(
        "release-prep", "Prepare a release",
        "Write the changelog, check the release is ready, and stop for your approval.",
        step(
            "changelog", "Draft the changelog", BuiltInRoles.ProductManager, RoleAuthority.ProposePlan,
            "From the changes since the last release, draft a changelog for users: what changed, in plain words, grouped by what a person will notice.",
        ),
        step(
            "checks", "Check it is ready", qa, RoleAuthority.Verify,
            "Check that the build, tests and version are in order and nothing unfinished is included. List blockers first.",
            after = listOf("changelog"),
        ),
        step(
            "code-review", "Review the release", review, RoleAuthority.ReviewCode,
            "Read the diff since the last release for anything risky or unexplained, and for changes the changelog does not mention.",
            after = listOf("checks"),
        ),
        releaseGate("release-approval", "Approve the release", after = listOf("code-review")),
        testDesign = TestDesignPolicy.None,
    )

    private fun incidentHotfix() = delivers(
        "incident-hotfix", "Incident hotfix",
        "A production problem: find the cause, fix it narrowly, and if verification fails, stop and plan a rollback instead.",
        step(
            "diagnose", "Diagnose the incident", BuiltInRoles.RecoveryEngineer, RoleAuthority.DiagnoseFailure,
            "From the run's objective, find what is failing, since when, and the likeliest cause. State what is known and what is guessed.",
        ),
        step(
            "hotfix", "Apply the narrowest fix", impl, RoleAuthority.Implement,
            "Apply the smallest change that stops the harm. No cleanup, no refactor.",
            after = listOf("diagnose"), humanApproval = true,
        ),
        step(
            "verify", "Verify the fix", qa, RoleAuthority.Verify,
            "Check the failure no longer happens and nothing nearby broke. Report what you ran and saw.",
            after = listOf("hotfix"),
        ),
        step(
            "rollback-plan", "Plan a rollback", BuiltInRoles.RecoveryEngineer, RoleAuthority.DiagnoseFailure,
            "Verification failed. Propose how to undo the change safely, what state it leaves, and what to tell people.",
            after = listOf("hotfix"), onFailureOf = "verify",
        ),
        releaseGate("release-approval", "Approve the hotfix", after = listOf("verify")),
        testDesign = TestDesignPolicy.None,
    )

    // ---- Read and report: safe to leave alone ----

    private fun codeReview() = reports(
        "code-review", "Review a change",
        "Three independent reviews of a change, then one summary. The run's objective names the change. Nothing is modified.",
        step(
            "correctness", "Review correctness", review, RoleAuthority.ReviewCode,
            "Review the change for bugs, side effects, missing cases and maintainability.",
        ),
        step(
            "risk", "Attack the change", BuiltInRoles.AdversarialReviewer, RoleAuthority.RejectPlan,
            "Assume the change is wrong. Find how: edge cases, concurrency, failure paths, security.",
        ),
        step(
            "tests", "Review the tests", qa, RoleAuthority.Verify,
            "Judge whether the tests cover the change and would catch a regression. Say what is missing.",
        ),
        step(
            "summary", "Summarize", BuiltInRoles.ProductManager, RoleAuthority.ProposePlan,
            "Merge the three reviews into one: blockers first, then suggestions, then what is good. Remove duplicates.",
            after = listOf("correctness", "risk", "tests"),
        ),
    )

    private fun securityAudit() = reports(
        "security-audit", "Security audit",
        "Model the threats, look for the weaknesses, and report them by severity. Nothing is modified.",
        step(
            "threat-model", "Model the threats", BuiltInRoles.Architect, RoleAuthority.ProposePlan,
            "Describe what the area in the objective protects, who might attack it, and through which entry points.",
        ),
        step(
            "hunt", "Look for weaknesses", BuiltInRoles.AdversarialReviewer, RoleAuthority.RejectPlan,
            "Walk each entry point looking for injection, broken access control, secrets in code, unsafe deserialization and unsafe defaults. Cite file and line.",
            after = listOf("threat-model"),
        ),
        step(
            "triage", "Triage the findings", qa, RoleAuthority.Verify,
            "For each finding, say whether it is real, how it could be exploited, and its severity. Drop what you cannot support.",
            after = listOf("hunt"),
        ),
        step(
            "report", "Write the report", BuiltInRoles.ProductManager, RoleAuthority.ProposePlan,
            "Write the report: findings by severity, each with where, why and the smallest fix. End with what to fix first.",
            after = listOf("triage"),
        ),
    )

    private fun codebaseMap() = reports(
        "codebase-map", "Map a codebase",
        "For someone new to the code: what is where, how it fits together, and where to start. Nothing is modified.",
        step(
            "survey", "Survey the code", BuiltInRoles.Researcher, null,
            "Survey the repository: its parts, how they depend on each other, how it is built and tested, and where the entry points are.",
        ),
        step(
            "architecture", "Describe the architecture", BuiltInRoles.Architect, RoleAuthority.ProposePlan,
            "From the survey, describe the architecture in plain words: layers, boundaries, the main flows, and what is surprising.",
            after = listOf("survey"),
        ),
        step(
            "guide", "Write the starting guide", BuiltInRoles.ProductManager, RoleAuthority.ProposePlan,
            "Write a short guide for a new contributor: how to build, run and test, where to start for common changes, and what to avoid.",
            after = listOf("architecture"),
        ),
    )

    // ---- On a schedule: read, report, change nothing ----

    private fun nightlyDependencyAudit() = scheduled(
        "nightly-dependency-audit", "Nightly dependency audit",
        "Report dependencies that are outdated or newly vulnerable. Nothing is modified.",
        ScheduleCadence.daily(hourUtc = 7),
        "List the project's dependencies that are outdated or have new known vulnerabilities since the last report.",
        step(
            "audit", "Audit dependencies", BuiltInRoles.Researcher, null,
            "List every dependency that is outdated or has a known vulnerability, with the version to move to and the risk. Say if nothing changed.",
        ),
        step(
            "report", "Write the report", BuiltInRoles.ProductManager, RoleAuthority.ProposePlan,
            "Summarize the audit: security issues first, then major updates, then the rest. If nothing needs action, say so in one line.",
            after = listOf("audit"),
        ),
    )

    private fun dailyCiTriage() = scheduled(
        "daily-ci-triage", "Daily CI triage",
        "Read the recent CI runs and say what is failing, what is flaky, and what to look at first. Nothing is modified.",
        ScheduleCadence.daily(hourUtc = 13),
        "Triage the project's CI runs from the last day.",
        step(
            "collect", "Collect failures", BuiltInRoles.Researcher, null,
            "Collect the failing and flaky CI runs of the last day: which jobs, which tests, how often, since which change.",
        ),
        step(
            "diagnose", "Likely causes", BuiltInRoles.RecoveryEngineer, RoleAuthority.DiagnoseFailure,
            "For each failure group, give the likeliest cause and whether it looks like a real regression, a flaky test or an infrastructure problem.",
            after = listOf("collect"),
        ),
        step(
            "report", "Write the triage", BuiltInRoles.ProductManager, RoleAuthority.ProposePlan,
            "Write a short triage: what to fix first and why. If CI is healthy, say so in one line.",
            after = listOf("diagnose"),
        ),
    )

    private fun weeklyTestHealth() = scheduled(
        "weekly-test-health", "Weekly test health",
        "Report slow, flaky and missing tests. Nothing is modified.",
        ScheduleCadence.weekly(dayOfWeek = 1, hourUtc = 8),
        "Report on the health of the project's test suite this week.",
        step(
            "assess", "Assess the suite", qa, RoleAuthority.Verify,
            "Assess the test suite: the slowest tests, the flaky ones, the areas changed this week with no tests, and anything skipped.",
        ),
        step(
            "report", "Write the report", BuiltInRoles.ProductManager, RoleAuthority.ProposePlan,
            "Write the report with the three things most worth doing next and why.",
            after = listOf("assess"),
        ),
    )

    private fun weeklySecurityScan() = scheduled(
        "weekly-security-scan", "Weekly security scan",
        "Look at the week's changes for security problems and report them. Nothing is modified.",
        ScheduleCadence.weekly(dayOfWeek = 2, hourUtc = 8),
        "Scan the changes made to the project this week for security problems.",
        step(
            "scan", "Scan the week's changes", BuiltInRoles.AdversarialReviewer, RoleAuthority.RejectPlan,
            "Read the changes of the last seven days for secrets, injection, weakened access checks and unsafe defaults. Cite file and line.",
        ),
        step(
            "report", "Write the report", BuiltInRoles.ProductManager, RoleAuthority.ProposePlan,
            "Report findings by severity, or say that none were found.",
            after = listOf("scan"),
        ),
    )

    private fun weeklyDocsDrift() = scheduled(
        "weekly-docs-drift", "Weekly docs drift",
        "Check the docs against the week's code changes and report what went stale. Nothing is modified.",
        ScheduleCadence.weekly(dayOfWeek = 3, hourUtc = 8),
        "Check whether this week's code changes made any documentation wrong.",
        step(
            "compare", "Compare docs to changes", BuiltInRoles.Researcher, null,
            "From the last seven days of changes, list each place the documentation now disagrees with the code, with both locations.",
        ),
        step(
            "report", "Write the report", BuiltInRoles.ProductManager, RoleAuthority.ProposePlan,
            "Report the stale docs in order of how misleading they are, or say that none are.",
            after = listOf("compare"),
        ),
    )

    private fun weeklyTechDebt() = scheduled(
        "weekly-tech-debt", "Weekly tech-debt report",
        "Name where the code is getting harder to change, and what to do about it. Nothing is modified.",
        ScheduleCadence.weekly(dayOfWeek = 5, hourUtc = 8),
        "Report on where technical debt is growing in the project.",
        step(
            "survey", "Survey the debt", BuiltInRoles.Architect, RoleAuthority.ProposePlan,
            "Find the files and modules that changed most this month, grew most, or collect TODOs, duplication and workarounds. Rank them.",
        ),
        step(
            "report", "Write the report", BuiltInRoles.ProductManager, RoleAuthority.ProposePlan,
            "Recommend the one or two debts worth paying down first, each with the smallest first step.",
            after = listOf("survey"),
        ),
    )

    /** The self-improvement loop (docs/architecture/SELF_IMPROVEMENT.md): read-only, so it may run on its own. */
    private fun selfImprovement() = CodingSuiteEntry(
        SelfImprovementLoop.definition(),
        CodingSuiteEntry.Kind.Reports,
        SuggestedSchedule(
            ScheduleCadence.weekly(dayOfWeek = 7, hourUtc = 10),
            "Run a self-improvement cycle: examine how the swarm has been doing, and examine this loop itself.",
            withEvidence = true,
        ),
    )

    // ---- Building blocks ----

    private fun delivers(
        slug: String,
        name: String,
        description: String,
        vararg tasks: TaskDefinition,
        testDesign: TestDesignPolicy,
    ) = CodingSuiteEntry(
        WorkflowDefinition(
            id = WorkflowDefinitionId("suite-$slug"), name = name, description = description,
            tasks = tasks.toList(), testDesignPolicy = testDesign,
        ),
        CodingSuiteEntry.Kind.Delivers,
    )

    private fun reports(slug: String, name: String, description: String, vararg tasks: TaskDefinition) = CodingSuiteEntry(
        WorkflowDefinition(
            id = WorkflowDefinitionId("suite-$slug"), name = name, description = description,
            tasks = tasks.toList(), testDesignPolicy = TestDesignPolicy.None,
        ),
        CodingSuiteEntry.Kind.Reports,
    )

    private fun scheduled(
        slug: String,
        name: String,
        description: String,
        cadence: ScheduleCadence,
        objective: String,
        vararg tasks: TaskDefinition,
    ) = reports(slug, name, description, *tasks).copy(schedule = SuggestedSchedule(cadence, objective))

    private fun step(
        id: String,
        name: String,
        role: RoleDefinition,
        authority: RoleAuthority?,
        objective: String,
        after: List<String> = emptyList(),
        criteria: List<String> = emptyList(),
        humanApproval: Boolean = false,
        onFailureOf: String? = null,
    ) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = name,
        objective = objective,
        roleId = role.id,
        requiredRoleAuthority = authority,
        dependsOn = after.map(::TaskDefinitionId).toSet(),
        condition = onFailureOf?.let { TaskCondition.OnFailure(TaskDefinitionId(it)) } ?: TaskCondition.Always,
        acceptanceCriteria = criteria.map(::AcceptanceCriterion),
        approvalPolicy = if (humanApproval) ApprovalPolicy.HumanApproval else ApprovalPolicy.None,
        escalationPolicy = EscalationPolicy.RequireHumanDecision,
        executor = TaskExecutor.RoleAgent(role.id),
    )

    private fun releaseGate(id: String, label: String, after: List<String>) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = label,
        objective = "A person decides whether this goes out.",
        roleId = release.id,
        requiredRoleAuthority = RoleAuthority.ApproveRelease,
        dependsOn = after.map(::TaskDefinitionId).toSet(),
        executor = TaskExecutor.HumanApproval(label),
    )
}
