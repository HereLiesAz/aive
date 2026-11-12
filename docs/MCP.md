# MCP server (external agents using The Aive)

External agents can read and steer a running The Aive desktop app through the Model Context Protocol. It is two parts:

- **The control API** (in the app): a small JSON API on `127.0.0.1`, off by default, protected by a per-install bearer token. Code: `shared/.../control/` (`ControlApi`, `RuntimeControlHost`) and `desktopApp/.../DesktopControlApi.kt`.
- **The bridge** (`mcpBridge`, a separate Gradle module and process): an MCP server over stdio that turns tool calls and resource reads into control API calls. It shares no code with the app and adds no dependency beyond kotlinx.serialization.

This is separate from [A2A](A2A.md), which lets agents hand The Aive a workflow to run. MCP is for reading and authoring: it can look at everything, edit hives and start or stop a run, and it can never approve anything.

## Turn it on

1. Start The Aive with the control API on. It is off unless asked:

~~~bash
AIVE_CONTROL_API=on ./the-aive        # or set the preference com/hereliesaz/haive/control-api enabled=true
~~~

   `AIVE_CONTROL_PORT` picks another port (1024 to 65535; default 41242). With the API on, the app writes two files in `~/.aive/control/` (folder mode 0700, files 0600):

   - `control-token`: the bearer token, made once per install (32 random bytes, base64url). Delete it and restart the app to replace the token.
   - `control.json`: the address and the path of the token file. It never holds the token.

2. Build and run the bridge. It finds the app by itself from `control.json`:

~~~bash
./gradlew :mcpBridge:installDist
# bridge launcher: mcpBridge/build/install/aive-mcp/bin/aive-mcp
~~~

3. Add it to your MCP client. Claude Desktop (`claude_desktop_config.json`) or Claude Code (`.mcp.json`):

~~~json
{
  "mcpServers": {
    "aive": {
      "command": "/path/to/aive/mcpBridge/build/install/aive-mcp/bin/aive-mcp"
    }
  }
}
~~~

   Nothing else is needed on the same computer. To point the bridge somewhere else, set environment variables on that entry (the values here are placeholders; use your own path and never paste a real token into a shared file):

~~~json
{
  "mcpServers": {
    "aive": {
      "command": "/path/to/aive/mcpBridge/build/install/aive-mcp/bin/aive-mcp",
      "env": {
        "AIVE_CONTROL_URL": "http://127.0.0.1:41242",
        "AIVE_CONTROL_TOKEN_FILE": "/path/to/your/.aive/control/control-token"
      }
    }
  }
}
~~~

   `AIVE_CONTROL_TOKEN` (the token itself) is also read if set, for setups where a file is awkward. The bridge refuses any address that is not on this computer, so it can never send the token elsewhere.

## The Aive CLI

The companion `aive` terminal executable calls this same authenticated control API, reusing the bridge's route definitions and approval restrictions. It is installed separately with `./gradlew :cliApp:installDist`. See [CLI.md](CLI.md) for commands and script examples.

## What is exposed

The words are the app's: a **hive** is a workflow, a **creature** is one of its steps, a **role** is the kind of agent a creature is.

### Read

| Tool | Resource | What it gives |
|---|---|---|
| `aive_status` | `aive://status` | version, readiness, counts of hives, runs, live runs, pending approvals |
| `aive_list_hives`, `aive_get_hive` | `aive://hives`, `aive://hives/{id}` | hives; one hive with every creature (kind, role, dependencies, approval) |
| `aive_list_roles`, `aive_get_role` | `aive://roles`, `aive://roles/{id}` | roles, their authorities and preferred provider; instructions on the single role |
| `aive_list_runs`, `aive_get_run` | `aive://runs`, `aive://runs/{id}` | runs; one run with each step: status, attempt, provider, providers it failed over from, progress, blocking reason, artifacts |
| `aive_list_events` | `aive://runs/{id}/events` | the run's log, in order, paged with `after` |
| `aive_list_approvals` | `aive://approvals` | what waits on a person. **Read-only.** |
| `aive_list_providers` | `aive://providers` | each provider, whether it is connected, what it can do, and recent failover notes |
| `aive_get_settings` | `aive://settings` | settings that hold no key |

### Author hives

| Tool | |
|---|---|
| `aive_draft_hive` | Asks the linked planner (the same path as **New hive › Draft**) to propose creatures. Saves nothing, runs nothing. |
| `aive_create_hive` | Saves a hive of up to 24 creatures. Not run. |
| `aive_update_hive` | Renames a hive or changes its description. |
| `aive_add_creature`, `aive_update_creature` | Add a creature; change its name, objective, role, dependencies, criteria or label. |
| `aive_set_role_provider` | Sets a role's `preferredProviderId` (or `null` to leave the choice to the app), with the same save as the provider picker in the Roster. Only to a linked provider. |
| `aive_delete_hive` | **Destructive.** Deletes a hive; its past runs stay. |
| `aive_remove_creature` | **Destructive.** Removes an agent creature and the strands touching it. |

Rules, each answered with a typed error:

- A creature is an **agent** (a switched-on role that runs an agent) or an **approval** gate. Scripts, deployments, GitHub actions, nested workflows and repository operations cannot be made here, and a field that would carry an executor is refused.
- A role whose capabilities include repository write, pull-request creation or shell execution cannot be used for a creature here (it is refused with a policy error); those hives are made in the app. Edits cannot move a creature out from behind a person's approval: changing the dependencies of a creature behind one, or removing a creature that links it to one, is refused, and an approval gate can be made to wait on more but not on less.
- Every save is checked by the workflow validator. A repository change already in a hive keeps its human-approval ancestor (invariant 4).
- A run started over this interface treats its objective as external content (context taint): a step that can change something waits at a gate for a person.
- A human approval can be turned on, never off. A gate, or a creature that waits for a person, cannot be removed here.
- Only hives made in the app (or here) can be changed or deleted. Not while a run of the hive is live; not one that came from a package or is used as a circuit by another hive.

### Run control

| Tool | |
|---|---|
| `aive_start_run` | Starts a run of a saved hive with an objective, by the same launch path as the app. Refused while another run is unfinished. The run waits at every gate for the person at the app. |
| `aive_stop_run` | **Destructive.** Stops the active run, cancelling every unfinished creature and provider session. |
| `aive_get_run`, `aive_list_providers` | Status and failover: each step names its provider and the providers it moved on from; the provider list carries the app's failover notes. |

Pause and resume are not offered: the app has no pause for a run other than the Hall Monitor's and the spend stop, which are decisions for the keeper.

## Approvals

**Approvals are never resolved over MCP.** `aive_list_approvals` and `aive://approvals` show what waits (gates, and creatures waiting at an approval); approving or rejecting happens in the app, by the person. There is no tool, no resource and no control API route that decides a gate, lifts a spend stop, or reads a credential. Four tests hold that line:

- `ControlApiTest`: the route table has one approval-named route, a read; approve, reject and decision paths answer 404 or 405.
- `RuntimeControlHostTest`: every route and method is called with a real pending gate in place, and the gate stays pending; mutating repository operations keep their human-approval ancestor.
- `ControlApprovalBoundaryTest`: the control sources contain no call that decides a gate, and the host interface has no such member.
- `BridgeAgainstControlApiTest`: the bridge's tools map one-to-one onto the routes, and none decides an approval.

## Errors

A tool failure is a result with `isError: true` and a body of `{"error": "<code>", "message": "..."}`. Codes: `unauthorized`, `forbidden`, `policy_denied`, `not_found`, `method_not_allowed`, `invalid_argument`, `conflict`, `too_large`, `unavailable` (the app is not ready, or no planner is linked), `internal`, and from the bridge `app_unreachable`.

## Safety

- Loopback only. A caller that is not loopback, a request with an `Origin` header, and a `Host` that is not `127.0.0.1`, `localhost` or `[::1]` are refused. No CORS headers are sent.
- The token is compared in constant time, never logged, never shown, never in a result. Every answer is scrubbed of the token and of common key shapes. Strings in answers are cut at 4,000 characters; bodies are capped at 256 KB.
- No API key or credential is read by the control API. Provider listings carry the kind of failure, never a provider's message.
- Threat model: [THREAT_MODEL.md](THREAT_MODEL.md), Control API and MCP bridge; invariant 8.

## Not done yet

- Streamable HTTP transport for the bridge (stdio only).
- A switch in the Keeper's room (the control API is turned on by environment variable or preference) and **New token** there.
- Starting a run from a free-form objective with no saved hive (use A2A, or create a hive and start it).
- Creating or editing roles, scripts and repository operations; pause and resume.

## Tests

- `shared/src/commonTest/.../control/ControlApiTest.kt`: auth, loopback, Host, Origin, size, redaction, route table.
- `shared/src/commonTest/.../control/RuntimeControlHostTest.kt`: authoring rules, invariant 4, live-run guards, approvals untouched.
- `shared/src/desktopTest/.../ControlApprovalBoundaryTest.kt`: source tripwire.
- `mcpBridge/src/test/.../BridgeAgainstControlApiTest.kt`: drives the bridge, over real loopback HTTP, against the control API with a fake app.
