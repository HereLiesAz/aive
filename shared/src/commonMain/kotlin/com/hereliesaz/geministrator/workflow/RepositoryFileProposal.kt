package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A model's proposed full-file replacements, not authorization to write anything. */
@Serializable
data class RepositoryFileProposal(val baseCommit: String, val files: List<RepositoryProposedFile>) {
    /** Validate both imported and programmatically constructed proposals before any network write. */
    fun validate() {
        require(isGitObjectId(baseCommit)) { "Proposal requires a full base commit SHA" }
        validateProposalPaths(files.map { it.path })
        require(files.sumOf { it.content.encodeToByteArray().size.toLong() } <= MAX_TOTAL_BYTES.toLong()) { "Proposal exceeds total content limit" }
        for (file in files) {
            require(file.expectedBlob == null || isGitObjectId(file.expectedBlob)) { "Expected blob must be a full SHA or null" }
            require(file.content.encodeToByteArray().size <= MAX_FILE_BYTES && '\u0000' !in file.content) { "Proposed text file exceeds limits or contains NUL" }
        }
    }

    companion object {
        /** Read-only operation for an exact, host-owned snapshot of proposed edit targets. */
        const val READ_CONTEXT_OPERATION = "read-file-proposal-context"
        const val MAX_FILE_BYTES = 120_000
        const val MAX_TOTAL_BYTES = 500_000

        /** Bounds and path restrictions shared by the snapshot reader and the commit validator. */
        internal fun validateProposalPaths(paths: List<String>) {
            require(paths.size in 1..24) { "Proposal must contain 1 to 24 files" }
            val normalized = paths.map { it.lowercase() }
            require(normalized.distinct().size == paths.size) { "Duplicate file paths" }
            for (path in paths) {
                require(path.length in 1..1024 && path.split('/').all { part ->
                    part.isNotEmpty() && part != "." && part != ".." && !part.equals(".git", ignoreCase = true) &&
                        part.none { it.code < 32 || it.code == 127 || it == '\\' || it == ':' }
                }) { "Proposal contains an unsafe repository path" }
                require(normalized.none { it.startsWith(path.lowercase() + "/") }) {
                    "File proposal contains a file/directory collision"
                }
            }
        }

        /** Parse strictly, without permissive unknown fields, Markdown extraction or command interpretation. */
        fun parse(text: String): RepositoryFileProposal {
            require(text.length <= 1_000_000) { "Proposal JSON exceeds input limit" }
            return Json.decodeFromString<RepositoryFileProposal>(text).also { it.validate() }
        }
    }
}

/** Null [expectedBlob] means create only; otherwise replace exactly this existing regular file. */
@Serializable
data class RepositoryProposedFile(val path: String, val expectedBlob: String?, val content: String)

internal fun isGitObjectId(value: String): Boolean = value.matches(Regex("[0-9a-f]{40}"))

/**
 * A repository executor, not the inference provider, consumes the proposal. The producing task must
 * be a direct dependency of both the commit and a completed unconditional human approval. Refuse
 * held, missing, ambiguous or failed output; never fall back to arbitrary model text.
 */
internal fun TaskExecutorContext.approvedFileProposal(producer: TaskDefinitionId): RepositoryFileProposal {
    val source = definition.tasks.singleOrNull { it.id == producer } ?: error("Unknown proposal task")
    require(producer in task.dependsOn && !source.holdOutput) { "Proposal must be a released direct dependency" }
    val approved = definition.tasks.any { gate ->
        gate.id in task.dependsOn && gate.executor is TaskExecutor.HumanApproval &&
            gate.condition is TaskCondition.Always && producer in gate.dependsOn &&
            run.taskRuns[gate.id]?.status == TaskRunStatus.Completed
    }
    require(approved) { "File proposal requires a completed human approval depending on the proposal" }
    val sourceRun = run.taskRuns[producer]?.takeIf { it.status == TaskRunStatus.Completed }
        ?: error("Proposal task did not complete")
    val artifact = sourceRun.artifacts.singleOrNull { it.kind == ArtifactKind.CodeChange }
        ?: error("Expected exactly one code-change proposal artifact")
    require(artifact.taskRunId == sourceRun.id) { "Proposal artifact belongs to another task run" }
    return RepositoryFileProposal.parse(artifact.textContent ?: error("Proposal has no text content"))
}

/** A fixed keeper-authored operation selects the proposal task and a new dedicated branch. */
internal data class CommitFileProposalOperation(val producer: TaskDefinitionId, val branch: String) {
    companion object {
        const val PREFIX = "commit-files:"

        fun parse(operation: String): CommitFileProposalOperation {
            val parts = operation.removePrefix(PREFIX).split(':')
            require(operation.startsWith(PREFIX) && parts.size == 2 && parts[0].isNotBlank()) {
                "Expected commit-files:<proposal-task-id>:<new-aive-branch>"
            }
            val branch = GitRefNames.requireSafe(parts[1])
            require(branch.startsWith("aive/") && branch.length <= 200) { "Commit requires a dedicated aive/ branch" }
            return CommitFileProposalOperation(TaskDefinitionId(parts[0]), branch)
        }
    }
}
