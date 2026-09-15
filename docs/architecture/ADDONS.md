# Workflow Add-Ons Architecture

Haive supports azphalt `kind:"workflow"` add-ons to allow extending the platform with workflow packages and agents without compromising security or memory boundaries.

## NEVER EXPOSE

The following internal platform capabilities MUST NEVER be exposed to the add-on layer:
- The Memory layer (`MemoryMicroAgents`, `MemoryMicroAgentDeployment`, embeddings, etc.)
- Provider credentials and platform credentials
- Raw filesystem paths
- Internal persistence implementations
- Raw `AgentProvider` or `TaskExecutorIntegration` instances

## Mediated Contracts

All operations exposed to add-ons are strictly mediated. Add-ons request operations symbolically (e.g. via symbolic UI actions) and Haive interprets and executes these if the add-on has the granted permissions (`HostPermission`).

## Native Declarative Screens

Add-ons can provide declarative UI structures (e.g. `AddonScreen`) which are natively rendered by Haive in Compose. Add-ons never load raw HTML/JS or custom bytecode into the host.
