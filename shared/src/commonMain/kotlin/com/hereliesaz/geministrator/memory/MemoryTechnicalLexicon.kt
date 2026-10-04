package com.hereliesaz.geministrator.memory

/**
 * Curated software vocabulary that WordNet either lacks or gets wrong (its first sense of "bug" is
 * the insect, of "commit" is "perpetrate"). An entry here decides the sense before WordNet is
 * consulted: [TechTerm.canonical] groups synonyms under one tag, [TechTerm.synonyms] become the
 * tag's aliases, [TechTerm.broader] becomes an implied tag.
 *
 * Kept small and inspectable on purpose. Add a term when WordNet's sense for it is wrong in
 * software text, or when it is missing and common.
 */
internal data class TechTerm(
    val canonical: String,
    val pos: WordNetLexicon.Pos,
    val synonyms: List<String> = emptyList(),
    val broader: List<String> = emptyList(),
)

internal object MemoryTechnicalLexicon {
    private val nouns = HashMap<String, TechTerm>()
    private val verbs = HashMap<String, TechTerm>()

    private fun noun(canonical: String, synonyms: String = "", broader: String = "") =
        register(nouns, TechTerm(canonical, WordNetLexicon.Pos.Noun, split(synonyms), split(broader)))

    private fun verb(canonical: String, synonyms: String = "", broader: String = "") =
        register(verbs, TechTerm(canonical, WordNetLexicon.Pos.Verb, split(synonyms), split(broader)))

    private fun split(value: String) = value.split(',').map(String::trim).filter(String::isNotEmpty)

    private fun register(map: HashMap<String, TechTerm>, term: TechTerm) {
        map.getOrPut(term.canonical) { term }
        term.synonyms.forEach { map.getOrPut(it) { term } }
    }

    /** The overlay entry for a lemma (or multi-word lemma) as [pos], if any. */
    fun lookup(lemma: String, pos: WordNetLexicon.Pos): TechTerm? = when (pos) {
        WordNetLexicon.Pos.Noun -> nouns[lemma]
        WordNetLexicon.Pos.Verb -> verbs[lemma]
        else -> null
    }

    fun knowsVerb(lemma: String): Boolean = lemma in verbs

    /** Unambiguous expansions only; an acronym with several common meanings is left out. */
    val acronyms: Map<String, String> = mapOf(
        "api" to "application programming interface", "ci" to "continuous integration",
        "cd" to "continuous delivery", "pr" to "pull request", "mr" to "merge request", "ui" to "user interface",
        "ux" to "user experience", "url" to "url", "http" to "http", "json" to "json", "sql" to "sql",
        "db" to "database", "orm" to "object-relational mapping", "sdk" to "software development kit",
        "jvm" to "java virtual machine", "jdk" to "java development kit", "ide" to "integrated development environment",
        "cli" to "command-line interface", "oom" to "out of memory", "npe" to "null pointer exception",
        "llm" to "large language model", "gpu" to "graphics processing unit", "cpu" to "central processing unit",
        "ram" to "random-access memory", "os" to "operating system", "ssh" to "secure shell",
        "tls" to "transport layer security", "dns" to "domain name system", "cdn" to "content delivery network",
        "vm" to "virtual machine", "e2e" to "end-to-end test", "qa" to "quality assurance",
        "tdd" to "test-driven development", "wip" to "work in progress", "adr" to "architecture decision record",
        "rfc" to "request for comments", "cve" to "common vulnerabilities and exposures", "jwt" to "json web token",
        "cors" to "cross-origin resource sharing", "csrf" to "cross-site request forgery", "xss" to "cross-site scripting",
        "rpc" to "remote procedure call", "kmp" to "kotlin multiplatform", "agp" to "android gradle plugin",
        "apk" to "android package", "aab" to "android app bundle", "ndk" to "native development kit",
        "wasm" to "webassembly", "dom" to "document object model", "spa" to "single-page application",
        "ssr" to "server-side rendering", "pwa" to "progressive web app", "lsp" to "language server protocol",
        "ast" to "abstract syntax tree", "regex" to "regular expression", "repo" to "repository",
        "env" to "environment", "config" to "configuration", "deps" to "dependencies", "dep" to "dependency",
        "auth" to "authentication", "perf" to "performance", "impl" to "implementation", "lib" to "library",
        "pkg" to "package", "msg" to "message", "dir" to "directory", "src" to "source", "ws" to "websocket",
    )

    /** File extension -> language or format (subset of GitHub Linguist's languages.yml, MIT). */
    val extensions: Map<String, String> = mapOf(
        "kt" to "kotlin", "kts" to "kotlin script", "java" to "java", "scala" to "scala", "groovy" to "groovy",
        "py" to "python", "ipynb" to "jupyter notebook", "rb" to "ruby", "go" to "go", "rs" to "rust",
        "c" to "c", "h" to "c header", "cc" to "c++", "cpp" to "c++", "hpp" to "c++ header", "m" to "objective-c",
        "swift" to "swift", "cs" to "c#", "fs" to "f#", "js" to "javascript", "mjs" to "javascript",
        "cjs" to "javascript", "jsx" to "javascript", "ts" to "typescript", "tsx" to "typescript", "vue" to "vue",
        "svelte" to "svelte", "php" to "php", "dart" to "dart", "lua" to "lua", "r" to "r", "jl" to "julia",
        "sh" to "shell script", "bash" to "shell script", "zsh" to "shell script", "ps1" to "powershell",
        "sql" to "sql", "sq" to "sqldelight", "html" to "html", "htm" to "html", "css" to "css", "scss" to "scss",
        "md" to "markdown", "mdx" to "markdown", "rst" to "restructuredtext", "txt" to "text",
        "json" to "json", "jsonc" to "json", "jsonl" to "json lines", "yaml" to "yaml", "yml" to "yaml",
        "toml" to "toml", "xml" to "xml", "gradle" to "gradle", "properties" to "properties file",
        "proto" to "protocol buffers", "graphql" to "graphql", "dockerfile" to "dockerfile", "tf" to "terraform",
        "gz" to "gzip archive", "zip" to "zip archive", "jar" to "jar", "aar" to "android archive",
        "apk" to "android package", "onnx" to "onnx model", "png" to "image", "jpg" to "image", "svg" to "svg",
        "wasm" to "webassembly",
    )

    /** Phrasal verbs WordNet lacks; WordNet's own (set up, roll back, check out...) are found there. */
    val phrasalVerbs: Set<String> = setOf(
        "spin up", "spin down", "tear down", "roll out", "wire up", "hook up", "opt in", "opt out",
        "fall back", "factor out", "scale up", "scale out", "scale down", "sync up", "boot up", "start up",
        "log in", "log out", "sign in", "sign out", "sign up", "back up", "clean up", "bump up", "plug in",
        "check in", "pull in", "merge in", "stub out", "mock out", "comment out", "gate on", "fan out",
    )

    init {
        // Source control
        noun("commit", "changeset", "change set")
        noun("branch", broader = "source control")
        noun("pull request", "merge request, pr, mr", "code review")
        noun("repository", "repo", "source control")
        noun("merge conflict", broader = "source control")
        noun("rebase", broader = "source control")
        noun("diff", "patch", "change")
        noun("tag", broader = "source control")
        verb("commit", broader = "record")
        verb("push", broader = "upload")
        verb("pull", "fetch", "download")
        verb("merge", broader = "combine")
        verb("rebase", broader = "rewrite history")
        verb("cherry-pick", broader = "copy")
        verb("checkout", "check out, switch", "select")
        verb("revert", "roll back", "undo")
        verb("squash", broader = "combine")
        verb("stash", broader = "store")
        verb("clone", broader = "copy")
        verb("fork", broader = "copy")
        // Defects and failures
        noun("bug", "defect, fault, glitch", "software defect")
        noun("crash", broader = "failure")
        noun("regression", broader = "software defect")
        noun("exception", broader = "error")
        noun("error", broader = "failure")
        noun("stack trace", "stacktrace, traceback, backtrace", "diagnostic")
        noun("flake", "flaky test", "test failure")
        noun("timeout", broader = "failure")
        noun("memory leak", "leak", "software defect")
        noun("race condition", "race", "software defect")
        noun("deadlock", broader = "software defect")
        verb("crash", broader = "fail")
        verb("fail", broader = "break")
        verb("break", broader = "fail")
        verb("fix", "repair, patch, resolve", "change")
        verb("debug", "troubleshoot", "investigate")
        verb("reproduce", "repro", "investigate")
        verb("hang", "freeze, stall", "fail")
        verb("leak", broader = "fail")
        verb("throw", "raise", "signal")
        // Build and test
        noun("build", broader = "software process")
        noun("compiler", broader = "build tool")
        noun("gradle", broader = "build tool")
        noun("maven", broader = "build tool")
        noun("webpack", broader = "build tool")
        noun("vite", broader = "build tool")
        noun("cargo", broader = "build tool")
        noun("test", "unit test, test case", "verification")
        noun("test suite", "suite", "verification")
        noun("assertion", "assert", "test")
        noun("junit", broader = "test framework")
        noun("pytest", broader = "test framework")
        noun("jest", broader = "test framework")
        noun("vitest", broader = "test framework")
        noun("espresso", broader = "test framework")
        noun("coverage", "code coverage, test coverage", "metric")
        noun("lint", "linter", "static analysis")
        noun("benchmark", broader = "measurement")
        verb("build", "compile, assemble", "create")
        verb("compile", "build", "translate")
        verb("test", "verify, check", "examine")
        verb("lint", broader = "check")
        verb("benchmark", "measure, profile", "measure")
        verb("profile", broader = "measure")
        verb("mock", "stub, fake", "simulate")
        // CI and delivery
        noun("pipeline", "workflow", "automation")
        noun("workflow", broader = "automation")
        noun("continuous integration", "ci", "automation")
        noun("continuous delivery", "cd, continuous deployment", "automation")
        noun("github actions", "actions", "continuous integration")
        noun("check run", "check, status check", "continuous integration")
        noun("artifact", "build artifact", "output")
        noun("release", broader = "software version")
        noun("deployment", "deploy", "release")
        noun("rollout", broader = "deployment")
        noun("environment", "env", "configuration")
        noun("staging", broader = "environment")
        noun("production", "prod", "environment")
        verb("deploy", "ship, roll out", "release")
        verb("release", "publish, ship", "distribute")
        verb("publish", broader = "distribute")
        verb("rerun", "retry, re-run", "run")
        verb("trigger", broader = "start")
        // Code structure
        noun("function", "method, routine", "code element")
        noun("class", broader = "type")
        noun("interface", broader = "type")
        noun("module", broader = "code unit")
        noun("package", broader = "code unit")
        noun("library", "lib", "dependency")
        noun("dependency", "dep", "component")
        noun("framework", broader = "library")
        noun("plugin", "plug-in, extension, add-on", "component")
        noun("endpoint", "route", "api")
        noun("api", "application programming interface", "interface")
        noun("schema", broader = "structure")
        noun("migration", "schema migration", "change")
        noun("query", broader = "request")
        noun("database", "db, datastore, data store", "storage")
        noun("cache", broader = "storage")
        noun("index", broader = "data structure")
        noun("table", broader = "data structure")
        noun("field", "property, attribute", "data element")
        noun("variable", "var", "code element")
        noun("parameter", "param, argument, arg", "input")
        noun("config", "configuration, settings", "setup")
        noun("flag", "feature flag, toggle", "configuration")
        noun("token", "access token", "credential")
        noun("secret", "api key, credential", "credential")
        noun("key", "signing key", "credential")
        noun("certificate", "cert", "credential")
        noun("permission", "scope, grant", "access control")
        noun("thread", broader = "concurrency")
        noun("coroutine", broader = "concurrency")
        noun("process", broader = "program")
        noun("request", broader = "message")
        noun("response", "reply", "message")
        noun("payload", "body", "data")
        noun("log", "logs, logging", "record")
        noun("screen", "view, page", "user interface")
        noun("component", "widget", "user interface")
        noun("layout", broader = "user interface")
        noun("model", broader = "machine learning")
        noun("adapter", "lora adapter", "model")
        noun("prompt", broader = "input")
        noun("agent", broader = "program")
        noun("issue", "ticket", "work item")
        noun("todo", broader = "work item")
        // Code change verbs
        verb("refactor", "restructure", "change")
        verb("rename", broader = "change")
        verb("implement", broader = "create")
        verb("add", "introduce", "change")
        verb("remove", "delete, drop", "change")
        verb("delete", "remove, erase", "change")
        verb("update", "change, modify", "change")
        verb("upgrade", "bump", "update")
        verb("downgrade", broader = "update")
        verb("migrate", "port", "move")
        verb("configure", "set up, setup", "set")
        verb("install", broader = "set up")
        verb("uninstall", broader = "remove")
        verb("enable", "turn on, switch on", "change")
        verb("disable", "turn off, switch off", "change")
        verb("parse", broader = "analyze")
        verb("serialize", "encode, marshal", "convert")
        verb("deserialize", "decode, unmarshal", "convert")
        verb("cache", broader = "store")
        verb("persist", "save, store", "store")
        verb("fetch", "retrieve, download", "get")
        verb("load", broader = "read")
        verb("query", broader = "request")
        verb("validate", "check, verify", "check")
        verb("authenticate", "log in, sign in", "verify")
        verb("authorize", broader = "permit")
        verb("sign", broader = "authenticate")
        verb("encrypt", broader = "encode")
        verb("decrypt", broader = "decode")
        verb("log", broader = "record")
        verb("render", "draw", "display")
        verb("inline", broader = "refactor")
        verb("extract", broader = "refactor")
        verb("train", broader = "teach")
        verb("quantize", broader = "compress")
        verb("export", broader = "convert")
        verb("import", broader = "load")
        verb("run", "execute", "operate")
        verb("execute", "run", "operate")
        verb("spawn", "launch, start", "start")
        verb("kill", "terminate, stop", "stop")
        verb("restart", "reboot", "start")
        verb("review", broader = "examine")
        verb("approve", broader = "accept")
        verb("reject", "decline", "refuse")
        verb("document", broader = "write")
        verb("investigate", broader = "examine")
        verb("dedupe", "deduplicate", "remove")
    }
}
