# Entire

[Entire](https://entire.com) keeps agent sessions in git: each commit an agent made carries an `Entire-Checkpoint` trailer naming a checkpoint, which holds the session's prompts, transcript and files touched. The Aive supports it on Desktop, through the `entire` command ([install](https://docs.entire.com/cli/installation)). Nothing here runs on Android or the web.

The Aive finds `entire` on `PATH` (absolute entries only) or where its installers put it (`/opt/homebrew/bin`, `/usr/local/bin`, `~/.local/bin`, `~/go/bin`, …). It always runs it with a fixed argument list, never through a shell.

## The Aive as an Entire agent

With **Record runs as checkpoints** on (Repositories → Entire), each Local Git coding run in a repository where Entire is set up (`entire enable`) becomes a checkpoint:

1. While the run works, The Aive writes its transcript to `~/.aive/entire/sessions/<session>.jsonl`: the task, the plan, the diff, the files changed and the tokens used.
2. It commits in its isolated worktree as before, with git hooks still disabled, so no repository code runs on the host.
3. It then runs `entire session attach <session> --agent aive --force` in that worktree. Entire adds the `Entire-Checkpoint` trailer to the run's commit (on its own `haive/...` branch; the keeper's checkout is untouched), and the checkpoint is pushed with that branch.
4. The run shows an **Entire checkpoint** artifact with what `entire` reported. If attaching fails, the commit stands and the artifact says why.

Entire reads the transcript through `entire-agent-aive`, an [external agent plugin](https://github.com/entireio/cli/blob/main/docs/architecture/external-agent-protocol.md) (protocol 1). The Aive writes that launcher to `~/.aive/bin` each time it attaches, pointing at the running installation (`<The Aive> entire-agent <subcommand>`), and puts that folder first on `PATH` for the `entire` it starts. The plugin declares no hooks; it reads sessions, transcripts, prompts, changed files and token counts, and refuses any transcript outside the sessions folder.

Entire honours external agents only from an untracked `.entire/settings.local.json`. The Aive copies the keeper's Entire settings into the run's temporary worktree and allows external agents there alone; the keeper's own `.entire/settings.local.json` is never changed. The worktree is removed after the run.

## Checkpoints as context

Before a Local Git run plans, its drifter reads the recent checkpoints on the repository's current branch (`entire checkpoint explain --json`, and for the newest three the intent and summary from `entire checkpoint explain <id>`): who worked (Claude Code, Codex, Cursor, The Aive, …), on which commit, what they were asked and what they did. This happens whenever `entire` is installed and the repository has Entire set up, whether or not runs are recorded.

## Entire as a repository host

From Repositories → Entire:

- **Log in to Entire** runs `entire login`, which finishes in the browser.
- **Clone**, signed in, takes `/gh/<owner>/<repo>` (a GitHub mirror) or `/et/<project>/<repo>` (an Entire-native repository), asks for a folder, and runs `entire repo clone`. Link the clone to a project from the Overview screen as a Local Git repository.

Repository-operation tasks on a Local Git project add two operations (see `Local-Git-Repository-Operations.md`):

- `entire-trail:<title>` — `entire trail create --title <title>`: publishes the current branch and opens a Trail for review.
- `entire-mirror:</gh|et/owner/repo>` — `entire repo mirror add <ref> --no-wait`: mirrors a repository onto Entire.

Both need `entire` and a signed-in account; without the command they fail with a pointer to the installation page.
