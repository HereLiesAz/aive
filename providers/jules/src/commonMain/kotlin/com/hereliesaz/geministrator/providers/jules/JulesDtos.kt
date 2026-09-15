package com.hereliesaz.geministrator.providers.jules

import kotlinx.serialization.Serializable

@Serializable
data class JulesListSourcesResponse(
    val sources: List<JulesSource> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class JulesSource(
    val name: String,
    val id: String,
    val githubRepo: JulesGithubRepo? = null,
)

@Serializable
data class JulesGithubRepo(
    val owner: String,
    val repo: String,
    val isPrivate: Boolean = false,
    val defaultBranch: JulesGithubBranch? = null,
    val branches: List<JulesGithubBranch> = emptyList(),
)

@Serializable
data class JulesGithubBranch(
    val displayName: String,
)

@Serializable
data class JulesCreateSessionRequest(
    val prompt: String,
    val title: String? = null,
    val sourceContext: JulesSourceContext? = null,
    val requirePlanApproval: Boolean = false,
    val automationMode: String? = null,
)

@Serializable
data class JulesSourceContext(
    val source: String,
    val githubRepoContext: JulesGithubRepoContext? = null,
)

@Serializable
data class JulesGithubRepoContext(
    val startingBranch: String,
)

@Serializable
data class JulesSession(
    val name: String,
    val id: String,
    val prompt: String? = null,
    val title: String? = null,
    val state: String? = null,
    val url: String? = null,
    val outputs: List<JulesSessionOutput> = emptyList(),
    val createTime: String? = null,
    val updateTime: String? = null,
)

@Serializable
data class JulesSessionOutput(
    val pullRequest: JulesPullRequest? = null,
)

@Serializable
data class JulesPullRequest(
    val url: String,
    val title: String,
    val description: String? = null,
)

@Serializable
data class JulesListActivitiesResponse(
    val activities: List<JulesActivity> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class JulesActivity(
    val name: String,
    val id: String,
    val originator: String? = null,
    val description: String? = null,
    val createTime: String? = null,
    val artifacts: List<JulesArtifact> = emptyList(),
    val planGenerated: JulesPlanGenerated? = null,
    val planApproved: JulesPlanApproved? = null,
    val userMessaged: JulesUserMessaged? = null,
    val agentMessaged: JulesAgentMessaged? = null,
    val progressUpdated: JulesProgressUpdated? = null,
    val sessionCompleted: JulesSessionCompleted? = null,
    val sessionFailed: JulesSessionFailed? = null,
)

@Serializable
data class JulesPlanGenerated(
    val plan: JulesPlan,
)

@Serializable
data class JulesPlan(
    val id: String,
    val steps: List<JulesPlanStep> = emptyList(),
    val createTime: String? = null,
)

@Serializable
data class JulesPlanStep(
    val id: String,
    val index: Int,
    val title: String,
    val description: String? = null,
)

@Serializable
data class JulesPlanApproved(
    val planId: String,
)

@Serializable
data class JulesUserMessaged(
    val userMessage: String,
)

@Serializable
data class JulesAgentMessaged(
    val agentMessage: String,
)

@Serializable
data class JulesProgressUpdated(
    val title: String,
    val description: String? = null,
)

@Serializable
class JulesSessionCompleted

@Serializable
data class JulesSessionFailed(
    val reason: String,
)

@Serializable
data class JulesArtifact(
    val changeSet: JulesChangeSet? = null,
    val bashOutput: JulesBashOutput? = null,
    val media: JulesMedia? = null,
)

@Serializable
data class JulesChangeSet(
    val source: String,
    val gitPatch: JulesGitPatch,
)

@Serializable
data class JulesGitPatch(
    val baseCommitId: String,
    val unidiffPatch: String,
    val suggestedCommitMessage: String? = null,
)

@Serializable
data class JulesBashOutput(
    val command: String,
    val output: String,
    val exitCode: Int,
)

@Serializable
data class JulesMedia(
    val mimeType: String,
    val data: String,
)

@Serializable
data class JulesSendMessageRequest(
    val prompt: String,
)
