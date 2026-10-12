package com.hereliesaz.conveyance

/** Which kind of design observation a finding is about. */
enum class Audit {
    IdleWorker,
    DeadEnd,
    HeroOfTheHill,
}

enum class Severity { Error, Warning }

data class RuleGuide(
    val docs: String,
)

data class Finding(
    val audit: Audit,
    val severity: Severity,
    val where: String,
    val because: String,
    val instead: String,
    val guide: RuleGuide,
) {
    override fun toString(): String = buildString {
        append("[$severity] $audit at $where\n")
        append("Found: $because\n")
        append("Try: $instead\n")
        append("Examples, ideas, and opt-out: ${guide.docs}")
    }
}

/**
 * The verification layer.
 *
 * Conscience reasons about Acts, elements, gates, and their relationships. Employment remains the
 * answer to "why is this element here?"; Act emphasis remains the answer to "how much functional
 * prominence does this Act deserve?" Neither is derived from the other.
 */
object Conscience {

    val employmentGuide = RuleGuide(
        docs = "https://github.com/HereLiesAz/Conveyance/blob/main/docs/RULES-AND-OPTOUTS.md#employment",
    )

    val gateGuide = RuleGuide(
        docs = "https://github.com/HereLiesAz/Conveyance/blob/main/docs/RULES-AND-OPTOUTS.md#gates",
    )

    val emphasisGuide = RuleGuide(
        docs = "https://github.com/HereLiesAz/Conveyance/blob/main/docs/RULES-AND-OPTOUTS.md#act-emphasis",
    )

    fun audit(product: Product): List<Finding> = product.surfaces.flatMap { audit(it) }

    fun audit(surface: Surface): List<Finding> = buildList {
        addAll(deadEnds(surface))
    }

    fun audit(frame: AuditFrame): List<Finding> = buildList {
        addAll(idleWorkers(frame))
        addAll(heroOfTheHill(frame))
        addAll(deadEnds(frame))
    }

    private fun idleWorkers(frame: AuditFrame): List<Finding> {
        val underEmployed = frame.elements.filter { !it.ambient && it.jobs.size < 4 }
        if (underEmployed.isEmpty()) return emptyList()

        val suggestions = ConsolidationAdvisor.suggest(frame)
        val covered = suggestions.flatMapTo(mutableSetOf()) { it.elements }

        val findings = suggestions.map { suggestion ->
            val because = suggestion.elements.joinToString(
                prefix = "related under-employed elements ",
                separator = ", ",
            ) { id ->
                val element = frame.elements.first { it.id == id }
                "${id.value} (${element.jobs.size})"
            }

            Finding(
                audit = Audit.IdleWorker,
                severity = Severity.Warning,
                where = frame.surface,
                because = because,
                instead = suggestion.message(),
                guide = employmentGuide,
            )
        }.toMutableList()

        underEmployed.filter { it.id !in covered }.forEach { element ->
            findings += Finding(
                audit = Audit.IdleWorker,
                severity = Severity.Warning,
                where = frame.surface,
                because = "${element.id.value} is doing ${element.jobs.size} jobs",
                instead = "Reimagine it until it honestly does four jobs. Enrich interface objects.",
                guide = employmentGuide,
            )
        }

        return findings
    }

    /**
     * One screen, one hero at most.
     *
     * Heroic is the title level of the Act outline. If more than one visible Act claims that level,
     * nobody keeps the crown: every visible Act resolves one rung lower until the screen names a
     * single Heroic Act again.
     */
    private fun heroOfTheHill(frame: AuditFrame): List<Finding> {
        val heroes = frame.elements.filter {
            it.visible && it.act != null && it.emphasis == ActEmphasis.Heroic
        }
        if (heroes.size < 2) return emptyList()

        return listOf(
            Finding(
                audit = Audit.HeroOfTheHill,
                severity = Severity.Warning,
                where = frame.surface,
                because = "${heroes.size} visible Acts claim Heroic; a screen can present only one hero moment",
                instead = "Choose one Heroic Act. Until then the screen resolves every Act one level lower: Heroic→Primary, Primary→Secondary, Secondary→Tertiary, Tertiary→Supporting.",
                guide = emphasisGuide,
            ),
        )
    }

    private fun deadEnds(surface: Surface): List<Finding> {
        val present = surface.elements.map { it.id }.toSet()
        return surface.gates.filter { it.livesAt !in present }.map { gate ->
            Finding(
                audit = Audit.DeadEnd,
                severity = Severity.Warning,
                where = "${surface.name}/${gate.id}",
                because = "its declared resolver ${gate.livesAt.value} is not on this surface",
                instead = "Expose the resolver or rethink the blocker.",
                guide = gateGuide,
            )
        }
    }

    private fun deadEnds(frame: AuditFrame): List<Finding> {
        val present = frame.elements.map { it.id }.toSet()
        return frame.gateAddresses.filter { it !in present }.map { address ->
            Finding(
                audit = Audit.DeadEnd,
                severity = Severity.Warning,
                where = "${frame.surface}/${address.value}",
                because = "its declared resolver did not compose in this frame",
                instead = "Expose the resolver or rethink the blocker.",
                guide = gateGuide,
            )
        }
    }

    fun blocks(findings: List<Finding>): Boolean = findings.any { it.severity == Severity.Error }
}
