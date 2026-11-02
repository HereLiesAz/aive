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
- `entire-trail:<title>` — publishes the current branch to Entire and opens a Trail for it (`entire trail create`); see `Entire.md`
- `entire-mirror:</gh|et/owner/repo>` — mirrors a repository onto Entire (`entire repo mirror add --no-wait`); see `Entire.md`

These are parsed into fixed `ProcessBuilder` argument lists (the Entire operations run the `entire` command in the repository). Repository operations are not arbitrary shell commands.

Each operation emits a `CommandOutput` artifact carrying repository metadata used by the orchestration UI:

- repository source
- operation
- command
- exit code
- current branch
- current HEAD commit
- dirty-file count

## A checkpoint per task

Coding tasks run by the Local Git workspace agent (Desktop) each commit their own changes, so a workflow run leaves one Git checkpoint per DAG node that touched the repository:

- Each task works in its own temporary worktree, starting from the newest `headCommit` among its dependencies' artifacts (else the repository's HEAD).
- Its commit's subject is `Haive: <objective>`; the body names the task and the run as trailers:

~~~
Haive-Task: <task name> (<task id>)
Haive-Run: <workflow run id>
Haive-Task-Run: <task run id>
~~~

- The commit then lands on the run's branch, `haive/run-<run id>`: the first task's commit creates it, and each later task whose base is still that branch's tip fast-forwards it. A task that started elsewhere (a parallel sibling got there first), or a run branch checked out in some worktree, keeps the commit on the task's own `haive/<task run>-<id>` branch instead. Branches only ever move by compare-and-swap, with hooks disabled.
- A task whose patch leaves the repository unchanged makes no commit and no checkpoint; its `CommandOutput` artifact carries the unchanged `headCommit`, so later tasks continue from it.
- The task's `CodeChange` artifact records `checkpointCommit` (the commit's sha), `headCommit`, `branch`, and, inside a workflow run, `workflowRunId` and `taskName`. The creature's **Work** tab shows it as **Checkpoint**: the short sha and its branch.
- With Entire recording on, each task's commit is attached as its own Entire checkpoint (`Entire.md`).

Nothing is pushed. This is Desktop only: Android and the web have no Local Git and make no checkpoints, and other providers are unchanged.

The desktop folder picker verifies a selected directory with `git rev-parse --show-toplevel` before storing it as a Local Git project repository.

Local Git execution is intentionally desktop-only because browser and Android runtimes do not have arbitrary filesystem access to a desktop working tree.
