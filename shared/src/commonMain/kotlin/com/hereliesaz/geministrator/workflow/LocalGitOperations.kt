package com.hereliesaz.geministrator.workflow

/**
 * The Local Git operation grammar, shared by the desktop client (the git command line) and the
 * Android client (JGit over an imported copy), so a workflow means the same thing on both.
 * Operations are parsed into fixed structures; they are never shell commands.
 */
sealed interface LocalGitOperation {
    val label: String

    data object Status : LocalGitOperation {
        override val label = "Repository status"
    }

    data object Fetch : LocalGitOperation {
        override val label = "Fetch remotes"
    }

    data object Pull : LocalGitOperation {
        override val label = "Pull fast-forward"
    }

    data object AddAll : LocalGitOperation {
        override val label = "Stage all changes"
    }

    data object Push : LocalGitOperation {
        override val label = "Push current branch"
    }

    data class Checkout(val ref: String) : LocalGitOperation {
        override val label = "Checkout $ref"
    }

    data class CreateBranch(val branch: String) : LocalGitOperation {
        override val label = "Create branch $branch"
    }

    /**
     * Creates [branch] from [base] (HEAD when absent) in its own working copy beside the
     * repository, at [worktreeDirectory], so a task works in isolation and the user's checkout,
     * its branch and its uncommitted changes are left alone. Desktop uses a linked `git worktree`;
     * Android, whose JGit has no linked worktrees, a local clone on the new branch.
     */
    data class WorktreeBranch(val branch: String, val base: String?) : LocalGitOperation {
        override val label = "Create branch $branch in a worktree"

        /** `<repo>-worktrees/<branch>`, beside the repository root at [rootPath]. */
        fun worktreeDirectory(rootPath: String): String {
            val root = rootPath.trimEnd('/', '\\')
            val separator = maxOf(root.lastIndexOf('/'), root.lastIndexOf('\\'))
            val parent = if (separator >= 0) root.substring(0, separator) else "."
            val name = root.substring(separator + 1)
            return "$parent/$name-worktrees/${branch.replace('/', '-')}"
        }
    }

    data class Commit(val message: String) : LocalGitOperation {
        override val label = "Commit staged changes"
    }

    data class PushSetUpstream(val branch: String) : LocalGitOperation {
        override val label = "Push $branch and set upstream"
    }

    /**
     * Android only: writes [branch]'s committed files (the current branch when absent) back to the
     * folder the repository was imported from. Imported copies live in app storage, so nothing
     * reaches the picked folder until this runs.
     */
    data class Export(val branch: String?) : LocalGitOperation {
        override val label = "Export ${branch ?: "current branch"} to the picked folder"
    }

    companion object {
        const val SUPPORTED = "status, fetch, pull, add-all, push, checkout:<ref>, create-branch:<branch>, " +
            "worktree-branch:<branch>[@<base>], commit:<message>, push-set-upstream:<branch>, export[:<branch>]"

        fun parse(raw: String): LocalGitOperation {
            val operation = raw.trim()
            return when {
                operation == "status" -> Status
                operation == "fetch" -> Fetch
                operation == "pull" -> Pull
                operation == "add-all" -> AddAll
                operation == "push" -> Push
                operation == "export" -> Export(null)
                operation.startsWith("export:") -> Export(requireSafeRef(operation.substringAfter(':')))
                operation.startsWith("checkout:") -> Checkout(requireSafeRef(operation.substringAfter(':')))
                operation.startsWith("create-branch:") -> CreateBranch(requireSafeRef(operation.substringAfter(':')))
                operation.startsWith("worktree-branch:") -> operation.substringAfter(':').let { spec ->
                    WorktreeBranch(
                        branch = requireSafeRef(spec.substringBefore('@')),
                        base = spec.substringAfter('@', "").takeIf(String::isNotEmpty)?.let(::requireSafeRef),
                    )
                }
                operation.startsWith("commit:") -> Commit(
                    operation.substringAfter(':').trim().also {
                        require(it.isNotEmpty()) { "Commit message is required" }
                    },
                )
                operation.startsWith("push-set-upstream:") ->
                    PushSetUpstream(requireSafeRef(operation.substringAfter(':')))
                else -> error("Unsupported Local Git operation '$operation'. Supported operations: $SUPPORTED")
            }
        }

        private fun requireSafeRef(raw: String): String = GitRefNames.requireSafe(raw, "Git ref")
    }
}
