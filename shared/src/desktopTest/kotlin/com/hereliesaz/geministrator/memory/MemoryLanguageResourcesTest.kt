package com.hereliesaz.geministrator.memory

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryLanguageResourcesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val resources = MemoryLanguageResources.fromText(
        MemoryLanguageResources.gunzip(File(root, "shared/src/commonMain/composeResources/files/aive-wordnet-v1.txt.gz").readBytes()),
        MemoryLanguageResources.gunzip(File(root, "shared/src/commonMain/composeResources/files/aive-pos-tagger-v1.txt.gz").readBytes()),
    )

    @Test
    fun kotlinTaggerMatchesTheTrainingScript() {
        val rows = File(root, "tools/memory_lexicon/pos_tagger_golden.tsv").readLines().filter(String::isNotBlank)
        var mismatched = 0
        rows.forEach { row ->
            val (words, tags) = row.split('\t').map { it.split(' ') }
            if (resources.tagger.tag(words) != tags) mismatched++
        }
        assertEquals(0, mismatched, "sentences tagged differently from train_pos_tagger.py")
    }

    @Test
    fun morphyFindsBaseForms() {
        val wordNet = resources.wordNet
        assertEquals(listOf("run"), wordNet.baseForms("ran", WordNetLexicon.Pos.Verb))
        assertTrue("query" in wordNet.baseForms("queries", WordNetLexicon.Pos.Noun))
        assertEquals("nv", wordNet.partsOfSpeech("builds"))
    }
}
