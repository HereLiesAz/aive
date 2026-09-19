# Repository search and swarm customization

## Connected repository selection

When GitHub or GitLab credentials are connected, the repository locator on the run setup screen is both a manual locator field and a repository search dropdown.

- Focusing the empty field lists repositories owned by the authenticated account first.
- Typing searches the connected service and keeps owned repositories ahead of other visible or public matches.
- Selecting a suggestion fills the repository URL and its default branch when available.
- Manual URL and `owner/repository` or `group/repository` entry remains supported.
- Local repositories continue to use the local folder picker on platforms that support it.

## Custom swarm roster

The Swarm screen manages the semantic orchestration roles that reason about and govern work. Deterministic system workers and repetitive programmatic executors are deliberately outside this roster.

Users can add, edit, remove, reorder, reroute, and save orchestration roles. Editable role properties include identity, description, standing instructions, provider preference, required capabilities, and authorities.

The saved collection is authoritative for new workflow construction. Starter tasks and injected test/environment-planning tasks resolve roles by authority rather than assuming a fixed built-in role ID.

Removed roles are retained internally as disabled definitions so historical or already-running workflows can still resolve the role they were created with. Resetting restores Haive's built-in role definitions and order while retaining removed custom definitions only for historical compatibility.
