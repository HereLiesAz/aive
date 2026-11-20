package com.hereliesaz.geministrator.providers.cli

/** POSIX shell quoting, pure. A quoted word is one literal word to `sh` and `bash`, whatever it holds. */
object PosixShell {
    /** [text] as one single-quoted word: `'` becomes `'\''`, and a NUL (which no shell can hold) is dropped. */
    fun quote(text: String): String = "'" + text.replace("\u0000", "").replace("'", "'\\''") + "'"
}

/** What [CliRunScript.build] decided: a script to hand to the Linux runtime's `bash`, or the plain reason it will not. */
sealed interface CliRunBuild {
    /** [script] goes to `bash -s` on standard input; [promptTruncated] says the prompt was cut to fit. */
    data class Ready(val script: String, val promptTruncated: Boolean) : CliRunBuild

    data class Refused(val reason: String) : CliRunBuild
}

/** One run of a vendor CLI inside the hidden Linux runtime (docs/Local-CLI-Agents.md, "On a phone"). Every field is checked by [CliRunScript.build]. */
data class CliRunRequest(
    val spec: CliAgentSpec,
    val mode: CliRunMode,
    /** The repository the runtime's git clones, or null for a question with no repository. */
    val repositoryUrl: String?,
    /** The branch or tag to clone (depth 1), or null for the remote's default. */
    val baseRef: String?,
    /** The new branch the work is committed to: `aive/cli-...`. */
    val branch: String,
    /** Names the run's folder in the runtime's home; letters, digits and `-` only survive. */
    val runId: String,
    val prompt: String,
    /** A fresh random hex string (16 to 64 digits) the result block is fenced with; the CLI never sees it. */
    val nonce: String,
    /** Hands the CLI its vendor key variable(s), if the keeper has them set in the runtime. Off by default. */
    val passKeys: Boolean = false,
    val allowProjectConfig: Boolean = false,
    /**
     * Whether the clone may use the one-shot variable `AIVE_GIT_TOKEN` (set by the host for this process only, never in a
     * command line or on disk) as an HTTP header for this https host alone. Ignored for a non-https address.
     */
    val useRepositoryToken: Boolean = false,
    val timeoutSeconds: Int = CliRunScript.DEFAULT_TIMEOUT_SECONDS,
    val commitSubject: String = "Aive CLI agent",
)

/**
 * Builds the one shell script The Aive hands to the hidden Linux runtime's `bash` (on standard input). Pure: no Android
 * types, nothing is run here. Every value that is not a constant of this file reaches the script only as a single-quoted
 * word in a variable assignment or an array ([PosixShell.quote]); the script body is fixed text that never contains one. The
 * prompt travels in a quoted here-document whose end marker holds the run's secret nonce.
 *
 * What the script does, in order: refuses unless the home folder is where it expects; finds the CLI (`command -v`); clones the
 * repository with the runtime's git (hooks off, no `ext::` or file transport, no prompts); makes the branch; refuses a checkout
 * that tracks the CLI's own project settings (unless allowed) or a symlink that leaves the tree; runs the CLI from a bare
 * environment (`env -i`) under `timeout`, its prompt on stdin or as its last argument; for an editing run, refuses a change to
 * Git's control files or an escaping symlink, commits with hooks off under a fixed identity and takes the patch; prints one
 * bounded, nonce-fenced result block ([CliRunResultParser]); and deletes its folder.
 */
object CliRunScript {
    /**
     * Inside the runtime, which sees the same paths a Termux session does (the app's private Termux-derived prefix is mapped there by
     * proot, so the pinned bootstrap's hard-coded paths hold). The run folder is under home.
     */
    const val RUNTIME_PREFIX = "/data/data/com.termux/files/usr"
    const val RUNTIME_HOME = "/data/data/com.termux/files/home"
    const val RUNTIME_PATH = "$RUNTIME_PREFIX/bin"

    /** The Termux packages every CLI needs, installed once when the runtime is prepared (the bootstrap itself has bash and the core tools). */
    val BASE_PACKAGES: List<String> = listOf("git", "openssh", "ripgrep", "nodejs-lts")
    const val DEFAULT_TIMEOUT_SECONDS = 30 * 60

    /** The three sections of the result block together stay far under any pipe or memory bound. */
    const val ANSWER_MAX_BYTES = 40_000
    const val STDERR_MAX_BYTES = 2_000
    const val PATCH_MAX_BYTES = 45_000

    /** The prompt is cut so the whole script stays small. */
    const val MAX_PROMPT_CHARS = 60_000
    private const val MAX_SCRIPT_BYTES = 300_000

    /** Name of the one-shot variable the host may set for the clone; see [CliRunRequest.useRepositoryToken]. */
    const val TOKEN_VARIABLE = "AIVE_GIT_TOKEN"

    /** Proxy and certificate variables passed on from the runtime's own environment when set; nothing else is, apart from the spec's own. */
    val PASSED_ENVIRONMENT: List<String> = listOf(
        "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "no_proxy", "all_proxy",
        "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS", "REQUESTS_CA_BUNDLE",
    )

    private val nonceShape = Regex("^[0-9a-f]{16,64}$")
    private val branchShape = Regex("^aive/cli-[A-Za-z0-9][A-Za-z0-9._-]{0,80}$")
    private val refShape = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,100}$")
    private val variableShape = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    private val binaryShape = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")
    private val npmSpecShape = Regex("^(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*(@[0-9]+\\.[0-9]+\\.[0-9]+)?$")
    private val aptPackageShape = Regex("^[a-z0-9][a-z0-9+.-]*$")
    private const val PATH_CHARS = "[A-Za-z0-9._~%+/-]"
    private val httpsUrl = Regex("^https://([A-Za-z0-9][A-Za-z0-9.-]*(:[0-9]{1,5})?)/[A-Za-z0-9._~%+-]$PATH_CHARS*$")
    private val sshUrl = Regex("^ssh://[A-Za-z0-9_][A-Za-z0-9_.-]*@[A-Za-z0-9][A-Za-z0-9.-]*(:[0-9]{1,5})?/[A-Za-z0-9._~%+-]$PATH_CHARS*$")
    private val scpUrl = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]*@[A-Za-z0-9][A-Za-z0-9.-]*:[A-Za-z0-9._~%+-]$PATH_CHARS*$")

    /** Why [url] may not be cloned, or null. Only `https://host/path`, `ssh://user@host/path` and `user@host:path`; no embedded password, no `-` start, no `ext::`. */
    fun repositoryUrlProblem(url: String): String? = when {
        url.length > 400 -> "The repository address is too long."
        url.any { it.isISOControl() || it.isWhitespace() } -> "The repository address has a space or control character."
        url.startsWith("-") -> "The repository address starts with a dash."
        url.contains("..") -> "The repository address has \"..\" in it."
        httpsUrl.matches(url) || sshUrl.matches(url) || scpUrl.matches(url) -> null
        else -> "Only https://, ssh:// and user@host:path repository addresses are cloned, with no password in them."
    }

    /** The `host[:port]` of an https address that passed [repositoryUrlProblem], else null. */
    fun httpsAuthority(url: String): String? = httpsUrl.matchEntire(url)?.groupValues?.getOrNull(1)

    /** The run's folder name: letters, digits and `-`, at most 40, never empty. */
    fun safeRunId(text: String): String = text.filter { (it.isLetterOrDigit() && it.code < 128) || it == '-' }.take(40).ifBlank { "run" }

    /** Where the run lives inside the runtime. */
    fun runFolder(runId: String): String = "$RUNTIME_HOME/.aive-cli/${safeRunId(runId)}"

    fun build(request: CliRunRequest): CliRunBuild {
        val spec = request.spec
        if (!nonceShape.matches(request.nonce)) return CliRunBuild.Refused("The run's nonce is not 16 to 64 hex digits.")
        if (!branchShape.matches(request.branch) || request.branch.contains("..") || request.branch.endsWith(".") || request.branch.endsWith(".lock")) {
            return CliRunBuild.Refused("The branch name \"${request.branch.take(60)}\" is not an aive/cli-... name of plain characters.")
        }
        val url = request.repositoryUrl
        if (url != null) repositoryUrlProblem(url)?.let { return CliRunBuild.Refused(it) }
        val baseRef = request.baseRef
        if (baseRef != null && (!refShape.matches(baseRef) || baseRef.contains(".."))) {
            return CliRunBuild.Refused("The base branch name has characters a branch name does not.")
        }
        if (url == null && request.mode == CliRunMode.EditFiles) return CliRunBuild.Refused("Editing needs a repository to clone; a question with no repository is read-only.")
        if (request.timeoutSeconds !in 30..7200) return CliRunBuild.Refused("The timeout must be between 30 seconds and 2 hours.")
        val binaries = spec.binaries
        if (binaries.isEmpty() || binaries.any { !binaryShape.matches(it) }) return CliRunBuild.Refused("${spec.displayName} has no usable program name.")
        val keyNames = if (request.passKeys) spec.optionalKeyEnvironment else emptyList()
        val environmentNames = (PASSED_ENVIRONMENT + spec.authEnvironment + keyNames).distinct()
        if (environmentNames.any { !variableShape.matches(it) }) return CliRunBuild.Refused("${spec.displayName} names an environment variable that is not a plain name.")

        val folder = runFolder(request.runId)
        val tree = "$folder/tree"
        val fixed = spec.fixedArguments(request.mode, tree)
        CliAgentSpecs.violation(fixed)?.let { return CliRunBuild.Refused("${spec.displayName} was not started: its command line contains $it, which the app never passes.") }

        val stdin = spec.promptChannel == CliPromptChannel.Stdin
        val limit = if (stdin) MAX_PROMPT_CHARS else minOf(MAX_PROMPT_CHARS, CliAgentSpec.MAX_ARGUMENT_PROMPT_CHARS)
        val clean = request.prompt.replace("\u0000", "").replace("\r\n", "\n")
        val prompt = clean.take(limit)
        val delimiter = "AIVE_PROMPT_END_${request.nonce}"
        if (prompt.contains(delimiter)) return CliRunBuild.Refused("The prompt contains the run's private marker.")

        val tokenHost = if (request.useRepositoryToken && url != null) httpsAuthority(url).orEmpty() else ""
        val hint = if (spec.androidInstall != null) " (press Install)" else ""
        val header = StringBuilder()
        fun assign(name: String, value: String) { header.append(name).append('=').append(PosixShell.quote(value)).append('\n') }
        fun array(name: String, values: List<String>) {
            header.append(name).append("=(").append(values.joinToString(" ") { PosixShell.quote(it) }).append(")\n")
        }
        assign("NONCE", request.nonce)
        assign("HOMEEXPECT", RUNTIME_HOME)
        assign("PATHVALUE", RUNTIME_PATH)
        assign("RUN", folder)
        assign("REPOURL", url.orEmpty())
        assign("BASEREF", baseRef.orEmpty())
        assign("BRANCH", request.branch)
        assign("MODE", if (request.mode == CliRunMode.EditFiles) "edit" else "read")
        assign("TIMEOUT", request.timeoutSeconds.toString())
        assign("STDINPROMPT", if (stdin) "1" else "0")
        assign("ALLOWCFG", if (request.allowProjectConfig) "1" else "0")
        assign("TOKENHOST", tokenHost)
        assign("MSG", request.commitSubject.lineSequence().firstOrNull().orEmpty().filter { !it.isISOControl() }.trim().take(72).ifBlank { "Aive CLI agent" })
        assign("PROVID", spec.id)
        assign("DNAME", spec.displayName)
        assign("HINT", hint)
        array("BINS", binaries)
        array("ARGS", fixed)
        array("ENVNAMES", environmentNames)
        array("CFGPATS", spec.projectConfig.map { it.lowercase().replace("\n", "") })
        header.append("IFS= read -r -d '' PROMPT <<'").append(delimiter).append("' || true\n")
        header.append(prompt).append('\n').append(delimiter).append('\n')

        val script = header.toString() + BODY.replace('§', '$')
        if (script.encodeToByteArray().size > MAX_SCRIPT_BYTES) return CliRunBuild.Refused("The prompt is too large to hand to the runtime.")
        return CliRunBuild.Ready(script, promptTruncated = clean.length > limit)
    }

    /** A script that reports, for each of [names], where the runtime finds it (`command -v`). Names are plain program names. */
    fun probeScript(nonce: String, names: List<String>): String? {
        if (!nonceShape.matches(nonce) || names.isEmpty() || names.size > 64 || names.any { !binaryShape.matches(it) }) return null
        return "NONCE=${PosixShell.quote(nonce)}\nNAMES=(${names.joinToString(" ") { PosixShell.quote(it) }})\n" + PROBE_BODY.replace('§', '$')
    }

    /**
     * A script that installs Termux packages (`apt-get install`, after `apt-get update` when [update]) and then, when [npmSpec]
     * is given, `npm install -g <name[@version]>`, and reports the exit code and the tail of the output. Null when a name is not
     * a plain package name.
     */
    fun installScript(nonce: String, aptPackages: List<String>, npmSpec: String?, update: Boolean): String? {
        if (!nonceShape.matches(nonce) || aptPackages.size > 16 || aptPackages.any { !aptPackageShape.matches(it) }) return null
        if (npmSpec != null && !npmSpecShape.matches(npmSpec)) return null
        if (aptPackages.isEmpty() && npmSpec == null && !update) return null
        return "NONCE=${PosixShell.quote(nonce)}\nUPDATE=${if (update) "1" else "0"}\nNPMSPEC=${PosixShell.quote(npmSpec.orEmpty())}\n" +
            "APTPKGS=(${aptPackages.joinToString(" ") { PosixShell.quote(it) }})\n" + INSTALL_BODY.replace('§', '$')
    }

    // Fixed texts. `§` stands for `$` (Kotlin would read it as its own), and is replaced before the text is used.
    private const val PROBE_BODY = """
printf 'AIVE-READY %s\n' "§NONCE"
for n in "§{NAMES[@]}"; do
  p="§(command -v "§n" 2>/dev/null)"
  case "§p" in
    /*) printf 'AIVE-FOUND %s %s %s\n' "§NONCE" "§n" "§p" ;;
    *) printf 'AIVE-MISSING %s %s\n' "§NONCE" "§n" ;;
  esac
done
"""

    private const val INSTALL_BODY = """
export DEBIAN_FRONTEND=noninteractive
CODE=0
OUT=""
if [ "§UPDATE" = 1 ]; then
  OUT="§(timeout -k 10 600 apt-get update 2>&1)"; CODE=§?
fi
if [ "§CODE" = 0 ] && [ "§{#APTPKGS[@]}" -gt 0 ]; then
  OUT="§OUT§(timeout -k 10 1500 apt-get install -y -o Dpkg::Options::=--force-confold -- "§{APTPKGS[@]}" 2>&1)"; CODE=§?
fi
if [ "§CODE" = 0 ] && [ -n "§NPMSPEC" ]; then
  if ! command -v npm >/dev/null 2>&1; then printf 'AIVE-NONPM %s\n' "§NONCE"; exit 0; fi
  OUT="§OUT§(timeout -k 10 900 npm install -g --no-audit --no-fund -- "§NPMSPEC" 2>&1)"; CODE=§?
fi
printf 'AIVE-INSTALL %s %s\n' "§NONCE" "§CODE"
printf '%s\n' "§OUT" | tail -c 2000
printf '\nAIVE-INSTALL-END %s\n' "§NONCE"
"""

    private const val BODY = """
set -u
umask 077
sect() { printf 'AIVE-%s-BEGIN %s\n' "§1" "§NONCE"; if [ -f "§2" ]; then "§3" -c "§4" "§2"; fi; printf '\nAIVE-%s-END %s\n' "§1" "§NONCE"; }
fin() {
  R="§(printf '%s' "§{2:-}" | tr '\r\n' '  ' | cut -c1-300)"
  {
    printf 'AIVE-RESULT-BEGIN %s\n' "§NONCE"
    printf 'status=%s\n' "§1"
    printf 'exit=%s\n' "§{EXITCODE:-0}"
    printf 'branch=%s\n' "§{BRANCHOUT:-}"
    printf 'commit=%s\n' "§{COMMITOUT:-}"
    printf 'patch-truncated=%s\n' "§{PTRUNC:-0}"
    printf 'reason=%s\n' "§R"
    sect ANSWER "§RUN/out" tail $ANSWER_MAX_BYTES
    sect STDERR "§RUN/err" tail $STDERR_MAX_BYTES
    sect PATCH "§RUN/patch" head $PATCH_MAX_BYTES
    printf 'AIVE-RESULT-END %s\n' "§NONCE"
  }
  exit 0
}
badlink() {
  find "§TREE" -path "§TREE/.git" -prune -o -type l -print0 2>/dev/null | while IFS= read -r -d '' l; do
    t="§(readlink -- "§l")"
    case "§t" in /*) r="§(realpath -m -- "§t" 2>/dev/null)" ;; *) r="§(realpath -m -- "§(dirname -- "§l")/§t" 2>/dev/null)" ;; esac
    case "§r" in "§TREEREAL"|"§TREEREAL"/*) ;; *) printf '%s\n' "§{l#"§TREE"/}"; break ;; esac
  done
}
doclone() { timeout -k 5 300 "§{G[@]}" clone "§{CL[@]}" -- "§REPOURL" "§TREE" >"§RUN/clone.out" 2>&1; }
[ "§HOME" = "§HOMEEXPECT" ] || fin refused "The runtime's home folder is not where The Aive expects it."
case "§RUN" in "§HOME"/.aive-cli/?*) ;; *) fin refused "The run folder is not under the runtime's home." ;; esac
mkdir -p -- "§HOME/.aive-cli" && chmod 700 "§HOME/.aive-cli" && rm -rf -- "§RUN" && mkdir -- "§RUN" && mkdir -- "§RUN/tmp" || fin failed "Could not make a work folder in the runtime."
trap 'rm -rf -- "§RUN"' EXIT
TREE="§RUN/tree"
BIN=""
for b in "§{BINS[@]}"; do
  p="§(command -v "§b" 2>/dev/null)"
  case "§p" in /*) BIN="§p"; break ;; esac
done
[ -n "§BIN" ] || fin refused "§DNAME is not installed in the runtime§HINT"
export GIT_TERMINAL_PROMPT=0
: "§{GIT_SSH_COMMAND:=ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new}"; export GIT_SSH_COMMAND
G=(git -c core.hooksPath=/dev/null -c core.fsmonitor=false -c core.autocrlf=false -c protocol.ext.allow=never -c protocol.file.allow=never)
if [ -n "§REPOURL" ]; then
  CL=(--depth 1 --no-tags --single-branch)
  if [ -n "§BASEREF" ]; then CL+=(--branch "§BASEREF"); fi
  if [ -n "§TOKENHOST" ] && [ -n "§{AIVE_GIT_TOKEN:-}" ]; then
    ( export GIT_CONFIG_COUNT=1 "GIT_CONFIG_KEY_0=http.https://§TOKENHOST/.extraHeader" "GIT_CONFIG_VALUE_0=Authorization: Basic §(printf 'x-access-token:%s' "§AIVE_GIT_TOKEN" | base64 -w0)"; doclone ) || fin failed "The runtime's git could not clone the repository: §(tail -c 250 "§RUN/clone.out")"
  else
    doclone || fin failed "The runtime's git could not clone the repository (a private repository needs the token option): §(tail -c 250 "§RUN/clone.out")"
  fi
  unset AIVE_GIT_TOKEN
  TREEREAL="§(realpath -- "§TREE")"
  BASE="§("§{G[@]}" -C "§TREE" rev-parse HEAD 2>/dev/null)" || fin failed "The clone has no commit to work from."
  "§{G[@]}" -C "§TREE" checkout -q -b "§BRANCH" || fin failed "Could not make the branch §BRANCH."
  BRANCHOUT="§BRANCH"
  if [ "§ALLOWCFG" != 1 ] && [ "§{#CFGPATS[@]}" -gt 0 ]; then
    GP=()
    for pt in "§{CFGPATS[@]}"; do GP+=(-e "/§pt"); done
    HIT="§("§{G[@]}" -C "§TREE" ls-files | tr 'A-Z' 'a-z' | sed 's|^|/|' | grep -F "§{GP[@]}" | head -n 1)"
    [ -z "§HIT" ] || fin refused "This repository tracks §DNAME project settings (§HIT), which can make it run commands, so it was not started."
  fi
  LK="§(badlink)"
  [ -z "§LK" ] || fin refused "This repository tracks a symbolic link that points outside the checkout (§LK), so §DNAME was not started."
else
  unset AIVE_GIT_TOKEN
  mkdir -- "§TREE" || fin failed "Could not make a folder to work in."
  TREEREAL="§(realpath -- "§TREE")"
fi
printf '%s' "§PROMPT" > "§RUN/prompt"
E=("PATH=§PATHVALUE" "HOME=§HOME" "TMPDIR=§RUN/tmp" "LANG=C.UTF-8" "NO_COLOR=1" "TERM=dumb")
for v in "§{ENVNAMES[@]}"; do
  if [ -n "§{!v:-}" ]; then E+=("§v=§{!v}"); fi
done
ARGV=("§{ARGS[@]}")
if [ "§STDINPROMPT" = 1 ]; then INFILE="§RUN/prompt"; else INFILE=/dev/null; ARGV+=("§(cat "§RUN/prompt")"); fi
cd "§TREE" || fin failed "Could not enter the work folder."
env -i "§{E[@]}" timeout -k 10 "§TIMEOUT" "§BIN" "§{ARGV[@]}" <"§INFILE" >"§RUN/out" 2>"§RUN/err"
EXITCODE=§?
if [ "§EXITCODE" = 124 ] || [ "§EXITCODE" = 137 ]; then fin failed "§DNAME timed out and was stopped."; fi
if [ "§EXITCODE" != 0 ]; then fin failed "§DNAME failed (exit §EXITCODE)."; fi
if [ -n "§REPOURL" ] && [ "§MODE" = edit ]; then
  CH="§("§{G[@]}" -C "§TREE" status --porcelain=v1 -z --untracked-files=all | tr '\0' '\n')"
  if printf '%s\n' "§CH" | grep -qiE '(^|[ /])\.git(attributes|modules)?(/|§)'; then fin failed "§DNAME changed a file that controls how Git behaves; the change was discarded."; fi
  LK="§(badlink)"
  [ -z "§LK" ] || fin failed "§DNAME made a symbolic link that points outside the checkout (§LK); the change was discarded."
  "§{G[@]}" -C "§TREE" add -A >/dev/null 2>&1
  if ! "§{G[@]}" -C "§TREE" diff --cached --quiet; then
    "§{G[@]}" -C "§TREE" -c user.name=Aive -c user.email=aive@localhost -c commit.gpgsign=false commit --no-verify -q -m "§MSG" -m "Aive-Provider: §PROVID" >/dev/null 2>&1 || fin failed "Could not commit §DNAME's changes."
    COMMITOUT="§("§{G[@]}" -C "§TREE" rev-parse HEAD)"
    "§{G[@]}" -C "§TREE" diff --no-ext-diff --no-textconv "§BASE" "§COMMITOUT" > "§RUN/patch" 2>/dev/null
    if [ "§(wc -c < "§RUN/patch")" -gt $PATCH_MAX_BYTES ]; then PTRUNC=1; fi
  fi
fi
fin ok ""
"""
}
