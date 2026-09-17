package com.hereliesaz.geministrator.azphalt

/** Normative structural checks for kind:"role" packages consumed by Haive. */
internal object AzphaltRoleConformance {
    private val executableSuffix = Regex(
        "\\.(?:js|mjs|cjs|jsx|ts|tsx|wasm|class|jar|dex|so|dll|dylib|exe|com|bat|cmd|sh|bash|zsh|fish|ps1|py|pyc|rb|php|pl|swift|kt|kts)$",
        RegexOption.IGNORE_CASE,
    )

    fun validate(manifest: AzphaltManifest): List<String> {
        if (manifest.kind != "role") return emptyList()
        val errors = mutableListOf<String>()
        val role = manifest.role ?: return listOf("kind:\"role\" requires a role block")

        listOf(
            "entry" to manifest.entry,
            "runtime" to manifest.runtime,
            "capabilities" to manifest.capabilities,
            "assets" to manifest.assets,
            "contributes" to manifest.contributes,
            "app" to manifest.app,
            "mcp" to manifest.mcp,
            "pack" to manifest.pack,
            "skill" to manifest.skill,
            "script" to manifest.script,
            "composable" to manifest.composable,
            "workflow" to manifest.workflow,
        ).filter { (_, value) -> value != null }.forEach { (field, _) ->
            errors += "kind:\"role\" must not declare root field $field"
        }

        if (role.format.isBlank()) errors += "role.format must be non-empty"
        if (role.roles.isEmpty()) errors += "role.roles must contain at least one entry"
        val seen = mutableSetOf<String>()
        role.roles.forEachIndexed { index, entry ->
            if (!isSafeId(entry.id)) {
                errors += "role.roles[$index].id must be a non-empty directory-safe name"
            } else if (!seen.add(entry.id)) {
                errors += "role.roles has duplicate id ${entry.id}"
            }
            if (entry.name != null && entry.name.isBlank()) {
                errors += "role.roles[$index].name must be non-empty when present"
            }
            if (entry.description != null && entry.description.isBlank()) {
                errors += "role.roles[$index].description must be non-empty when present"
            }
            when {
                !AzphaltWorkflowPackageInstaller.isSafePackagePath(entry.path) ->
                    errors += "role.roles[$index].path must be a safe relative payload path"
                entry.path !in manifest.files ->
                    errors += "role.roles[$index].path is not listed in manifest.files: ${entry.path}"
                executableSuffix.containsMatchIn(entry.path) ->
                    errors += "role.roles[$index].path must contain declarative data, not executable code: ${entry.path}"
            }
        }

        manifest.files.keys.forEach { path ->
            if (path != "LICENSE" && executableSuffix.containsMatchIn(path)) {
                errors += "kind:\"role\" must not bundle executable payload: $path"
            }
        }
        return errors
    }

    private fun isSafeId(id: String): Boolean =
        id.isNotBlank() && id.none { it.isWhitespace() || it == '/' || it == '\\' }
}
