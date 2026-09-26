# Repository services

The Aive treats repository services separately from AI providers. A project links to a repository source; repository-service credentials authorize host operations for that source.

## Sources

### GitHub

Connect a GitHub access token from **Repositories** in the control room.

Repository-operation tasks support:

- `status`
- `create-branch:<branch>`
- `open-pull-request:<source>:<target>:<title>`

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
- creates a dedicated `haive/...` branch
- commits all file actions atomically through the GitLab Repository Commits API
- emits the resulting commit diff, branch, commit ID, and compare URL as workflow evidence

GitLab workspace execution does not claim shell or test execution. Tests remain separate execution work until a GitLab CI executor is connected.

GitLab.com and self-managed GitLab repository URLs are mapped to that host's `/api/v4` endpoint.

### Local Git

On Desktop, The Aive can link a local Git working tree without a repository-service token. See `Local-Git-Repository-Operations.md` for the bounded local operation vocabulary. Supported desktop LLM providers can also execute governed Local Git coding tasks in isolated worktrees, leaving the user's source checkout untouched and preserving changes on dedicated `haive/...` branches.

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
