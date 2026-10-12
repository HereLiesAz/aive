package com.hereliesaz.conveyance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConscienceTest {

    private val send = ElementId("invoice.send")
    private val field = ElementId("recipient.field")

    private fun element(id: ElementId) = DeclaredElement(
        id = id,
        employment = Employment.Working(Job.Invite, Job.Report, Job.Progress, Job.Interrupt),
    )

    @Test
    fun `a gate whose resolver is absent is reported`() {
        val gate = Gate("recipient", livesAt = ElementId("nowhere")) { false }
        val findings = Conscience.audit(
            Surface("s", elements = listOf(element(send)), gates = listOf(gate)),
        )
        assertEquals(1, findings.count { it.audit == Audit.DeadEnd })
    }

    @Test
    fun `a reachable gate produces no finding`() {
        val gate = Gate("recipient", livesAt = field) { true }
        val findings = Conscience.audit(
            Surface(
                "invoice",
                elements = listOf(element(send), element(field)),
                gates = listOf(gate),
                places = listOf(Place.from("invoice.detail", origin = send)),
            ),
        )
        assertTrue(findings.isEmpty(), "A coherent surface should produce nothing to read: $findings")
    }

    private fun auditElement(
        id: ElementId,
        jobs: Set<Job> = setOf(Job.Invite, Job.Report, Job.Progress, Job.Interrupt),
        ambient: Boolean = false,
        left: Float = 0f,
        top: Float = 0f,
        width: Float = 20f,
        height: Float = 20f,
        act: ActId? = null,
        emphasis: ActEmphasis? = null,
    ) = AuditElement(
        id = id,
        left = left,
        top = top,
        width = width,
        height = height,
        visible = true,
        act = act,
        jobs = jobs,
        emphasis = emphasis,
        ambient = ambient,
    )

    @Test
    fun `a live working element doing fewer than four jobs is an idle worker`() {
        val frame = AuditFrame(
            surface = "invoice",
            census = Census(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList()),
            elements = listOf(auditElement(send, jobs = setOf(Job.Invite, Job.Report))),
        )
        assertEquals(1, Conscience.audit(frame).count { it.audit == Audit.IdleWorker })
    }

    @Test
    fun `ambient explicitly opts out of the four job rule`() {
        val frame = AuditFrame(
            surface = "invoice",
            census = Census(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList()),
            elements = listOf(auditElement(send, jobs = emptySet(), ambient = true)),
        )
        assertTrue(Conscience.audit(frame).none { it.audit == Audit.IdleWorker })
    }

    @Test
    fun `related idle workers are consolidated into one surface-level recommendation`() {
        val button = auditElement(
            ElementId("invoice.button"),
            jobs = setOf(Job.Invite, Job.Interrupt),
            left = 0f,
        )
        val status = auditElement(
            ElementId("invoice.status"),
            jobs = setOf(Job.Report, Job.Progress),
            left = 24f,
        )
        val identity = auditElement(
            ElementId("invoice.identity"),
            jobs = setOf(Job.Identify, Job.Confirm),
            left = 48f,
        )
        val frame = AuditFrame(
            surface = "invoice",
            census = Census(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList()),
            elements = listOf(button, status, identity),
        )

        val findings = Conscience.audit(frame).filter { it.audit == Audit.IdleWorker }
        assertEquals(1, findings.size)
        val log = findings.single().toString()
        assertTrue(log.contains("Combine"), log)
        assertTrue(log.contains("invoice.button"), log)
        assertTrue(log.contains("invoice.status"), log)
    }

    @Test
    fun `fragmented action feedback maps directly to the SDK Offer composable`() {
        val action = auditElement(
            ElementId("save.button"),
            jobs = setOf(Job.Invite, Job.Interrupt),
            left = 0f,
        )
        val progress = auditElement(
            ElementId("save.spinner"),
            jobs = setOf(Job.Progress),
            left = 24f,
        )
        val success = auditElement(
            ElementId("save.success"),
            jobs = setOf(Job.Confirm),
            left = 48f,
        )
        val frame = AuditFrame(
            surface = "editor",
            census = Census(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList()),
            elements = listOf(action, progress, success),
        )

        val log = Conscience.audit(frame).single { it.audit == Audit.IdleWorker }.toString()
        assertTrue(log.contains("Replace"), log)
        assertTrue(log.contains("save.button"), log)
        assertTrue(log.contains("save.spinner"), log)
        assertTrue(log.contains("save.success"), log)
        assertTrue(log.contains("Conveyance Offer"), log)
    }

    @Test
    fun `two visible heroic acts trigger HeroOfTheHill`() {
        val publish = auditElement(
            id = ElementId("publish"),
            act = ActId("publish"),
            emphasis = ActEmphasis.Heroic,
        )
        val share = auditElement(
            id = ElementId("share"),
            act = ActId("share"),
            emphasis = ActEmphasis.Heroic,
            left = 24f,
        )
        val primary = auditElement(
            id = ElementId("preview"),
            act = ActId("preview"),
            emphasis = ActEmphasis.Primary,
            left = 48f,
        )

        val frame = AuditFrame(
            surface = "release",
            census = Census(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList()),
            elements = listOf(publish, share, primary),
        )

        val finding = Conscience.audit(frame).single { it.audit == Audit.HeroOfTheHill }
        val log = finding.toString()
        assertTrue(log.startsWith("[Warning] HeroOfTheHill at release"), log)
        assertTrue(log.contains("2 visible Acts claim Heroic"), log)
        assertTrue(log.contains("Heroic→Primary"), log)
        assertTrue(log.contains("Primary→Secondary"), log)
        assertTrue(log.contains("RULES-AND-OPTOUTS.md#act-emphasis"), log)
    }

    @Test
    fun `one visible heroic act owns the hill`() {
        val frame = AuditFrame(
            surface = "release",
            census = Census(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList()),
            elements = listOf(
                auditElement(
                    id = ElementId("publish"),
                    act = ActId("publish"),
                    emphasis = ActEmphasis.Heroic,
                ),
                auditElement(
                    id = ElementId("share"),
                    act = ActId("share"),
                    emphasis = ActEmphasis.Primary,
                    left = 24f,
                ),
            ),
        )

        assertTrue(Conscience.audit(frame).none { it.audit == Audit.HeroOfTheHill })
    }

    @Test
    fun `act emphasis demotes one rung when hero claims compete`() {
        assertEquals(ActEmphasis.Heroic, ActEmphasis.Heroic.resolve(heroicClaims = 1))
        assertEquals(ActEmphasis.Primary, ActEmphasis.Heroic.resolve(heroicClaims = 2))
        assertEquals(ActEmphasis.Secondary, ActEmphasis.Primary.resolve(heroicClaims = 2))
        assertEquals(ActEmphasis.Tertiary, ActEmphasis.Secondary.resolve(heroicClaims = 2))
        assertEquals(ActEmphasis.Supporting, ActEmphasis.Tertiary.resolve(heroicClaims = 2))
        assertEquals(ActEmphasis.Supporting, ActEmphasis.Supporting.resolve(heroicClaims = 2))
    }

    @Test
    fun `a live gate resolver that did not compose is reported`() {
        val frame = AuditFrame(
            surface = "invoice",
            census = Census(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList()),
            elements = listOf(auditElement(send)),
            gateAddresses = setOf(ElementId("nowhere")),
        )
        assertEquals(1, Conscience.audit(frame).count { it.audit == Audit.DeadEnd })
    }

    @Test
    fun `idle worker lint uses the compact four line format`() {
        val frame = AuditFrame(
            surface = "invoice",
            census = Census(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList()),
            elements = listOf(auditElement(send, jobs = setOf(Job.Invite, Job.Report))),
        )

        val log = Conscience.audit(frame).single { it.audit == Audit.IdleWorker }.toString()

        assertEquals(
            "[Warning] IdleWorker at invoice\n" +
                "Found: invoice.send is doing 2 jobs\n" +
                "Try: Reimagine it until it honestly does four jobs. Enrich interface objects.\n" +
                "Examples, ideas, and opt-out: https://github.com/HereLiesAz/Conveyance/blob/main/docs/RULES-AND-OPTOUTS.md#employment",
            log,
        )
    }

    @Test
    fun `gate lint stays compact and links to examples ideas and opt-out`() {
        val gate = Gate("recipient", livesAt = ElementId("nowhere")) { false }
        val log = Conscience.audit(
            Surface("s", elements = listOf(element(send)), gates = listOf(gate)),
        ).single().toString()

        assertTrue(log.contains("Found:"), log)
        assertTrue(log.contains("Try:"), log)
        assertTrue(log.contains("Examples, ideas, and opt-out:"), log)
        assertTrue(log.contains("RULES-AND-OPTOUTS.md#gates"), log)
        assertFalse(log.contains("Rule:"), log)
        assertFalse(log.contains("Why:"), log)
    }

    @Test
    fun `warnings inform but do not block`() {
        val warningOnly = listOf(
            Finding(
                audit = Audit.DeadEnd,
                severity = Severity.Warning,
                where = "s",
                because = "because",
                instead = "instead",
                guide = Conscience.gateGuide,
            ),
        )
        assertFalse(Conscience.blocks(warningOnly))
    }
}
