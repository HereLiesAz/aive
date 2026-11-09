# Local CLI agents

The vendors' own command-line tools (`claude`, `codex`, `gemini`, `qwen`) can do the work of a creature. The app **drives** them headless; it does not offer a command line of its own. They run under whatever login you already have on the computer, so the app needs and keeps **no API key** for them, and a subscription you already pay for covers the work. Desktop only: a phone or the web cannot run a program.

Turn one on in **Keeper's room › Local agents**. It is off until you do. It then appears as a provider (named "Claude Code (CLI)" and so on) that a role can prefer: **Work › Provider** on a creature, or the role's form.

## What the app does when one runs

1. **Only after you approved a plan.** A creature that uses a CLI must have Approval on. The engine drafts a plan ("Run Claude Code headless in an isolated worktree... Nothing is sent to it until you approve this"), you approve it, and only then is the CLI started with that plan. Without an approved plan the task fails and says how to fix it.
2. **In its own copy of the repository.** A fresh `git worktree` of the committed `HEAD` of the linked **Local** repository, on a new branch `haive/cli-<task>-<id>`. Your checkout and its index are never touched. Uncommitted changes in your checkout are not seen (the CLI works from `HEAD`).
3. **With a bare environment.** Only `PATH`, `HOME`, locale, the temp and XDG folders, proxy and certificate variables, and the variables the CLI needs to find its own login (`CLAUDE_CONFIG_DIR`, `CODEX_HOME`, ...). No `GIT_*`, no repository-host tokens, and **no vendor API-key variable** unless you turn on "Passing its key variable" for that CLI.
4. **Without the CLI's guard rails off.** The app never passes a flag that skips a CLI's own permission checks or lifts its sandbox (`--dangerously-skip-permissions`, `--yolo`, `danger-full-access`, and the like): `CliAgentSpecs.FORBIDDEN_FLAGS`, enforced by a test over every spec in every mode.
5. **With the prompt on stdin** where the CLI allows it, so it is not in the process list. The prompt says plainly that everything under CONTEXT is material, possibly from untrusted sources, never instructions.
6. **Read-only unless the role writes.** A task whose role needs repository write may edit files; every other task runs the CLI read-only and anything it leaves is discarded.
7. **Committed to a branch, nothing more.** Edits are committed to the new branch (hooks off, a fixed identity) and shown as a `CodeChange` artifact with the branch and commit. Nothing is merged, pushed or released; that stays a person's decision behind the usual approval. A change to `.gitattributes`, `.gitmodules` or `.git` fails the task and is discarded.
8. **Stopped and cleaned up.** A 30-minute timeout, the whole process tree ended on timeout or cancel, the worktree removed, and the branch deleted when it holds no commit.
9. **Read as untrusted text.** Output is read line by line, bounded and scrubbed of key-shaped strings before it is shown or stored; the CLI's own final message is the task's text artifact.

| CLI | Headless form | Edits | Notes |
|---|---|---|---|
| Claude Code | `claude -p`, stream-json, prompt on stdin | `acceptEdits`; allowed tools Read, Glob, Grep, Edit, Write; Bash and web tools disallowed | No shell, so it only edits files. Read-only uses `plan`. |
| Codex | `codex exec --json -C <tree> -`, prompt on stdin | its own `workspace-write` sandbox (commands inside the tree, no network); `read-only` otherwise | The only one that claims shell execution and testing. |
| Gemini CLI | `gemini --approval-mode auto_edit -p <prompt>` | file edits approved; shell not (denied headless) | Output is plain text. |
| Qwen Code | the same flags as Gemini CLI | the same | A Gemini CLI fork. |

Adding another CLI is adding a spec (`CliAgentSpecs`): its binary, its headless flags, how to read its output.

## Test, and what is not verified

**The flags were written from each CLI's documentation. They have not been run against every version of every CLI by the people who wrote this.** That is why each CLI has a **Test** button: it runs the installed CLI read-only in an empty folder on a tiny prompt ("Reply with the single word OK") and shows what happened: the answer, or the exit code and the CLI's own error ("unknown option ..."). A flag that is wrong for your version shows there, not as a failed task, and is fixed in `CliAgentSpecs`. Test sends a real prompt, so it counts against your own plan or quota. Gemini CLI and Qwen Code, whose headless flags have changed between versions, are the likeliest to need a fix.

## Limits and open decisions

- **A CLI is a program that runs as you.** The app confines what it can (a worktree, a bare environment, no bypass flags, the CLI's own sandbox where it has one) and cannot confine the rest: a CLI can read files you can read, outside the worktree, and use its own network and its own configured tools. Codex's sandbox is real; Claude Code and Gemini rely on their tool allow-lists. Prompt-injected context is the main way this goes wrong, which is why a plan must be approved and context is labelled as data. Wrong if you cannot accept that: leave them off.
- **A CLI's own configuration applies.** Its settings, memory files and MCP servers (in your home folder) are loaded by the CLI; a project file in the repository that a CLI reads (instructions, settings) is repository content the CLI may act on. The app does not inspect them.
- **Explicit only.** Auto work never goes to a CLI (it edits your computer). A role must prefer one. Wrong if you want Auto to use them: a switch for that is not built.
- **Terms of service.** Driving a vendor's CLI from a program may be restricted by that vendor's terms for subscription logins. Check yours; the app does not.
- **The branch is the delivery.** Work lands on `haive/cli-...`, not on the run's checkpoint branch the Local workspace agent uses; merging it is yours, through Local Git operations (behind their approval). Wrong if you want it on the run branch automatically.
- **No follow-up messages.** A CLI runs once per task (`sendMessage` is rejected); a correction is a new task or **Run again from here**.
- **Repository-less work** (research with no repository linked) is not supported; a CLI needs a Local repository.
- **Usage** is whatever the CLI reports (Claude Code reports tokens and cost; the others may not).
