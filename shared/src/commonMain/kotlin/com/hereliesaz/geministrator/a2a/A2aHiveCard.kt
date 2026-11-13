package com.hereliesaz.geministrator.a2a

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.toTaskExecutor
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.humanReadable
import com.hereliesaz.geministrator.workflow.withoutPlacement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The hive card (docs/A2A.md, "Hive cards"): a portable record of one hive, which role runs in each
 * of its creatures and on which provider, so a multi-provider setup can be shared or restored on
 * any platform, with no server. It is an A2A 1.0 Agent Card (the same [A2aCodec.encodeCard]) whose
 * skills are the creatures, plus one `capabilities.extensions` entry ([EXTENSION_URI]) that holds
 * the record itself. The card describes a hive; it serves nothing, so it lists no interface.
 *
 * It never holds a credential, token, repository path or URL, memory, or a prompt: a creature is
 * its id, name, role id, provider id, whether the provider is pinned, its strands (what it depends
 * on) and, only when the keeper asks, a one-line objective. An imported card is untrusted input:
 * [import] bounds and validates every field, builds a NEW hive from the roles and providers this
 * device already has, and runs the result through [WorkflowGraphValidator] like an authored hive.
 */
object A2aHiveCard {
    const val EXTENSION_URI = "urn:aive:a2a:ext:hive-card:v1"
    const val FORMAT = "aive-hive-card"
    const val VERSION = 1
    const val MAX_CARD_BYTES = 256 * 1024
    const val MAX_CREATURES = 12
    const val MAX_NAME_CHARS = 80
    const val MAX_OBJECTIVE_CHARS = 200
    const val MAX_ID_CHARS = 64
    const val MAX_URL_CHARS = 300
    private const val MAX_DEPTH = 16

    /** Writes the card for [definition]; [roles] name its roles, [a2aCardUrls] map `a2a:` provider ids to their card URLs. */
    fun export(
        definition: WorkflowDefinition,
        roles: Collection<RoleDefinition>,
        options: HiveCardOptions = HiveCardOptions(),
        a2aCardUrls: Map<String, String> = emptyMap(),
    ): HiveCardExport {
        if (definition.tasks.isEmpty()) return HiveCardExport.Refused(HiveCardProblem.NoCreatures)
        if (definition.tasks.size > MAX_CREATURES) return HiveCardExport.Refused(HiveCardProblem.TooManyCreatures(definition.tasks.size))
        val rolesById = roles.associateBy { it.id.value }
        val seen = HashSet<String>()
        val creatures = ArrayList<ExportedCreature>()
        for (task in definition.tasks) {
            val id = task.id.value
            if (!isSafeId(id)) return HiveCardExport.Refused(HiveCardProblem.BadField("creature id"))
            if (!seen.add(id)) return HiveCardExport.Refused(HiveCardProblem.DuplicateCreature(id))
            val executor = task.executor.withoutPlacement()
            val roleId = task.roleId?.value
            val kind = when {
                executor is TaskExecutor.HumanApproval -> KIND_APPROVAL
                roleId != null && isSafeId(roleId) -> KIND_ROLE
                else -> KIND_OTHER
            }
            val role = roleId?.let { rolesById[it] }
            val pinned = if (kind == KIND_ROLE) {
                ((task.providerConstraints as? ProviderConstraints.RequireProvider)?.providerId?.value ?: role?.preferredProviderId?.value)
                    ?.takeIf(::isSafeId)
            } else {
                null
            }
            creatures += ExportedCreature(
                id = id,
                name = a2aOneLine(task.name, MAX_NAME_CHARS).ifBlank { id },
                kind = kind,
                roleId = roleId.takeIf { kind == KIND_ROLE },
                roleName = role?.name?.let { a2aOneLine(it, MAX_NAME_CHARS) },
                provider = pinned,
                cardUrl = pinned?.let { a2aCardUrls[it] }?.let(::safeCardUrl),
                dependsOn = task.dependsOn.map { it.value }.filter(::isSafeId).sorted(),
                objective = if (options.includeObjectives) a2aOneLine(task.objective, MAX_OBJECTIVE_CHARS).ifBlank { null } else null,
            )
        }
        val knownIds = creatures.mapTo(HashSet()) { it.id }
        val name = a2aOneLine(definition.name, MAX_NAME_CHARS).ifBlank { "Swarm" }
        val params = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("hive", buildJsonObject { put("name", name) })
            put("includesObjectives", options.includeObjectives)
            put(
                "creatures",
                buildJsonArray {
                    creatures.forEach { creature ->
                        add(
                            buildJsonObject {
                                put("id", creature.id)
                                put("name", creature.name)
                                put("kind", creature.kind)
                                creature.roleId?.let { put("role", it) }
                                put("assignment", if (creature.provider != null) ASSIGNMENT_PIN else ASSIGNMENT_AUTO)
                                creature.provider?.let { put("provider", it) }
                                creature.cardUrl?.let { put("cardUrl", it) }
                                put("dependsOn", JsonArray(creature.dependsOn.filter { it in knownIds }.map(::JsonPrimitive)))
                                creature.objective?.let { put("objective", it) }
                            },
                        )
                    }
                },
            )
        }
        val card = A2aAgentCard(
            name = name,
            description = "A record of this hive's creatures and the provider each one runs on. It describes the hive; it is not an agent that can be called.",
            version = VERSION.toString(),
            interfaces = emptyList(),
            skills = creatures.map { creature ->
                A2aSkill(
                    id = "creature:${creature.id}",
                    name = creature.name,
                    description = listOfNotNull(creature.roleName ?: creature.kind.takeIf { it != KIND_ROLE }, creature.objective).joinToString(": ").ifBlank { creature.name },
                    tags = listOfNotNull(
                        "creature",
                        creature.roleId?.let { "role:$it" },
                        creature.provider?.let { "provider:" + A2aProviderCards.cardKey(it) } ?: A2aProviderCards.AUTO_TAG,
                    ),
                )
            },
            extensions = listOf(
                A2aCardExtension(
                    uri = EXTENSION_URI,
                    description = "The Aive hive record: creatures, roles, provider assignments and strands.",
                    required = false,
                    params = params,
                ),
            ),
        )
        val text = A2aCodec.encodeCard(card).toString()
        if (text.encodeToByteArray().size > MAX_CARD_BYTES) return HiveCardExport.Refused(HiveCardProblem.TooLarge)
        return HiveCardExport.Ready(text)
    }

    /**
     * Reads [text] as a hive card and builds a new hive from it. Never throws and never touches
     * anything: the caller saves [HiveCardImport.Ready.definition] only if the keeper agrees.
     */
    fun import(text: String, context: HiveCardImportContext): HiveCardImport = try {
        read(text, context)
    } catch (_: Exception) {
        HiveCardImport.Refused(HiveCardProblem.Unreadable)
    }

    private fun read(raw: String, context: HiveCardImportContext): HiveCardImport {
        if (raw.length > MAX_CARD_BYTES) return refused(HiveCardProblem.TooLarge)
        val text = raw.trim().removePrefix("﻿").trim()
        if (text.isEmpty()) return refused(HiveCardProblem.Empty)
        if (text.encodeToByteArray().size > MAX_CARD_BYTES) return refused(HiveCardProblem.TooLarge)
        if (text.first() != '{' || nestingDepth(text) > MAX_DEPTH) return refused(HiveCardProblem.NotAHiveCard)
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return refused(HiveCardProblem.NotAHiveCard)

        val extensions = (root["capabilities"] as? JsonObject)?.get("extensions") as? JsonArray
        val params = extensions?.firstNotNullOfOrNull { entry ->
            (entry as? JsonObject)?.takeIf { it.string("uri") == EXTENSION_URI }?.get("params") as? JsonObject
        } ?: return refused(HiveCardProblem.NotAHiveCard)
        if (params.string("format") != FORMAT) return refused(HiveCardProblem.NotAHiveCard)
        val version = params["version"] as? JsonPrimitive
        if (version == null || version.isString || version.content != VERSION.toString()) {
            return refused(HiveCardProblem.UnsupportedVersion(a2aOneLine(version?.content, 12)))
        }
        val entries = params["creatures"] as? JsonArray ?: return refused(HiveCardProblem.BadField("creatures"))
        if (entries.isEmpty()) return refused(HiveCardProblem.NoCreatures)
        if (entries.size > MAX_CREATURES) return refused(HiveCardProblem.TooManyCreatures(entries.size))

        val parsed = ArrayList<ImportedCreature>()
        val ids = HashSet<String>()
        for (entry in entries) {
            val item = entry as? JsonObject ?: return refused(HiveCardProblem.BadField("creature"))
            val id = item.string("id")?.takeIf(::isSafeId) ?: return refused(HiveCardProblem.BadField("creature id"))
            if (!ids.add(id)) return refused(HiveCardProblem.DuplicateCreature(id))
            val kind = item.string("kind")?.takeIf { it == KIND_ROLE || it == KIND_APPROVAL || it == KIND_OTHER }
                ?: return refused(HiveCardProblem.BadField("kind of $id"))
            val role = if (kind == KIND_ROLE) item.string("role")?.takeIf(::isSafeId) ?: return refused(HiveCardProblem.BadField("role of $id")) else null
            val assignment = item.string("assignment") ?: ASSIGNMENT_AUTO
            if (assignment != ASSIGNMENT_PIN && assignment != ASSIGNMENT_AUTO) return refused(HiveCardProblem.BadField("assignment of $id"))
            val provider = if (assignment == ASSIGNMENT_PIN) item.string("provider")?.takeIf(::isSafeId) ?: return refused(HiveCardProblem.BadField("provider of $id")) else null
            val dependsRaw = item["dependsOn"]
            val depends = when (dependsRaw) {
                null -> emptyList()
                is JsonArray -> {
                    if (dependsRaw.size > MAX_CREATURES) return refused(HiveCardProblem.BadField("strands of $id"))
                    dependsRaw.map { dep ->
                        (dep as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(::isSafeId)
                            ?: return refused(HiveCardProblem.BadField("strands of $id"))
                    }
                }
                else -> return refused(HiveCardProblem.BadField("strands of $id"))
            }
            parsed += ImportedCreature(
                id = id,
                name = a2aOneLine(item.string("name"), MAX_NAME_CHARS).ifBlank { id },
                kind = kind,
                roleId = role,
                provider = provider,
                cardUrl = item.string("cardUrl")?.let(::safeCardUrl),
                dependsOn = depends.distinct(),
                objective = a2aOneLine(item.string("objective"), MAX_OBJECTIVE_CHARS).ifBlank { null },
            )
        }
        for (creature in parsed) {
            creature.dependsOn.firstOrNull { it !in ids }?.let { return refused(HiveCardProblem.UnknownStrand(creature.id, it)) }
        }

        val rolesById = context.roles.filter { it.enabled }.associateBy { it.id.value }
        val needsRoles = LinkedHashSet<String>()
        val needsProviders = LinkedHashSet<String>()
        val drifterCards = LinkedHashSet<String>()
        val pinned = ArrayList<HiveCardPin>()
        var leftOut = 0
        val tasks = ArrayList<TaskDefinition>()
        val kept = HashSet<String>()
        for (creature in parsed) {
            when (creature.kind) {
                KIND_ROLE -> {
                    val role = rolesById[creature.roleId]
                    if (role == null) {
                        creature.roleId?.let { needsRoles += it }
                        continue
                    }
                    val provider = creature.provider
                    val constraint = when {
                        provider == null -> ProviderConstraints.None
                        provider in context.connectedProviderIds -> {
                            pinned += HiveCardPin(creature.name, provider)
                            ProviderConstraints.RequireProvider(AgentProviderId(provider))
                        }
                        else -> {
                            needsProviders += provider
                            if (provider.startsWith(A2A_PROVIDER_PREFIX)) creature.cardUrl?.let { drifterCards += it }
                            ProviderConstraints.None
                        }
                    }
                    tasks += TaskDefinition(
                        id = TaskDefinitionId(creature.id),
                        name = creature.name,
                        objective = creature.objective ?: role.description.ifBlank { role.name },
                        roleId = role.id,
                        executor = role.executionSource.toTaskExecutor(role.id),
                        providerConstraints = constraint,
                    )
                    kept += creature.id
                }
                KIND_APPROVAL -> {
                    tasks += TaskDefinition(
                        id = TaskDefinitionId(creature.id),
                        name = creature.name,
                        objective = creature.objective ?: creature.name,
                        roleId = null,
                        executor = TaskExecutor.HumanApproval(creature.name),
                    )
                    kept += creature.id
                }
                else -> leftOut++
            }
        }
        if (tasks.isEmpty()) return refused(HiveCardProblem.NothingUsable(needsRoles.toList(), leftOut))

        var strandsKept = 0
        var strandsDropped = 0
        val wired = tasks.map { task ->
            val declared = parsed.first { it.id == task.id.value }.dependsOn
            val live = declared.filter { it in kept }
            strandsKept += live.size
            strandsDropped += declared.size - live.size
            task.copy(dependsOn = live.mapTo(LinkedHashSet()) { TaskDefinitionId(it) })
        }

        val taken = context.existingHiveIds
        val hiveId = generateSequence(1) { it + 1 }.map { "hive-$it" }.first { it !in taken }
        val name = a2aOneLine((params["hive"] as? JsonObject)?.string("name"), MAX_NAME_CHARS).ifBlank { context.defaultName }
        val definition = WorkflowDefinition(id = WorkflowDefinitionId(hiveId), name = name, tasks = wired)
        val errors = WorkflowGraphValidator.validate(definition)
        if (errors.isNotEmpty()) {
            return refused(HiveCardProblem.Invalid(a2aOneLine(errors.joinToString(" ") { it.humanReadable() }, 400)))
        }
        return HiveCardImport.Ready(
            definition,
            HiveCardSummary(
                hiveName = name,
                creatures = wired.size,
                pinned = pinned,
                needsProviders = needsProviders.toList(),
                needsRoles = needsRoles.toList(),
                leftOut = leftOut,
                strandsKept = strandsKept,
                strandsDropped = strandsDropped,
                drifterCards = drifterCards.toList(),
            ),
        )
    }

    private fun refused(problem: HiveCardProblem) = HiveCardImport.Refused(problem)

    /** The deepest `[`/`{` nesting outside strings; deeper than [MAX_DEPTH] is not a card. */
    internal fun nestingDepth(text: String): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > deepest) deepest = depth
                        if (deepest > MAX_DEPTH) return deepest
                    }
                    '}', ']' -> if (depth > 0) depth--
                }
            }
        }
        return deepest
    }

    /** An id the card may carry: 1 to [MAX_ID_CHARS] of ASCII letters, digits and `. _ - :`, starting with a letter or digit. */
    internal fun isSafeId(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_ID_CHARS) return false
        for (index in value.indices) {
            val c = value[index]
            val alnum = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
            if (!alnum && !(index > 0 && (c == '.' || c == '_' || c == '-' || c == ':'))) return false
        }
        return true
    }

    /**
     * An agent card address that is safe to write on a card or show: http(s), a host, and no user
     * info, query or fragment (those can carry a credential). Anything else is left out.
     */
    internal fun safeCardUrl(url: String): String? {
        val clean = url.trim()
        if (clean.isEmpty() || clean.length > MAX_URL_CHARS) return null
        val lower = clean.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
        if (clean.any { it.isWhitespace() || it.isISOControl() || it == '?' || it == '#' || it == '\\' }) return null
        val authority = clean.substringAfter("://").substringBefore('/')
        if (authority.isEmpty() || '@' in authority) return null
        return clean
    }

    private class ExportedCreature(
        val id: String,
        val name: String,
        val kind: String,
        val roleId: String?,
        val roleName: String?,
        val provider: String?,
        val cardUrl: String?,
        val dependsOn: List<String>,
        val objective: String?,
    )

    private class ImportedCreature(
        val id: String,
        val name: String,
        val kind: String,
        val roleId: String?,
        val provider: String?,
        val cardUrl: String?,
        val dependsOn: List<String>,
        val objective: String?,
    )

    private const val KIND_ROLE = "role"
    private const val KIND_APPROVAL = "approval"
    private const val KIND_OTHER = "other"
    private const val ASSIGNMENT_PIN = "pin"
    private const val ASSIGNMENT_AUTO = "auto"
    private const val A2A_PROVIDER_PREFIX = "a2a:"
}

/** What a share includes. Default: names and roles only; an objective can say more than the keeper means to share. */
data class HiveCardOptions(val includeObjectives: Boolean = false)

/** What this device has, for an import: linked providers, the swarm's roles, and the hive ids already taken. */
data class HiveCardImportContext(
    val connectedProviderIds: Set<String>,
    val roles: Collection<RoleDefinition>,
    val existingHiveIds: Set<String>,
    val defaultName: String = "Imported swarm",
)

sealed interface HiveCardExport {
    data class Ready(val text: String) : HiveCardExport
    data class Refused(val problem: HiveCardProblem) : HiveCardExport
}

sealed interface HiveCardImport {
    /** A NEW hive (an id that is not taken) and what happened to the card's assignments. */
    data class Ready(val definition: WorkflowDefinition, val summary: HiveCardSummary) : HiveCardImport
    data class Refused(val problem: HiveCardProblem) : HiveCardImport
}

/** A creature pinned to a provider that is linked here. */
data class HiveCardPin(val creatureName: String, val providerId: String)

/**
 * What an import did, in data: the screen words it. Providers in [needsProviders] are not linked
 * here, so their creatures run on "auto"; nothing was connected or fetched. [drifterCards] are
 * agent card addresses the keeper may bring in by hand; none was read.
 */
data class HiveCardSummary(
    val hiveName: String,
    val creatures: Int,
    val pinned: List<HiveCardPin>,
    val needsProviders: List<String>,
    val needsRoles: List<String>,
    val leftOut: Int,
    val strandsKept: Int,
    val strandsDropped: Int,
    val drifterCards: List<String>,
)

sealed interface HiveCardProblem {
    data object Empty : HiveCardProblem
    data object NotAHiveCard : HiveCardProblem
    data object Unreadable : HiveCardProblem
    data object TooLarge : HiveCardProblem
    data object NoCreatures : HiveCardProblem
    data class UnsupportedVersion(val found: String) : HiveCardProblem
    data class TooManyCreatures(val count: Int) : HiveCardProblem
    data class BadField(val field: String) : HiveCardProblem
    data class DuplicateCreature(val id: String) : HiveCardProblem
    data class UnknownStrand(val creature: String, val missing: String) : HiveCardProblem
    data class NothingUsable(val needsRoles: List<String>, val leftOut: Int) : HiveCardProblem
    data class Invalid(val reason: String) : HiveCardProblem
}
