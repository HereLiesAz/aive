package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.RoleDefinition
import kotlinx.coroutines.CancellationException

/**
 * The roles a run is made with, as the keeper's selected template generations resolve them (docs/Step-Templates.md).
 * Where a swarm is planned or launched, the roles are passed through here, so a role with an adopted revision is used as that
 * generation and every other role, and any failure to read the store, as it is. Nothing is written back to the roles.
 */
object LaunchRoles {
    /** The keeper's adopted revisions on this device; replaced in tests. */
    internal var store: TemplateStore? = null

    private val device: TemplateStore? by lazy { runCatching { SettingsTemplateStore() }.getOrNull() }

    suspend fun resolve(roles: List<RoleDefinition>): List<RoleDefinition> =
        try {
            val kept = store ?: device
            if (kept == null) roles else RoleTemplates.resolveAll(roles, kept.revisions())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            roles
        }
}
