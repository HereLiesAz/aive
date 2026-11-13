package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.RoleDefinition
import kotlinx.coroutines.CancellationException

/**
 * The keeper's adopted template revisions on this device, as the catalog resolves them (docs/Step-Templates.md). The palette
 * (Add creature), the planner and a launch read the catalog here, so a step or a role is made from the generation the keeper
 * selected, and from the built-in when none was or when the store cannot be read.
 */
object DeviceTemplates {
    /** The store; replaced in tests. Null reads the one on this device. */
    internal var store: TemplateStore? = null

    private val device: TemplateStore? by lazy { runCatching { SettingsTemplateStore() }.getOrNull() }

    /** The catalog of the built-in templates and the keeper's revisions; the built-ins alone when the store cannot be read. */
    suspend fun catalog(): TemplateCatalog =
        try {
            TemplateCatalog(BuiltInTemplates.bases(), (store ?: device)?.revisions().orEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TemplateCatalog(BuiltInTemplates.bases())
        }

    suspend fun revisions(): List<TemplateRevision> =
        try {
            (store ?: device)?.revisions().orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
}

/**
 * The roles a run is made with, as the keeper's selected template generations resolve them. Where a swarm is planned or
 * launched, the roles are passed through here, so a role with an adopted revision is used as that generation and every
 * other role, and any failure to read the store, as it is. Nothing is written back to the roles.
 */
object LaunchRoles {
    suspend fun resolve(roles: List<RoleDefinition>): List<RoleDefinition> =
        RoleTemplates.resolveAll(roles, DeviceTemplates.revisions())
}
