package com.hereliesaz.geministrator

/**
 * Makes a child process's environment safe for Git: drops every inherited `GIT_*` variable (a
 * stray `GIT_DIR`, `GIT_INDEX_FILE` or `GIT_WORK_TREE` would point a command at another repository)
 * and the credential-prompt helpers, then forbids interactive prompts so a command fails instead of
 * waiting on a terminal nobody sees. Shared by every desktop component that runs `git`, or a tool
 * that runs it (`entire`).
 */
internal fun sanitizeGitEnvironment(environment: MutableMap<String, String>) {
    environment.keys
        .filter { key ->
            key.startsWith("GIT_", ignoreCase = true) ||
                key.equals("SSH_ASKPASS", ignoreCase = true) ||
                key.equals("GCM_INTERACTIVE", ignoreCase = true)
        }
        .toList()
        .forEach(environment::remove)
    environment["GIT_TERMINAL_PROMPT"] = "0"
    environment["GCM_INTERACTIVE"] = "Never"
}

internal fun sanitizeGitEnvironment(builder: ProcessBuilder) = sanitizeGitEnvironment(builder.environment())
