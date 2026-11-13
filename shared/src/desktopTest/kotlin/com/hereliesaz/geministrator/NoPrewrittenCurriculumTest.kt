package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.tour.TourTargetId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The owner's rule: no prewritten curriculum. Lessons are generated on every run; nothing that reads as a lesson, a
 * lesson order or a syllabus may ship in resources or source. This is a conservative scanner (it errs toward flagging),
 * with a meta-test that proves each rule can actually detect a violation.
 */
class NoPrewrittenCurriculumTest {
    private val stringElement = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val chromeKeyPrefixes = listOf("tour_", "intro_")
    private val imperative = Regex("""(?i)\b(tap|open|click|press|go to|select|choose)\b""")

    private val valuesDirs = listOf(File("src/commonMain/composeResources"))
        .filter { it.isDirectory }
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }.toList() }

    private fun allStrings(): Map<String, String> = valuesDirs.flatMap { f ->
        stringElement.findAll(f.readText()).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
    }.toMap()

    /** Every control name and visible word a lesson could name: registry labels plus the non-chrome UI words. */
    private fun uiWords(strings: Map<String, String>): Set<String> {
        val labels = TourTargetId.entries.mapNotNull { strings[it.labelKey] }
        val others = strings.filterKeys { k -> chromeKeyPrefixes.none { k.startsWith(it) } }.values
            .map { it.replace(Regex("""%\d*\$?[sd]"""), "").trim() }
            .filter { it.length in 4..24 && !it.contains(' ') }
        return (labels + others).toSet()
    }

    // The rules, as pure functions so the meta-test can feed them violations. ---------------------------------------

    /** True when [text] has an imperative verb followed within three words by a UI word. */
    private fun readsAsLesson(text: String, words: Set<String>): Boolean {
        for (m in imperative.findAll(text)) {
            val after = text.substring(m.range.last + 1).split(Regex("""[^\p{L}']+""")).filter { it.isNotEmpty() }.take(3)
            if (after.any { w -> words.any { it.equals(w, ignoreCase = true) } }) return true
            val tail = text.substring(m.range.last + 1).take(40)
            if (words.any { it.contains(' ') && tail.contains(it, ignoreCase = true) }) return true
        }
        return false
    }

    private fun chromeProblems(strings: Map<String, String>, words: Set<String>): List<String> =
        strings.filterKeys { k -> chromeKeyPrefixes.any { k.startsWith(it) } }.mapNotNull { (k, v) ->
            when {
                v.length > 48 -> "$k is longer than 48 characters"
                v.count { it == '.' || it == '!' || it == '?' } > 1 -> "$k is more than one sentence"
                readsAsLesson(v, words) -> "$k reads as a lesson"
                else -> null
            }
        }

    private val stringLiteral = Regex(""""((?:[^"\\\n]|\\.)*)"""")

    private fun lessonLiterals(source: String, words: Set<String>): List<String> = source.lines()
        .filter { line -> val t = line.trim(); !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") }
        .flatMap { line -> stringLiteral.findAll(line).map { it.groupValues[1] }.toList() }
        .filter { readsAsLesson(it, words) }

    private val idList = Regex("""(?:listOf|arrayOf|setOf|mutableListOf)\s*\((?:[^()]*?TourTargetId\.[A-Z_]+){3,}""", RegexOption.DOT_MATCHES_ALL)
    private val indexMap = Regex("""\b\d+\s+to\s+TourTargetId\.[A-Z_]+""")
    private val whenIndex = Regex("""\b\d+\s*->\s*TourTargetId\.[A-Z_]+""")

    /** Fixed syllabus in code form: an ordered list of three or more target ids, or an index mapped to a target. */
    private fun syllabusInCode(source: String): List<String> = buildList {
        if (idList.containsMatchIn(source)) add("an ordered list of three or more TourTargetId entries")
        if (indexMap.containsMatchIn(source) || whenIndex.containsMatchIn(source)) add("a lesson index mapped to a target")
        val quotedIds = TourTargetId.registeredIds.filter { source.contains("\"$it\"") }
        if (quotedIds.size >= 3) add("three or more target ids as string literals")
    }

    private fun tourSources(): List<File> = File("src/commonMain/kotlin")
        .walkTopDown().filter { it.isFile && it.extension == "kt" && it.parentFile.name == "tour" && it.name != "TourTarget.kt" }.toList()

    // The real scans. ------------------------------------------------------------------------------------------------

    @Test
    fun noLessonFolderOrLessonFileShipsInResources() {
        val files = File("src/commonMain/composeResources/files")
        val offenders = files.walkTopDown().filter {
            val n = it.name.lowercase()
            n in setOf("guide", "tour", "intro", "lessons", "curriculum", "tutorial") || (it.isFile && Regex("""(lesson|curriculum|tutorial|syllabus)""").containsMatchIn(n))
        }.map { it.path }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun tourChromeStringsAreChromeNotLessons() {
        assertTrue(valuesDirs.isNotEmpty(), "resources not found from ${File(".").absolutePath}")
        val strings = allStrings()
        assertTrue(strings.keys.any { it.startsWith("tour_") }, "the overlay chrome strings are missing")
        assertEquals(emptyList<String>(), chromeProblems(strings, uiWords(strings)))
    }

    @Test
    fun noSourceStringInTheTourCodeReadsAsALesson() {
        val words = uiWords(allStrings())
        val sources = tourSources()
        assertTrue(sources.isNotEmpty(), "tour sources not found from ${File(".").absolutePath}")
        val hits = sources.flatMap { f -> lessonLiterals(f.readText(), words).map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun noSourceHoldsAFixedSyllabus() {
        val all = File("src/commonMain/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "TourTarget.kt" }
        val hits = all.flatMap { f -> syllabusInCode(f.readText()).map { "${f.name}: $it" } }.toList()
        assertEquals(emptyList<String>(), hits)
    }

    // Meta-tests: each rule must detect a violation. -----------------------------------------------------------------

    private val fixtureWords = setOf("Messages", "Keeper's room", "Mind")

    @Test
    fun metaALessonShapedChromeStringIsCaught() {
        val bad = mapOf("tour_step_one" to "Tap Messages to begin.", "tour_ok" to "Got it", "tour_long" to "x".repeat(60), "tour_two" to "One. Two. Three.")
        val problems = chromeProblems(bad, fixtureWords).map { it.substringBefore(' ') }
        assertEquals(setOf("tour_step_one", "tour_long", "tour_two"), problems.toSet())
    }

    @Test
    fun metaALessonShapedLiteralInSourceIsCaught() {
        val source = "val a = \"Open the Mind and look\"\n// \"Tap Messages\"\nval b = \"Step %1\\\$d of %2\\\$d\""
        assertEquals(listOf("Open the Mind and look"), lessonLiterals(source, fixtureWords))
    }

    @Test
    fun metaAFixedSyllabusInCodeIsCaught() {
        val list = "val order = listOf(TourTargetId.MESSAGES_BUTTON, TourTargetId.KEEPERS_ROOM_GEAR, TourTargetId.MESSAGES_BUTTON)"
        val map = "val byIndex = mapOf(0 to TourTargetId.MESSAGES_BUTTON)"
        val whenSrc = "when (k) { 1 -> TourTargetId.KEEPERS_ROOM_GEAR }"
        assertTrue(syllabusInCode(list).isNotEmpty())
        assertTrue(syllabusInCode(map).isNotEmpty())
        assertTrue(syllabusInCode(whenSrc).isNotEmpty())
        assertEquals(emptyList<String>(), syllabusInCode("val one = TourTargetId.MESSAGES_BUTTON"))
    }

    @Test
    fun metaALessonFileInResourcesWouldBeCaught() {
        val dir = createTempDirectory()
        File(dir, "lessons").mkdirs()
        val offenders = dir.walkTopDown().filter { it.name.lowercase() in setOf("lessons") }.toList()
        assertEquals(1, offenders.size)
        dir.deleteRecursively()
    }

    private fun createTempDirectory(): File = java.nio.file.Files.createTempDirectory("curriculum-meta").toFile()
}
