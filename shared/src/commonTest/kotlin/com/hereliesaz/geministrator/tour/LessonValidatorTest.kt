package com.hereliesaz.geministrator.tour

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LessonValidatorTest {
    private val rows = listOf(
        TargetRow("MESSAGES_BUTTON", "Messages", emptySet(), true, setOf(ExpectKind.Tapped)),
        TargetRow("KEEPERS_ROOM_GEAR", "Keeper's room", emptySet(), false, setOf(ExpectKind.Tapped)),
    )
    private val ctx = LessonContext(
        plannedTarget = "MESSAGES_BUTTON",
        targets = rows,
        uiWords = setOf("Messages", "Keeper's room", "Mind"),
        introduced = emptySet(),
    )

    /** A fixture payload for tests only; these words are never shipped. */
    private fun payload(
        target: String = "MESSAGES_BUTTON",
        sentence: String = "Do the one thing at the top.",
        expect: String = """{"type":"tapped","target":"MESSAGES_BUTTON"}""",
        explain: String = "Short explanation.",
        ifMissed: String = "Look again.",
        checkIn: String = "Are you with me so far?",
        anotherWay: String = "Another angle.",
        smaller: String = """["one","two"]""",
        extra: String = "",
    ) = """{"target":"$target","sentence":"$sentence","expect":$expect,"explain":"$explain","ifMissed":"$ifMissed","checkIn":"$checkIn","anotherWay":"$anotherWay","smaller":$smaller$extra}"""

    private fun rules(text: String, c: LessonContext = ctx): Set<String> =
        (LessonValidator.validate(text, c) as? LessonValidation.Invalid)?.violations?.map { it.rule }?.toSet() ?: emptySet()

    @Test
    fun aGoodPayloadPassesAndAFencedOneToo() {
        assertIs<LessonValidation.Valid>(LessonValidator.validate(payload(), ctx))
        assertIs<LessonValidation.Valid>(LessonValidator.validate("```json\n" + payload() + "\n```", ctx))
    }

    @Test
    fun l1ParseFailures() {
        assertEquals(setOf("L1"), rules("not json"))
        assertEquals(setOf("L1"), rules("Here you go: " + payload()))
        assertEquals(setOf("L1"), rules(payload(extra = ""","surprise":"x"""")))
        assertEquals(setOf("L1"), rules(payload(expect = """[{"type":"tapped"},{"type":"opened"}]""")))
        assertEquals(setOf("L1"), rules(payload(sentence = "x".repeat(2000))))
    }

    @Test
    fun l2WrongOrUnknownTarget() {
        assertTrue("L2" in rules(payload(target = "KEEPERS_ROOM_GEAR", expect = """{"type":"tapped","target":"KEEPERS_ROOM_GEAR"}""")))
        val unknown = ctx.copy(plannedTarget = "NOPE")
        assertTrue("L2" in rules(payload(target = "NOPE", expect = """{"type":"tapped","target":"NOPE"}"""), unknown))
    }

    @Test
    fun l3CheckpointMustMatchTheTargetAndAllowedKind() {
        assertTrue("L3" in rules(payload(expect = """{"type":"tapped","target":"KEEPERS_ROOM_GEAR"}""")))
        assertTrue("L3" in rules(payload(expect = """{"type":"opened","target":"MESSAGES_BUTTON"}""")))
    }

    @Test
    fun l4WordsMustBeRealUiWords() {
        assertTrue("L4" in rules(payload(sentence = "Open the **Mind tab** now.")))
        assertTrue("L4" in rules(payload(sentence = "Use Messages tab here.")))
        assertEquals(emptySet(), rules(payload(sentence = "Use **Mind** here.")))
    }

    @Test
    fun l5OnlyIntroducedControlsMayBeNamed() {
        assertTrue("L5" in rules(payload(explain = "Later you will see Keeper's room.")))
        val introduced = ctx.copy(introduced = setOf("KEEPERS_ROOM_GEAR"))
        assertTrue("L5" !in rules(payload(explain = "Earlier you saw Keeper's room."), introduced))
    }

    @Test
    fun l6Sizes() {
        assertTrue("L6" in rules(payload(sentence = "x ".repeat(90))))
        assertTrue("L6" in rules(payload(explain = (1..80).joinToString(" ") { "w" })))
        assertTrue("L6" in rules(payload(smaller = """["a","b","c"]""")))
        assertTrue("L6" in rules(payload(ifMissed = (1..40).joinToString(" ") { "w" })))
    }

    @Test
    fun l7RetiredWords() {
        assertTrue("L7" in rules(payload(explain = "Check the inbox.")))
        assertTrue("L7" in rules(payload(explain = "Open the Timeline.")))
    }

    @Test
    fun l8Safety() {
        assertTrue("L8" in rules(payload(explain = "Go to https://example.test now.")))
        assertTrue("L8" in rules(payload(explain = "See <b>this</b>.")))
        assertTrue("L8" in rules(payload(explain = "Read [this](x).")))
        assertTrue("L8" in rules(payload(explain = "Paste your api key here.")))
        assertTrue("L8" in rules(payload(explain = "Use sk-abcdefghijklmnopqrstuvwxyz123456.")))
    }

    @Test
    fun l9FirstLessonCarriesTheConsentFacts() {
        val first = ctx.copy(firstLesson = true)
        assertTrue("L9" in rules(payload(), first))
        val ok = payload(explain = "I read a summary, never your goals or keys. Free Sources may log what they get.")
        assertTrue("L9" !in rules(ok, first))
    }

    @Test
    fun l10HiveCountClaimsMustMatchTheFacts() {
        assertTrue("L10" in rules(payload(explain = "You have 3 hives.")))
        assertTrue("L10" in rules(payload(explain = "You have 3 hives."), ctx.copy(knownHiveCount = 2)))
        assertTrue("L10" !in rules(payload(explain = "You have 3 hives."), ctx.copy(knownHiveCount = 3)))
    }

    @Test
    fun l11CheckInsAreNotQuizzes() {
        for (bad in listOf("Which tab holds the budget?", "What is a Source?", "Explain the queen?", "Do you see Messages okay?")) {
            assertTrue("L11" in rules(payload(checkIn = bad)), bad)
        }
        for (good in listOf("Are you with me so far?", "Does that make sense?", "Want me to slow down?")) {
            assertEquals(emptySet(), rules(payload(checkIn = good)), good)
        }
        assertTrue("L11" in rules(payload(anotherWay = "Like Keeper's room, but busier.")))
    }

    @Test
    fun repairLoopRetriesWithViolationsThenAccepts() = runTest {
        val seen = mutableListOf<List<Violation>>()
        val outcome = writeValidatedLesson(ctx, LessonWriter { prev ->
            seen.add(prev)
            if (seen.size == 1) payload(explain = "See <b>this</b>.") else payload()
        })
        assertIs<LessonWriteOutcome.Accepted>(outcome)
        assertEquals(2, outcome.attempts)
        assertTrue(seen[1].any { it.rule == "L8" })
    }

    @Test
    fun repairLoopIsBoundedAndProducesNoFallback() = runTest {
        var calls = 0
        val outcome = writeValidatedLesson(ctx, LessonWriter { calls++; "nonsense" })
        assertIs<LessonWriteOutcome.Exhausted>(outcome)
        assertEquals(TeachingLimits.LESSON_REPAIRS + 1, calls)
    }
}
