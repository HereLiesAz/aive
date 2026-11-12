# The Aive command-line interface

The `aive` executable is a separate **terminal client** of The Aive. It uses the existing authenticated desktop control API and MCP tool definitions without loading Compose or duplicating workflow execution. This initial release requires a running desktop app with the control API enabled. It does not yet run a standalone workflow engine.

## Install and connect

Build using JDK 21:

```sh
./gradlew :cliApp:test :cliApp:installDist
# cliApp/build/install/aive/bin/aive
```

Run The Aive desktop app with `AIVE_CONTROL_API=on`. The CLI discovers the loopback URL and token file from `~/.aive/control/control.json`. The existing bridge also supports `AIVE_CONTROL_URL`, `AIVE_CONTROL_TOKEN_FILE`, and `AIVE_CONTROL_TOKEN`; do not include bearer tokens in command arguments or committed files. Addresses outside loopback are refused. See [MCP.md](MCP.md) for the transport and authentication contract.

## Commands

```sh
aive --help
aive status
aive providers
aive settings
aive approvals
aive hives list
aive hives get <hive-id>
aive hives draft --prompt "Plan an article"
aive hives create --file hive.json
aive hives update <hive-id> --name "New name"
aive hives delete <hive-id> --yes
aive creatures add <hive-id> --name "Writer" --role-id researcher
aive creatures update <hive-id> <creature-id> --objective "Rewrite section 2"
aive creatures remove <hive-id> <creature-id> --yes
aive roles list
aive roles get <role-id>
aive roles provider <role-id> --provider-id claude
aive roles provider <role-id> --provider-id null
aive runs list --hive <hive-id> --limit 20
aive runs get <run-id>
aive runs events <run-id> --after 0 --limit 50
aive runs start --hive-id <hive-id> --objective "Prepare release notes"
aive runs stop <run-id> --yes
```

`aive <command> --help` shows options and required fields. Positional identifiers are required as shown. Options use `--kebab-case value`, matching the control API's camelCase fields. Arrays take JSON (`--depends-on '["research"]'`); nested objects are easiest with a JSON file passed to `--file path`, or `--file -` for stdin:

```json
{
  "name": "Article review",
  "creatures": [
    {"id": "draft", "name": "Draft article", "roleId": "researcher"},
    {"id": "check", "name": "Review", "roleId": "code-reviewer", "dependsOn": ["draft"]}
  ]
}
```

Role IDs are discovered with `aive roles list`. The control API decides which roles and creature kinds are permissible. All launch, scheduling, provider-failover and human gates remain in The Aive. **Approvals are read-only through CLI and MCP.** There is no direct credential-read or approval-bypass command.

Successful responses print the unwrapped control API result as pretty JSON; `--json` prints compact JSON for scripts. Errors print to stderr. Exit codes are `0` success, `1` API refusal, `2` invalid input, and `3` unavailable service or transport error. Destructive commands require `--yes` without prompting.

## Contract and tests

CLI commands bind to the existing [MCP tool definitions](../mcpBridge/src/main/kotlin/com/hereliesaz/geministrator/mcpbridge/Tools.kt), which correspond one-to-one to [ControlApi routes](../shared/src/commonMain/kotlin/com/hereliesaz/geministrator/control/ControlApi.kt). Tests verify route coverage, path and JSON encoding, user confirmation, approval access and error handling:

```sh
./gradlew :cliApp:test
```

The CLI inherits the control API trust boundary. See [THREAT_MODEL.md](THREAT_MODEL.md) and [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md).
