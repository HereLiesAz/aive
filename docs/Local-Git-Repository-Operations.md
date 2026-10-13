# Local Git repository operations

On Desktop, The Aive can link a local Git working tree and execute bounded repository operations through `TaskExecutor.RepositoryOperation`.

Supported operation strings:

- `status`
- `fetch`
- `pull`
- `add-all`
- `push`
- `checkout:<ref>`
- `create-branch:<branch>`
- `worktree-branch:<branch>[@<base>]` — creates the branch from `<base>` (HEAD when omitted) in a new worktree at `<repo>-worktrees/<branch>` beside the repository, leaving the user's checkout, branch and uncommitted changes untouched; the artifact's `worktreePath`, `branch` and `headCommit` describe the new worktree
- `commit:<message>`
- `push-set-upstream:<branch>`

These are parsed into fixed `ProcessBuilder` argument lists. Repository operations are not arbitrary shell commands.

Each operation emits a `CommandOutput` artifact carrying repository metadata used by the orchestration UI:

- repository source
- operation
- command
- exit code
- current branch
- current HEAD commit
- dirty-file count

The desktop folder picker verifies a selected directory with `git rev-parse --show-toplevel` before storing it as a Local Git project repository.

Local Git execution is intentionally desktop-only because browser and Android runtimes do not have arbitrary filesystem access to a desktop working tree.
