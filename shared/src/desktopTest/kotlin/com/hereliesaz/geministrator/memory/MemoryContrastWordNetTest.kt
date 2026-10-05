package com.hereliesaz.geministrator.memory

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Contrast detection with WordNet loaded: synonyms fold, sibling concepts contrast. */
class MemoryContrastWordNetTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val wordNet = WordNetLexicon.parse(
        MemoryLanguageResources.gunzip(File(root, "shared/src/commonMain/composeResources/files/aive-wordnet-v1.txt.gz").readBytes()),
    )

    @Test
    fun synonymsAreOneFillerSaidTwoWays() {
        // Capitalized, so without WordNet each reads as a name and the pair as a contrast.
        assertNotNull(MemoryContrast.between("Car parked in lot B.", "Automobile parked in lot B.", wordNet = null))
        assertNull(MemoryContrast.between("Car parked in lot B.", "Automobile parked in lot B.", wordNet))
    }

    @Test
    fun siblingConceptsAreAContrast() {
        // Lowercase words that are not on the value list: without WordNet the slot is not a filler.
        assertNull(MemoryContrast.between("The banner should be red.", "The banner should be blue.", wordNet = null))
        val contrast = assertNotNull(MemoryContrast.between("The banner should be red.", "The banner should be blue.", wordNet))
        assertEquals("red", contrast.leftFiller)
        assertEquals("blue", contrast.rightFiller)
        assertNotNull(MemoryContrast.between("We decided to increase the retry budget.", "We decided to decrease the retry budget.", wordNet))
    }

    @Test
    fun generalizationsAndUnrelatedWordsAreNotSiblings() {
        assertNull(MemoryContrast.between("The dog barked at night.", "The canine barked at night.", wordNet))
        assertNull(MemoryContrast.between("We fixed the flaky cache test.", "We fixed the flaky cache build.", wordNet))
    }

    @Test
    fun existingContrastsAndNonContrastsAreUnchanged() {
        assertNotNull(MemoryContrast.between("I chose Postgres for the database.", "I chose MySQL for the database.", wordNet))
        assertNull(MemoryContrast.between("I chose Postgres for the database.", "I chose PostgreSQL for the database.", wordNet))
        assertNotNull(MemoryContrast.between("API timeout is 30 seconds.", "API timeout is 60 seconds.", wordNet))
        assertNull(MemoryContrast.between("We fixed the flaky cache test today.", "We fixed the flaky cache test.", wordNet))
        assertNull(MemoryContrast.between("The router retries twice.", "Deploy notes live in the wiki.", wordNet))
    }
}
