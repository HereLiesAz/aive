package com.hereliesaz.geministrator.workflow

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The docs say how many hives the coding suite has and how many of them run on a schedule, in words
 * ("Eighteen hives"). Those words drifted from the code once (docs said seventeen and six while the
 * suite had eighteen and seven), so this reads them back and holds them to [CodingWorkflowSuite].
 */
class SuiteCountsInDocsTest {
    private val words = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
        "twenty-one", "twenty-two", "twenty-three", "twenty-four", "twenty-five",
    )
    private val alternation = words.sortedByDescending { it.length }.joinToString("|")

    private val hives = CodingWorkflowSuite.all().size
    private val scheduled = CodingWorkflowSuite.all().count { it.schedule != null }

    private fun doc(name: String): String {
        // Gradle runs desktopTest from the shared/ module directory.
        val file = listOf(File("../docs/$name"), File("docs/$name")).firstOrNull { it.isFile }
        return assertNotNull(file, "docs/$name was not found from ${File(".").absolutePath}").readText()
    }

    private fun wordFor(count: Int) = assertNotNull(words.getOrNull(count), "extend the number words for $count")

    private fun said(text: String, pattern: String): String {
        val match = assertNotNull(Regex(pattern, RegexOption.IGNORE_CASE).find(text), "the docs no longer say: $pattern")
        return match.groupValues[1].lowercase()
    }

    @Test
    fun codingWorkflowsSaysHowManyHivesTheSuiteHas() {
        assertEquals(wordFor(hives), said(doc("Coding-Workflows.md"), "^# The coding suite\\s+($alternation) hives for working on code"))
    }

    @Test
    fun keepersRoomSaysHowManyHivesAndHowManySchedulesTheSuiteAdds() {
        val text = doc("Keepers-Room.md")
        assertEquals(wordFor(hives), said(text, "Add the coding suite\\*\\* puts ($alternation) coding hives"))
        assertEquals(wordFor(scheduled), said(text, "for the ($alternation) that only read and report"))
    }

    @Test
    fun scheduledWorkflowsSaysHowManySchedulesTheSuiteBrings() {
        assertEquals(wordFor(scheduled), said(doc("Scheduled-Workflows.md"), "coding suite \\(\\[Coding workflows\\]\\(Coding-Workflows.md\\)\\) brings ($alternation), off"))
    }

    @Test
    fun theScheduleTableListsEveryScheduledHive() {
        val text = doc("Coding-Workflows.md")
        val section = text.substringAfter("## On a schedule").substringBefore("\n## ")
        val rows = section.lines().count { it.startsWith("| ") && !it.startsWith("| Hive") }
        assertEquals(scheduled, rows, "the schedule table and the code disagree")
    }
}
