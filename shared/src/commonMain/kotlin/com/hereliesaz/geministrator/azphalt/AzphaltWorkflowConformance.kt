package com.hereliesaz.geministrator.azphalt

/** Normative structural checks from azphalt `spec/workflow.md` for kind:"workflow" packages. */
internal object AzphaltWorkflowConformance {
    private val executableSuffix = Regex(
        "\\.(?:js|mjs|cjs|jsx|ts|tsx|wasm|class|jar|dex|so|dll|dylib|exe|com|bat|cmd|sh|bash|zsh|fish|ps1|py|pyc|rb|php|pl|swift|kt|kts)$",
        RegexOption.IGNORE_CASE,
    )
    private val declarativeScreenSuffix = Regex("\\.(?:json|ya?ml)$", RegexOption.IGNORE_CASE)

    fun validate(manifest: AzphaltManifest): List<String> {
        if (manifest.kind != "workflow") return emptyList()
        val errors = mutableListOf<String>()
        val workflow = manifest.workflow
        if (workflow == null) {
            return listOf("kind:\"workflow\" requires a workflow block")
        }

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
        ).filter { (_, value) -> value != null }.forEach { (field, _) ->
            errors += "kind:\"workflow\" must not declare root field $field"
        }

        if (workflow.format.isBlank()) errors += "workflow.format must be non-empty"
        validatePayloadEntries("definitions", workflow.definitions, manifest.files, errors, required = true)
        validatePayloadEntries("fragments", workflow.fragments, manifest.files, errors, required = false)
        validateAgentEntries(workflow.agents, manifest.files, errors)
        validateDependencies(workflow.dependencies, manifest.id, errors)
        validateScreens(workflow.screens, manifest.files, errors)
        validateHostPermissions(workflow.hostPermissions, errors)

        manifest.files.keys.forEach { path ->
            if (path != "LICENSE" && executableSuffix.containsMatchIn(path)) {
                errors += "kind:\"workflow\" must not bundle executable payload: $path"
            }
        }
        return errors
    }

    private fun validatePayloadEntries(
        label: String,
        entries: List<AzphaltWorkflowPayloadEntry>,
        files: Map<String, String>,
        errors: MutableList<String>,
        required: Boolean,
    ) {
        if (required && entries.isEmpty()) errors += "workflow.$label must contain at least one entry"
        val seen = mutableSetOf<String>()
        entries.forEachIndexed { index, entry ->
            validateEntryIdentity(label, index, entry.id, entry.name, entry.description, entry.path, files, seen, errors)
        }
    }

    private fun validateAgentEntries(
        entries: List<AzphaltWorkflowAgentEntry>,
        files: Map<String, String>,
        errors: MutableList<String>,
    ) {
        val seen = mutableSetOf<String>()
        entries.forEachIndexed { index, entry ->
            validateEntryIdentity("agents", index, entry.id, entry.name, entry.description, entry.path, files, seen, errors)
        }
    }

    private fun validateEntryIdentity(
        label: String,
        index: Int,
        id: String,
        name: String?,
        description: String?,
        path: String,
        files: Map<String, String>,
        seen: MutableSet<String>,
        errors: MutableList<String>,
    ) {
        if (!isSafeId(id)) {
            errors += "workflow.$label[$index].id must be a non-empty directory-safe name"
        } else if (!seen.add(id)) {
            errors += "workflow.$label has duplicate id $id"
        }
        if (name != null && name.isBlank()) errors += "workflow.$label[$index].name must be non-empty when present"
        if (description != null && description.isBlank()) {
            errors += "workflow.$label[$index].description must be non-empty when present"
        }
        when {
            !AzphaltWorkflowPackageInstaller.isSafePackagePath(path) ->
                errors += "workflow.$label[$index].path must be a safe relative payload path"
            path !in files -> errors += "workflow.$label[$index].path is not listed in manifest.files: $path"
            executableSuffix.containsMatchIn(path) ->
                errors += "workflow.$label[$index].path must contain declarative data, not executable code: $path"
        }
    }

    private fun validateDependencies(
        dependencies: List<AzphaltWorkflowDependency>,
        packageId: String,
        errors: MutableList<String>,
    ) {
        val seen = mutableSetOf<Pair<String, String?>>()
        dependencies.forEachIndexed { index, dependency ->
            if (dependency.id.isBlank()) {
                errors += "workflow.dependencies[$index].id must be non-empty"
            } else if (dependency.id == packageId) {
                errors += "workflow.dependencies[$index] must not reference the package itself"
            }
            if (dependency.version != null && dependency.version.isBlank()) {
                errors += "workflow.dependencies[$index].version must be non-empty when present"
            }
            if (dependency.purpose != null && dependency.purpose.isBlank()) {
                errors += "workflow.dependencies[$index].purpose must be non-empty when present"
            }
            if (dependency.note != null && dependency.note.isBlank()) {
                errors += "workflow.dependencies[$index].note must be non-empty when present"
            }
            val key = dependency.id to dependency.version
            if (!seen.add(key)) {
                errors += "workflow.dependencies has duplicate id/version pair ${dependency.id}@${dependency.version.orEmpty()}"
            }
        }
    }

    private fun validateScreens(
        screens: List<AzphaltWorkflowScreenEntry>,
        files: Map<String, String>,
        errors: MutableList<String>,
    ) {
        val seen = mutableSetOf<String>()
        screens.forEachIndexed { index, screen ->
            if (!isSafeId(screen.id)) {
                errors += "workflow.screens[$index].id must be a non-empty directory-safe name"
            } else if (!seen.add(screen.id)) {
                errors += "workflow.screens has duplicate id ${screen.id}"
            }
            if (screen.name != null && screen.name.isBlank()) {
                errors += "workflow.screens[$index].name must be non-empty when present"
            }
            when {
                !AzphaltWorkflowPackageInstaller.isSafePackagePath(screen.path) ->
                    errors += "workflow.screens[$index].path must be a safe relative payload path"
                screen.path !in files -> errors += "workflow.screens[$index].path is not listed in manifest.files: ${screen.path}"
                !declarativeScreenSuffix.containsMatchIn(screen.path) ->
                    errors += "workflow.screens[$index].path must be declarative JSON/YAML: ${screen.path}"
            }
            val placements = mutableSetOf<String>()
            screen.placements.forEachIndexed { placementIndex, placement ->
                if (placement.isBlank()) {
                    errors += "workflow.screens[$index].placements[$placementIndex] must be non-empty"
                } else if (!placements.add(placement)) {
                    errors += "workflow.screens[$index].placements contains duplicate $placement"
                }
            }
        }
    }

    private fun validateHostPermissions(permissions: List<String>, errors: MutableList<String>) {
        val seen = mutableSetOf<String>()
        permissions.forEachIndexed { index, permission ->
            if (permission.isBlank()) {
                errors += "workflow.hostPermissions[$index] must be a non-empty string"
            } else if (!seen.add(permission)) {
                errors += "workflow.hostPermissions contains duplicate $permission"
            }
        }
    }

    private fun isSafeId(id: String): Boolean =
        id.isNotBlank() && id.none { it.isWhitespace() || it == '/' || it == '\\' }
}
