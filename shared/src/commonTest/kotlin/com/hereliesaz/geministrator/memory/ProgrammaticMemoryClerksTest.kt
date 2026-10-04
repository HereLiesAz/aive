package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgrammaticMemoryClerksTest {
    @Test
    fun wholePipelineRunsWithoutAModelAndRecallFindsIt() = runBlocking {
        val store = InMemoryMemoryStore()
        val layer = AgentMemoryLayer.createWithMicroAgents(store, ProgrammaticMemoryClerks.all { 1_000L })
        layer.queue.enqueueSession(
            MemorySessionEnvelope(
                sourceSessionId = "s1",
                projectId = "aive",
                userPrompt = "Move the memory store off JSON settings because every commit re-encodes it.",
                parts = listOf(
                    MemorySessionPart(
                        MemorySourceKind.Message,
                        "Agent",
                        """
                        # Findings
                        SettingsMemoryStore.commit() serializes the whole snapshot on every write.
                        We decided to replace it with an append-only log in `MemoryLog.kt`.

                        ok

                        The Gradle build and unit tests pass after the change.
                        """.trimIndent(),
                    ),
                ),
                closedAtEpochMillis = 1_000L,
            ),
        )

        var guard = 0
        while (layer.consolidateOne(1_000L) != MemoryConsolidationResult.Idle) {
            check(++guard < 200) { "consolidation did not settle" }
        }

        val snapshot = store.read()
        assertTrue(snapshot.queue.all { it.status == MemoryQueueStatus.Complete }, "queue: ${snapshot.queue}")
        val kinds = snapshot.nodes.groupingBy { it.kind }.eachCount()
        listOf(
            MemoryNodeKind.Context, MemoryNodeKind.NounTag, MemoryNodeKind.VerbTag,
            MemoryNodeKind.Phrase, MemoryNodeKind.Summary, MemoryNodeKind.Category,
        ).forEach { assertTrue((kinds[it] ?: 0) > 0, "no $it nodes: $kinds") }
        assertFalse(snapshot.nodes.any { it.text.trim() == "ok" }, "acknowledgement kept as memory")
        assertTrue(snapshot.nodes.any { it.kind == MemoryNodeKind.Category && it.text == "data" })

        val recall = layer.tool.grip(MemoryQuery("SettingsMemoryStore commit", resolution = MemoryResolution.Context))
        assertTrue(recall.hits.any { "append-only log" in it.node.text }, "recall: ${recall.hits.map { it.node.text }}")
    }

    @Test
    fun condensationDeclinesWhenMembersDisagreeOnAValue() {
        assertTrue(condensationWouldAdjudicate(listOf("Max connections: 50.", "Max connections: 100.")))
        assertTrue(condensationWouldAdjudicate(listOf("Cache is enabled.", "Cache is not enabled.")))
        assertFalse(condensationWouldAdjudicate(listOf("Build uses Gradle 9.", "The build uses Gradle 9.")))
    }

    @Test
    fun structuralBlocksKeepCodeFencesWhole() {
        val blocks = "## Title\nIntro line.\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\nTail.".structuralBlocks()
        assertEquals(listOf("## Title\nIntro line.", "```kotlin\nval a = 1\n\nval b = 2\n```", "Tail."), blocks)
    }

    @Test
    fun tagsFindCodeEntitiesKeyphrasesAndActions() {
        val text = "We decided to replace SettingsMemoryStore with an append-only log and fixed the flaky tests."
        val nouns = text.nounTags()
        val verbs = text.verbTags()
        assertTrue("SettingsMemoryStore" in nouns, "nouns: $nouns")
        assertTrue(nouns.any { "log" in it }, "nouns: $nouns")
        assertTrue("replace" in verbs && "decide" in verbs && "fix" in verbs, "verbs: $verbs")
    }

    @Test
    fun programmaticTaggerReadsSensesImpliedEntitiesNegationAndObjects() = runBlocking {
        val text = "Didn't delete the old branch. Roll back the release because FooRepository threw a NullPointerException; " +
            "the cache was stale, so I cleared it."
        val packet = MemoryWorkPacket(
            queueId = MemoryQueueId("q"),
            episodeId = MemoryEpisodeId("e"),
            stage = MemoryConsolidationStage.Tags,
            packetKey = "p",
            items = listOf(MemoryWorkItem("ctx-1", "node:Context", text, mapOf("sourceSectionIds" to "s1"))),
            instruction = "",
        )
        val nouns = ProgrammaticMemoryClerks.forRole(MemoryMicroAgentRole.NounTagger) { 1L }.process(packet).nodesToAdd
        val verbs = ProgrammaticMemoryClerks.forRole(MemoryMicroAgentRole.VerbTagger) { 1L }.process(packet).nodesToAdd
        val nounTexts = nouns.map { it.text }
        val verbTexts = verbs.map { it.text }

        assertTrue(nouns.none { it.metadata[TAG_KEY].orEmpty().startsWith("legacy:") }, "language resources did not load")
        assertTrue("FooRepository" in nounTexts && "NullPointerException" in nounTexts, "nouns: $nounTexts")
        // Implied: the identifier's head, the exception class, broader terms.
        assertEquals("Identifier", nouns.single { it.text == "repository" }.metadata[TAG_IMPLIED_BY])
        assertEquals("Exception", nouns.single { it.text == "exception" }.metadata[TAG_IMPLIED_BY])
        // Negation is kept, never dropped; synonyms group under the canonical tag.
        val notRemove = verbs.single { it.text == "not remove" }
        assertEquals("true", notRemove.metadata[TAG_NEGATED])
        assertTrue("delete" in notRemove.metadata[TAG_ALIASES].orEmpty(), "aliases: ${notRemove.metadata}")
        // "roll back" is the overlay's revert; "it" resolves to the cache.
        assertTrue("revert" in verbTexts, "verbs: $verbTexts")
        assertTrue("clear cache" in verbs.single { it.text == "clear" }.metadata[TAG_PHRASES].orEmpty(), "verbs: ${verbs.map { it.metadata }}")
        // Implied concepts rank after explicit ones.
        val firstImplied = nouns.indexOfFirst { it.metadata[TAG_IMPLIED_BY] != null }
        assertTrue(nouns.drop(firstImplied).all { it.metadata[TAG_IMPLIED_BY] != null })

        // The phrase clerk pairs a verb only with the objects the text gives it.
        val phrasePacket = packet.copy(
            stage = MemoryConsolidationStage.Phrases,
            items = (nouns + verbs).map { node ->
                MemoryWorkItem(node.id.value, "node:${node.kind.name}", node.text, node.metadata + ("sourceSectionIds" to "s1"))
            },
        )
        val phrases = ProgrammaticMemoryClerks.forRole(MemoryMicroAgentRole.PhraseSynthesizer) { 1L }.process(phrasePacket).nodesToAdd.map { it.text }
        assertTrue("clear cache" in phrases && "revert release" in phrases, "phrases: $phrases")
        assertFalse(phrases.any { it.startsWith("clear ") && it != "clear cache" }, "phrases: $phrases")
    }

    @Test
    fun sentencesNeverSplitInsideVersionsPathsIdentifiersOrAbbreviations() {
        val sentences = memorySentences(
            "Bumped Kotlin to v2.1.0, e.g. for `foo.bar()` in src/a.kt. It calls client.send() now! Done? Yes.",
        )
        assertEquals(
            listOf("Bumped Kotlin to v2.1.0, e.g. for `foo.bar()` in src/a.kt.", "It calls client.send() now!", "Done?", "Yes."),
            sentences,
        )
    }

    @Test
    fun typedBlocksKeepTracesDiffsLogsAndListsWhole() {
        val text = """
            Intro paragraph about the failure.
            java.lang.IllegalStateException: boom
                at com.example.Foo.bar(Foo.kt:10)
                at com.example.Main.main(Main.kt:3)
            diff --git a/x.kt b/x.kt
            @@ -1,2 +1,2 @@
            -val a = 1
            +val a = 2
            2026-10-04 01:00:00 INFO start
            2026-10-04 01:00:01 INFO step
            2026-10-04 01:00:02 ERROR stop
            - first item
            - second item
              continued
        """.trimIndent()
        val types = text.typedBlocks().map { it.type }
        assertEquals(
            listOf(MemoryBlockType.Paragraph, MemoryBlockType.StackTrace, MemoryBlockType.Diff, MemoryBlockType.Log, MemoryBlockType.ListItems),
            types,
        )
    }

    @Test
    fun longProseSplitsUnderTheLimitAtSentenceEnds() {
        val prose = (1..60).joinToString(" ") { "Sentence number $it describes the gradle cache and the build." }
        val blocks = prose.structuralBlocks(1_200)
        assertTrue(blocks.size > 1 && blocks.all { it.length <= 1_200 }, "sizes: ${blocks.map { it.length }}")
        assertTrue(blocks.all { it.endsWith(".") }, "a split landed mid-sentence")
    }

    @Test
    fun salienceDropsToolNoiseAndNearDuplicatesButNeverUserPrompts() = runBlocking {
        fun section(id: String, kind: MemorySourceKind, text: String) =
            MemoryWorkItem(id, "section", text, mapOf("sourceKind" to kind.name))
        val log = (1..8).joinToString("\n") { "2026-10-04 01:00:0$it INFO fetched chunk $it of 8 from cache" }
        val packet = MemoryWorkPacket(
            queueId = MemoryQueueId("q"),
            episodeId = MemoryEpisodeId("e"),
            stage = MemoryConsolidationStage.Salience,
            packetKey = "p",
            items = listOf(
                section("s1", MemorySourceKind.UserPrompt, "ok"),
                section("s2", MemorySourceKind.Message, "> Task :shared:compileKotlin UP-TO-DATE\n> Task :shared:jar UP-TO-DATE"),
                section("s3", MemorySourceKind.AgentNote, "We decided to replace the settings store with an append-only log in MemoryLog.kt."),
                section("s4", MemorySourceKind.AgentNote, "We decided to replace the settings store with an append-only log in MemoryLog.kt!"),
                section("s5", MemorySourceKind.Artifact, log),
            ),
            instruction = "",
        )
        val nodes = ProgrammaticMemoryClerks.forRole(MemoryMicroAgentRole.SalienceFilter) { 1L }.process(packet).nodesToAdd
        val texts = nodes.map { it.text }
        assertTrue("ok" in texts, "user prompt dropped: $texts")
        assertFalse(texts.any { "UP-TO-DATE" in it }, "tool noise kept: $texts")
        assertEquals(1, texts.count { "append-only log" in it }, "near-duplicate kept: $texts")
        val decision = nodes.single { "append-only log" in it.text }
        assertEquals("1", decision.metadata["nearDuplicatesDropped"])
        assertTrue("decision=" in decision.metadata["salienceFeatures"].orEmpty())
        val collapsed = nodes.single { "fetched chunk" in it.text }
        assertEquals(2, collapsed.text.lines().size, "repeated log lines not collapsed: ${collapsed.text}")
        assertEquals("6", collapsed.metadata["collapsedRepeatedLines"])
    }

    @Test
    fun summariesPickWholeSourceSentencesAndKeepValuesWhenTrimming() {
        val extract = MemoryExtractiveSummary.summarize(
            listOf(
                "The Gradle build failed with exit code 137 because the daemon ran out of memory.",
                "I raised the heap to 4 GB in gradle.properties.",
                "The Gradle build failed with exit code 137 because the daemon ran out of memory again.",
            ),
        )!!
        assertTrue(extract.sentences.all { it.endsWith(".") }, "not whole sentences: ${extract.sentences}")
        assertEquals(1, extract.sentences.count { "exit code 137" in it }, "redundant sentence kept: ${extract.sentences}")
        val long = "The cache (which the old SettingsMemoryStore wrote on every commit, see notes) is not used for 3 builds now " + "x".repeat(300)
        val trimmed = MemoryExtractiveSummary.trim(long, 280)
        assertEquals(memoryClaimSignature(long), memoryClaimSignature(trimmed))
    }

    @Test
    fun categoriesNeedEnoughEvidenceAndMatchWholeWordsOnly() {
        val lexicon = MemoryCategoryLexicon(null)
        val hits = lexicon.classify("SettingsMemoryStore.commit() serializes the whole snapshot to JSON on every write.")
        assertTrue(hits.any { it.category == "data" && it.evidence.any { e -> e.startsWith("serialize") } }, "hits: $hits")
        // "ui" inside "build", "ci" inside "decision", "api" inside "rapid" are not words.
        val none = lexicon.classify("A rapid decision about the build.").map { it.category }
        assertFalse("ui" in none || "ci" in none || "api" in none, "substring hits: $none")
        // One weak word is not enough.
        assertTrue(lexicon.classify("Please check this.").isEmpty())
        // Redirected phrase: test data is data, not testing.
        assertFalse(lexicon.classify("Load the test data from the fixture table schema.").any { it.category == "testing" && it.score > 3.0 })
    }

    @Test
    fun tagsLinkBySenseSynonymOrSpellingNotByBroaderTerm() {
        fun tag(text: String, key: String, aliases: String = "") =
            MemoryWorkItem(text, "node:NounTag", text, mapOf(TAG_KEY to key) + if (aliases.isEmpty()) emptyMap() else mapOf(TAG_ALIASES to aliases))
        assertEquals(1f, MemorySimilarity.tags(tag("repo", "n:repository"), tag("repository", "n:repository")))
        assertEquals(0.9f, MemorySimilarity.tags(tag("bug", "n:bug", "defect | fault"), tag("defect", "n:defect")))
        assertTrue(MemorySimilarity.tags(tag("gradle build", "n:gradle build"), tag("gradle builds", "n:gradle builds")) >= 0.92f)
        assertEquals(0f, MemorySimilarity.tags(tag("cache", "n:cache"), tag("storage", "n:storage")))
    }

    @Test
    fun condensationKeepsTheRepresentativeAndAppendsOnlyUncoveredSentences() {
        fun context(id: String, text: String) = MemoryWorkItem(id, "node:Context", text)
        val result = MemoryCondensation.condense(
            listOf(
                context("a", "The build uses Gradle. It runs on the JDK toolchain."),
                context("b", "The build uses Gradle. Configuration cache is enabled."),
                context("c", "The build uses Gradle."),
            ),
        )
        assertTrue("Configuration cache is enabled." in result.text && "JDK toolchain" in result.text, result.text)
        assertEquals(1, Regex("uses Gradle").findAll(result.text).count(), "repeated sentence: ${result.text}")
    }

    @Test
    fun proseOfMostlyUnknownWordsIsNoiseButCodeAndPromptsAreNot() = runBlocking {
        fun section(id: String, kind: MemorySourceKind, text: String) =
            MemoryWorkItem(id, "section", text, mapOf("sourceKind" to kind.name))
        val mash = "qwzx vbnmq plkoj hgfdr tyuxz wqazs edcrf vgbhy"
        val packet = MemoryWorkPacket(
            queueId = MemoryQueueId("q"),
            episodeId = MemoryEpisodeId("e"),
            stage = MemoryConsolidationStage.Salience,
            packetKey = "p",
            items = listOf(
                section("s1", MemorySourceKind.UserPrompt, mash),
                section("s2", MemorySourceKind.Message, mash.uppercase() + " zzqk"),
                section("s3", MemorySourceKind.Artifact, "val qwzx = vbnmq(plkoj) { hgfdr.tyuxz() }"),
                section("s4", MemorySourceKind.AgentNote, "The release build failed because the signing key was missing from the runner."),
            ),
            instruction = "",
        )
        val texts = ProgrammaticMemoryClerks.forRole(MemoryMicroAgentRole.SalienceFilter) { 1L }.process(packet).nodesToAdd.map { it.text }
        assertTrue(mash in texts, "user prompts are never dropped")
        assertFalse(texts.any { "ZZQK" in it }, "keyboard mash kept: $texts")
        assertTrue(texts.any { "val qwzx" in it }, "code is exempt")
        assertTrue(texts.any { "signing key" in it })
    }

    @Test
    fun laterSectionsEarnASmallRecencyFeature() {
        val early = MemorySalienceFeatures.score("The build passed.", "Message", emptySet(), emptyMap(), 0, position = 0f)
        val late = MemorySalienceFeatures.score("The build passed.", "Message", emptySet(), emptyMap(), 0, position = 1f)
        assertTrue(late.score > early.score)
        assertTrue("recency=" in late.explain())
    }

    @Test
    fun tagsRankByPositionSpreadAndCasing() {
        // Early beats late; wide beats narrow; an identifier's casing counts.
        assertTrue(keyphraseImportance("cache", listOf(0), 6) > keyphraseImportance("cache", listOf(4), 6))
        assertTrue(keyphraseImportance("cache", listOf(1, 2, 3, 4), 6) > keyphraseImportance("cache", listOf(1), 6))
        assertTrue(keyphraseImportance("SyncWorker", listOf(2), 6) > keyphraseImportance("worker", listOf(2), 6))
    }

    @Test
    fun minHashPairsNearCopiesAndSkipsUnrelatedText() {
        val texts = buildMap {
            put("a", "the sync worker retries failed uploads with exponential backoff and jitter")
            put("b", "the sync worker retries failed uploads with exponential backoff and random jitter")
            (0 until 60).forEach { put("x$it", "unrelated note number $it about ${listOf("compose", "gradle", "sqlite", "oauth")[it % 4]} release $it") }
        }
        val candidates = MemoryMinHash.candidates(texts)
        assertTrue("b" in candidates["a"].orEmpty())
        assertFalse(candidates["a"].orEmpty().any { it.startsWith("x") })
    }
}
