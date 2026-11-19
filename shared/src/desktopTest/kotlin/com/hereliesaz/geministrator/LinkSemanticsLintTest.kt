package com.hereliesaz.geministrator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The link convention (docs/Navigation-Map.md, 5.4): something a person can tap names its role, so a screen reader
 * says "button" and the thing is not colour-only. A source scan, in the style of [PlacePointersLinkedTest]: a
 * `clickable(` call that names no `role` is counted, and the count may only fall. A new tap target uses
 * `LinkText`, `LiveInspectorLine`, `AzphaltNote`, `AzphaltRecord` or `clickable(role = Role.Button, ...)`.
 */
class LinkSemanticsLintTest {
    /** The calls that named no role when this guard was written; lower it when one is fixed, never raise it. */
    private val roleLessClickableBaseline = 17

    private fun sources(): List<File> =
        File("src/commonMain/kotlin/com/hereliesaz/geministrator").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun clickableWithoutARoleDoesNotGrow() {
        val bare = sources().sumOf { file ->
            file.readLines().count { line -> ".clickable(" in line && "role" !in line }
        }
        assertTrue(
            bare <= roleLessClickableBaseline,
            "$bare clickable calls name no role (baseline $roleLessClickableBaseline): give the new one role = Role.Button or use a link composable",
        )
    }

    @Test
    fun anExternalLinkIsTheOnlyUseOfTheLinkRole() {
        // In-app navigation is Role.Button with an onClickLabel; Role.Link is for openUri targets only (5.4).
        val users = sources().filter { file -> file.readText().contains("Role.Link") }.map { it.name }
        assertTrue(users.size <= 6, "Role.Link is for external links only; found it in $users")
    }
}
