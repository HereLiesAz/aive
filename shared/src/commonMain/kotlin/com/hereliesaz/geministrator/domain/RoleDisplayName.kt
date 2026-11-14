package com.hereliesaz.geministrator.domain

/** The id of the built-in orchestrator role; the keeper knows her as the queen. Saved data and logic keep this id. */
private const val ORCHESTRATOR_ROLE_ID = "orchestrator"

/** What the keeper reads for the orchestrator's creature. Display only: the stored role id and name stay as they are. */
const val QUEEN_DISPLAY_NAME = "Queen"

/**
 * The one place a role's stored name becomes the name the keeper reads. The built-in orchestrator is the queen on every
 * screen (terrarium, creature sheet, Messages rows, labels); every other role keeps its own name. Logic that must tell
 * the orchestrator apart keeps comparing the stored id or name, never this.
 */
fun roleDisplayName(id: String?, name: String): String =
    if (id == ORCHESTRATOR_ROLE_ID || name == "Orchestrator") QUEEN_DISPLAY_NAME else name

/** [roleDisplayName] for a role definition. */
fun RoleDefinition.keeperName(): String = roleDisplayName(id.value, name)

/** [roleDisplayName] for a bare name (a role name carried without its id). */
fun roleDisplayName(name: String): String = roleDisplayName(null, name)
