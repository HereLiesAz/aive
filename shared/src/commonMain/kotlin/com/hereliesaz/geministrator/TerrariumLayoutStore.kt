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
        val result = linkedMapOf<String, H2g2TerrariumPosition>()
        // Legacy keys encoded every character as four hex digits, which overflows Preferences' 80-char key limit.
        val legacyPrefix = legacyDefinitionPrefix(definitionId)
        settings.keys.filter { it.startsWith(legacyPrefix) }.forEach { key ->
            val subjectId = key.removePrefix(legacyPrefix).fromSettingsKeyToken() ?: return@forEach
            parse(settings.getStringOrNull(key))?.let { result[subjectId] = it }
        }
        val prefix = definitionPrefix(definitionId)
        settings.keys.filter { it.startsWith(prefix) }.forEach { key ->
            // Value is "x,y,subjectId"; the key only carries a hash of the subject.
            val encoded = settings.getStringOrNull(key) ?: return@forEach
            val pieces = encoded.split(',', limit = 3)
            if (pieces.size != 3) return@forEach
            parse("${pieces[0]},${pieces[1]}")?.let { result[pieces[2]] = it }
        }
        return result
    }

    private fun parse(encoded: String?): H2g2TerrariumPosition? {
        val pieces = encoded?.split(',') ?: return null
        if (pieces.size != 2) return null
        val x = pieces[0].toFloatOrNull() ?: return null
        val y = pieces[1].toFloatOrNull() ?: return null
        return H2g2TerrariumPosition(x, y).clamped()
    }

    override fun put(
        definitionId: WorkflowDefinitionId,
        subjectId: String,
        position: H2g2TerrariumPosition,
    ) {
        val clamped = position.clamped()
        settings.remove("${legacyDefinitionPrefix(definitionId)}${subjectId.toSettingsKeyToken()}")
        settings.putString(key(definitionId, subjectId), "${clamped.x},${clamped.y},$subjectId")
    }

    override fun clear(definitionId: WorkflowDefinitionId) {
        val prefixes = listOf(definitionPrefix(definitionId), legacyDefinitionPrefix(definitionId))
        settings.keys.filter { key -> prefixes.any(key::startsWith) }.forEach(settings::remove)
    }

    private fun legacyDefinitionPrefix(definitionId: WorkflowDefinitionId): String =
        "$rootKey.${definitionId.value.toSettingsKeyToken()}."

    private fun definitionPrefix(definitionId: WorkflowDefinitionId): String =
        "$rootKey.h.${hash(definitionId.value)}."

    private fun key(definitionId: WorkflowDefinitionId, subjectId: String): String =
        "${definitionPrefix(definitionId)}${hash(subjectId)}"

    private fun hash(value: String): String = Sha256.hex(value.encodeToByteArray()).take(HASH_CHARS)

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
        private const val HASH_CHARS = 24
        const val DEFAULT_ROOT_KEY: String = "haive.terrarium.layout.v1"
        fun createDefault(): SettingsTerrariumLayoutStore = SettingsTerrariumLayoutStore(Settings())
    }
}
