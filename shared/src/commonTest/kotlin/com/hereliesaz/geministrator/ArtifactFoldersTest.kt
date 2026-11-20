package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactFoldersTest {
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("wf"),
        name = "Feature delivery",
        tasks = listOf(
            TaskDefinition(
                id = TaskDefinitionId("t"), name = "Implement", objective = "Do it",
                roleId = BuiltInRoles.ImplementationEngineer.id,
            ),
        ),
    )
    private val run = WorkflowRunFactory.create(
        definition = definition, workflowRunId = WorkflowRunId("run-0123456789"), projectId = ProjectId("p"),
        objective = "Objective", nowEpochMillis = 0L, taskRunIdFactory = { TaskRunId("tr") },
    )

    private fun entry(id: String, creature: String, label: String, text: String? = "body $id", uri: String? = null, mediaType: String? = null, kind: ArtifactKind = ArtifactKind.Research) =
        ArtifactEntry("t", creature, false, ArtifactRef(ArtifactId(id), kind, TaskRunId("tr"), label, uri, text, mediaType, createdAtEpochMillis = 0L))

    @AfterTest
    fun reset() {
        ArtifactFolderHost.service = null
        ArtifactFolderSettings.setFolder("p", null)
    }

    @Test
    fun aNameIsSafeEverywhere() {
        assertEquals("a-b-c", ArtifactFilePlanner.safeName("a/b\\c", "x"))
        assertEquals("x", ArtifactFilePlanner.safeName("../..", "x"))
        assertEquals("x", ArtifactFilePlanner.safeName("   ", "x"))
        assertEquals("plan", ArtifactFilePlanner.safeName(".plan.", "x"))
        assertTrue(ArtifactFilePlanner.safeName("y".repeat(500), "x").length <= 90)
        assertFalse(ArtifactFilePlanner.safeName("a:b*c?d\"e<f>g|h", "x").any { it in ":*?\"<>|" })
    }

    @Test
    fun theRunHasItsOwnFolderNamedByNumberAndHive() {
        assertEquals("run-12 Feature delivery", ArtifactFilePlanner.runFolder(definition, run, 12))
        assertEquals("run-23456789 Feature delivery", ArtifactFilePlanner.runFolder(definition, run, null))
    }

    @Test
    fun filesAreNamedByCreatureAndLabelWithTheRightExtensionAndNeverCollide() {
        val files = ArtifactFilePlanner.plan(
            definition, run, 3,
            listOf(
                entry("1", "Implement", "plan"),
                entry("2", "Implement", "plan"),
                entry("3", "Implement", "data", mediaType = "application/json"),
                entry("4", "Implement", "notes.md"),
            ),
        )
        assertEquals(
            listOf("Implement - plan.md", "Implement - plan (2).md", "Implement - data.json", "Implement - notes.md"),
            files.map { it.fileName },
        )
        assertTrue(files.all { it.subfolder == "run-3 Feature delivery" })
    }

    @Test
    fun anArtifactThatIsOnlyALinkIsWrittenAsANoteNamingIt() {
        val link = entry("5", "Ship", "pull request", text = null, uri = "https://github.com/x/y/pull/7", kind = ArtifactKind.PullRequest)
        val file = ArtifactFilePlanner.plan(definition, run, 1, listOf(link)).single()
        assertTrue("https://github.com/x/y/pull/7" in file.content, file.content)
        assertTrue(file.content.startsWith("# pull request"))
    }

    private class Recorder(var failing: Set<String> = emptySet()) : ArtifactFolderService {
        val written = mutableListOf<Triple<String, String, String>>()
        override suspend fun chooseFolder() = ArtifactFolderRef("/chosen", "chosen")
        override suspend fun write(folder: String, subfolder: String, fileName: String, content: String): String? {
            if (fileName in failing) return null
            written += Triple(folder, subfolder, fileName)
            return fileName
        }
    }

    @Test
    fun withNoFolderNothingIsWrittenAndItSaysSo() = runBlocking {
        val recorder = Recorder().also { ArtifactFolderHost.service = it }
        val result = ArtifactSaver.save("p", definition, run, 1, listOf(entry("1", "Implement", "plan")))
        assertTrue(result.noFolder)
        assertTrue(recorder.written.isEmpty())
    }

    @Test
    fun savingWritesIntoTheChosenFolderAndCountsFailures() = runBlocking {
        val recorder = Recorder(failing = setOf("Implement - bad.md")).also { ArtifactFolderHost.service = it }
        ArtifactFolderSettings.setFolder("p", ArtifactFolderRef("/chosen", "chosen"))
        val result = ArtifactSaver.save("p", definition, run, 7, listOf(entry("1", "Implement", "plan"), entry("2", "Implement", "bad")))
        assertEquals(1, result.written)
        assertEquals(1, result.failed)
        assertEquals(listOf(Triple("/chosen", "run-7 Feature delivery", "Implement - plan.md")), recorder.written)
    }

    @Test
    fun savingAsTheyAreMadeNeverWritesTheSameArtifactTwice() = runBlocking {
        val recorder = Recorder().also { ArtifactFolderHost.service = it }
        ArtifactFolderSettings.setFolder("p", ArtifactFolderRef("/chosen", "chosen"))
        val first = listOf(entry("a", "Implement", "one"))
        ArtifactSaver.save("p", definition, run, 1, first, onlyUnsaved = true)
        ArtifactSaver.save("p", definition, run, 1, first + entry("b", "Implement", "two"), onlyUnsaved = true)
        assertEquals(listOf("Implement - one.md", "Implement - two.md"), recorder.written.map { it.third })
        // A keeper's own Save is not skipped.
        ArtifactSaver.save("p", definition, run, 1, first)
        assertEquals(3, recorder.written.size)
    }

    @Test
    fun theSwitchIsOnlyEverOnWithAFolder() {
        ArtifactFolderSettings.setAutomatic("p", true)
        assertFalse(ArtifactFolderSettings.automatic("p"))
        ArtifactFolderSettings.setFolder("p", ArtifactFolderRef("/chosen", "chosen"))
        assertTrue(ArtifactFolderSettings.automatic("p"))
        ArtifactFolderSettings.setFolder("p", null)
        assertFalse(ArtifactFolderSettings.automatic("p"))
        assertNull(ArtifactFolderSettings.folder("p"))
    }
}
