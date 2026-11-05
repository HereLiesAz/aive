package com.hereliesaz.geministrator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compose Resources formats `%1$s`-style placeholders by indexing the argument list. A string that asks for
 * `%2$d` but not `%1$` is called with one argument less than it indexes, and the formatter throws
 * IndexOutOfBoundsException while the screen is composing (it crashed the Delete hive confirmation).
 * Every string's placeholders must therefore be numbered 1..n with no gap.
 */
class StringPlaceholderTest {
    private val dirs = listOf(
        File("src/commonMain/composeResources"),
        File("../androidApp/src/main/res"),
    )
    private val entry = Regex("""<(?:string|item)\b[^>]*>(.*?)</(?:string|item)>""", RegexOption.DOT_MATCHES_ALL)
    private val named = Regex("""<string name="([^"]+)"[^>]*>""")
    private val placeholder = Regex("""%(\d+)\$""")

    @Test
    fun placeholdersAreNumberedFromOneWithoutGaps() {
        val broken = mutableListOf<String>()
        var checked = 0
        dirs.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }
                .forEach { file ->
                    val text = file.readText()
                    entry.findAll(text).forEach { match ->
                        val numbers = placeholder.findAll(match.groupValues[1]).map { it.groupValues[1].toInt() }.toSortedSet()
                        if (numbers.isEmpty()) return@forEach
                        checked++
                        if (numbers.toList() != (1..numbers.last()).toList()) {
                            val name = named.find(match.value)?.groupValues?.get(1) ?: "(plural item)"
                            broken += "${file.path}: $name uses placeholders $numbers"
                        }
                    }
                }
        }
        assertTrue(checked > 100, "expected to find the app's formatted strings, found $checked")
        assertTrue(broken.isEmpty(), "strings that skip a placeholder number:\n" + broken.joinToString("\n"))
    }
}
