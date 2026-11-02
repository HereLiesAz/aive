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

The desktop folder picker verifies a selected directory with `git rev-parse --show-toplevel` before storing it as a Local Git project repository.

Local Git execution is intentionally desktop-only because browser and Android runtimes do not have arbitrary filesystem access to a desktop working tree.

## Running tests (`TaskExecutor.TestRunner`)

On Desktop, a `TestRunner` task in a Local Git project runs its test command on this computer (`LocalTestRunnerClient`). Android and the web build register no test runner; there, tests run through GitHub Actions or a script executor.

Because it runs repository code, the test task must come after a human-approval task, and that approval must have been granted in the run. Otherwise the task fails without running anything. Put the approval between the work that produces the change and the test:

~~~text
Implement (Local Git agent)  ->  Approve running tests (Human approval)  ->  Run tests (TestRunner "./gradlew test")
~~~

The command:

- is split into arguments without a shell. Single or double quotes group words (no escapes inside them). Shell syntax such as `&&`, `;`, `|`, `>`, `$VAR`, backticks, globs, `~` or a leading `NAME=value` is refused, not interpreted. On Windows, quote a path that contains `\`.
- defaults, when the task gives none, to the Gradle wrapper (`gradlew test`), else `npm test`.
- may name its program as a path (`./gradlew`, `scripts/test.sh`, resolved against the working directory) or by a bare name looked up on `PATH`.

Where it runs, from the newest upstream artifact that names one:

- the `worktreePath` of a `worktree-branch` operation, if it lies under `<repo>-worktrees/`;
- else its `headCommit` (for example the commit a Local Git agent made on its `haive/...` branch), checked out in a temporary detached worktree with hooks disabled and removed afterwards;
- else, when nothing upstream names one, the linked folder itself.

The process gets only an allowlisted environment (`PATH`, `HOME`, locale, temp folders, `JAVA_HOME`, Gradle and Android SDK locations, the Windows system variables) plus `CI=true`; provider keys and tokens are not passed. It is stopped after 20 minutes, when the app closes, or when its workflow stops polling it for two minutes (the run was cancelled or replaced); stopping ends the whole process tree.

The task completes when the command exits with 0 and fails otherwise. Either way it produces a `TestResult` artifact (the test report) with the command, working directory, result, duration and the last 32,000 characters of stdout and stderr, and metadata `exitCode`, `passed`, `timedOut`, `workspace`, `headCommit` or `worktreePath`, `stdoutChars`, `stderrChars` and `outputTruncated`. Downstream tasks, such as a reviewer or verifier, receive it as context. Runs are kept in memory, so a test still running when the app restarts reports Failed and is not run again on its own.

The test runner serves only this device's own runs; pool leases from other devices (distributed compute) cannot use it.
