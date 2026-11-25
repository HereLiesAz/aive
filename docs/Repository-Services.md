# Repository services

The Aive treats repository services separately from AI providers. A project links to a repository source; repository-service credentials authorize host operations for that source.

## Sources

### GitHub

Connect a GitHub access token from **Repositories** in the control room.

Repository-operation tasks support:

- `status`
- `create-branch:<branch>`
- `open-pull-request:<source>:<target>:<title>`
- `commit-files:<proposal-task-id>:<new-aive-branch>` or `commit-files:<proposal-task-id>:auto` (host-owned GitHub file commit; `auto` derives a dedicated branch from the run and task identity)

The host commit consumes one `CodeChange` text artifact from the named completed, released proposal task. It must be a direct dependency of both the commit and an unconditional human approval task, which must itself be a completed direct dependency of the commit. Inspect the proposal artifact before approving. An inference provider need not have a repository token or native write capability to generate this artifact; the repository executor uses the already linked GitHub credential. The proposal is data, never a command or script.

The artifact must be strict JSON, for example:

```json
{"baseCommit":"0123456789012345678901234567890123456789","files":[{"path":"README.md","expectedBlob":null,"content":"# New file\n"}]}
```

Replace the example SHA with the linked base's actual commit. `expectedBlob: null` creates a new file only; an existing regular file requires its exact blob SHA, and retains its mode. The host refuses a changed base, unsafe paths, duplicate or conflicting paths, truncated trees, symlinks, submodules, and replacements of directories. It prepares the tree and commit before creating a new `aive/` ref; it never updates a ref or opens or merges a PR. A failed final ref creation may leave unreachable Git objects, but never overwrites a branch. The bounds are 24 files, 120,000 UTF-8 bytes per file and 500,000 bytes total, inherited from the existing GitLab workspace action limits (enforced here in bytes rather than characters).

A keeper-authored read-only `read-file-proposal-context` task uses exactly one `repository_files` resource with literal paths, not globs. It returns complete bounded UTF-8 files and their immutable blob SHAs, or null for paths absent from the complete tree, as a JSON `CommandOutput` artifact. A subsequent model-only proposal task can depend on that artifact, retain the base and expected blob IDs, and supply the desired contents. The read operation uses the linked GitHub credential in Aive, never the model. It does not write or require a repository-capable inference provider.

At workflow preparation, an Auto, local, single-inference file-write contract with no external tools, one CodeChange/files-change output, no specified target branch and a literal repository_files path list is compiled into four steps: host snapshot, model proposal, human approval of the proposal, and host commit. The final step retains the original ID and output contract (BRANCH and FILES lines), so downstream dependencies are unchanged. The model retains its role but receives neither a repository handle nor tools, and repository capabilities inherited from its role are not required. The host rejects any proposal whose base or blob IDs differ from its snapshot or that adds an unlisted path. Any original plan gate stays on the proposal; the host commit additionally waits for a person's approval of the actual contents.

The planner is told to choose this route for known file paths. Unbounded, explicitly pinned, conditional, optional, distributed, compound, or otherwise incompatible write steps are not rewritten. GitLab and Local Git retain their existing workspace agents; these host file operations are unavailable for those backends. The existing repository mutation approval and context-taint rules remain in force.

The same GitHub connection is also used by the GitHub Actions executor.

### GitLab

Connect a GitLab personal access token from **Repositories** in the control room. The token must have permission to read the linked project and perform any requested write operation.

Repository-operation tasks support:

- `status`
- `create-branch:<branch>`
- `open-merge-request:<source>:<target>:<title>`

When both a GitLab repository credential and a supported LLM provider are connected, The Aive can also execute governed coding tasks directly against a linked GitLab project. The GitLab workspace agent:

- reads a bounded repository tree and selected file contents
- generates an approval-gated implementation plan when required
- validates structured create/update/delete file actions before any write
- creates a dedicated `aive/...` branch
- commits all file actions atomically through the GitLab Repository Commits API
- emits the resulting commit diff, branch, commit ID, and compare URL as workflow evidence

GitLab workspace execution does not claim shell or test execution. Tests remain separate execution work until a GitLab CI executor is connected.

Only GitLab.com is supported (`https://gitlab.com/api/v4`); repository operations reject other hosts, so self-managed GitLab instances do not work yet.

### Local Git

On Desktop, The Aive can link a local Git working tree without a repository-service token. See `Local-Git-Repository-Operations.md` for the bounded local operation vocabulary. Supported desktop LLM providers can also execute governed Local Git coding tasks in isolated worktrees, leaving the user's source checkout untouched and preserving changes on dedicated `aive/...` branches.

### Entire

On Desktop, with the `entire` command installed, The Aive signs in to Entire, clones Entire repositories, opens Trails and mirrors repositories, records Local Git runs as Entire checkpoints, and gives drifters a repository's recent checkpoints as context. See `Entire.md`.

## Orchestration artifacts

Repository operations emit normal workflow artifacts. The run overview consumes their metadata to show, when available:

- repository source and URL/path
- branch
- HEAD commit
- Local Git dirty-file count
- latest repository operation
- pull request or merge request
- release activity

Remote repository hosts do not report Local Git working-tree cleanliness, so The Aive does not label a remote repository clean or dirty based on host state.

## Failure behavior

Authentication, API, and repository-operation failures are normalized into failed workflow tasks. They therefore use the workflow's ordinary retry and escalation policy instead of failing the application runtime itself.
