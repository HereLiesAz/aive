package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.russhwolf.settings.Settings

internal interface TerrariumLayoutStore {
    fun load(definitionId: WorkflowDefinitionId): Map<String, H2g2TerrariumPosition>
    fun put(definitionId: WorkflowDefinitionId, subjectId: String, position: H2g2TerrariumPosition)
    fun clear(definitionId: WorkflowDefinitionId)
}

/**
 * Tiny presentation-state store kept separate from workflow truth. Coordinates are normalized so a
 * layout survives browser/device/window-size changes. Workflow definitions never acquire pixel UI
 * metadata, and moving a creature never mutates execution semantics by itself.
 */
internal class SettingsTerrariumLayoutStore(
    private val settings: Settings,
    private val rootKey: String = DEFAULT_ROOT_KEY,
) : TerrariumLayoutStore {

    override fun load(definitionId: WorkflowDefinitionId): Map<String, H2g2TerrariumPosition> {
        val prefix = definitionPrefix(definitionId)
        return settings.keys
            .asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull { key ->
                val subjectToken = key.removePrefix(prefix)
                val subjectId = subjectToken.fromSettingsKeyToken() ?: return@mapNotNull null
                val encoded = settings.getStringOrNull(key) ?: return@mapNotNull null
                val pieces = encoded.split(',')
                if (pieces.size != 2) return@mapNotNull null
                val x = pieces[0].toFloatOrNull() ?: return@mapNotNull null
                val y = pieces[1].toFloatOrNull() ?: return@mapNotNull null
                subjectId to H2g2TerrariumPosition(x, y).clamped()
            }
            .toMap(linkedMapOf())
    }

    override fun put(
        definitionId: WorkflowDefinitionId,
        subjectId: String,
        position: H2g2TerrariumPosition,
    ) {
        val clamped = position.clamped()
        settings.putString(
            key(definitionId, subjectId),
            "${clamped.x},${clamped.y}",
        )
    }

    override fun clear(definitionId: WorkflowDefinitionId) {
        val prefix = definitionPrefix(definitionId)
        settings.keys.filter { it.startsWith(prefix) }.forEach(settings::remove)
    }

    private fun definitionPrefix(definitionId: WorkflowDefinitionId): String =
        "$rootKey.${definitionId.value.toSettingsKeyToken()}."

    private fun key(definitionId: WorkflowDefinitionId, subjectId: String): String =
        "${definitionPrefix(definitionId)}${subjectId.toSettingsKeyToken()}"

    private fun String.toSettingsKeyToken(): String = buildString(length * 4) {
        for (character in this@toSettingsKeyToken) {
            append(character.code.toString(16).padStart(4, '0'))
        }
    }

    private fun String.fromSettingsKeyToken(): String? {
        if (length % 4 != 0) return null
        return buildString(length / 4) {
            var index = 0
            while (index < this@fromSettingsKeyToken.length) {
                val code = this@fromSettingsKeyToken.substring(index, index + 4).toIntOrNull(16) ?: return null
                append(code.toChar())
                index += 4
            }
        }
    }

    companion object {
        const val DEFAULT_ROOT_KEY: String = "haive.terrarium.layout.v1"
        fun createDefault(): SettingsTerrariumLayoutStore = SettingsTerrariumLayoutStore(Settings())
    }
}
