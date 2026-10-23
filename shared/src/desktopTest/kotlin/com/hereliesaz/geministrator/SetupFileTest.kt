package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.memory.MemoryLayerSettingsStore
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupFileTest {
    private val secrets = SetupSecrets(
        providerCredentials = mapOf(ProviderCatalog.OPENAI_ID to "sk-test-provider-key-123"),
        repositoryCredentials = mapOf(RepositoryServiceCatalog.GITHUB_ID to "ghp_test_repository_token_456"),
        distributedComputeToken = "compute-test-token-789",
        remoteRelayUrl = "https://relay.example.test",
        remoteRelayPairing = """{"relay":"https://relay.example.test","room":"r","hostToken":"h","clientToken":"c","key":"k"}""",
    )

    @Test
    fun roundTripRestoresEverySection() = runBlocking {
        val source = SourceSetup.create()
        val encoded = exportFrom(source)

        val target = InMemoryWorkflowPersistence()
        val targetSettings = InMemorySettings().apply {
            putString("haive.workflows.authored.v1", "stale")
            putString("unrelated.key", "kept")
        }
        val targetStore = FakeSecretStore()
        val decoded = SetupFileCodec.decode(encoded, PASSPHRASE)
        val summary = SetupFileTransfer(target, targetSettings).apply(decoded.contents)
        targetStore.write(decoded.secrets)

        assertEquals(listOf(source.project), target.projects.all())
        assertEquals("feature/setup", target.projects.all().single().repository?.defaultBranch)
        assertEquals(listOf(source.definition), target.definitions.all())
        assertEquals(source.persistence.roles.all().toSet(), target.roles.all().toSet())
        SETUP_SETTINGS_KEYS.forEach { key ->
            assertEquals(source.settings.getStringOrNull(key), targetSettings.getStringOrNull(key), key)
        }
        assertEquals("1.0,2.0", targetSettings.getStringOrNull(TERRARIUM_KEY))
        assertEquals("kept", targetSettings.getStringOrNull("unrelated.key"))
        assertNull(targetSettings.getStringOrNull(UI_DRAFT_KEY), "UI drafts are not setup")
        val inference = Json.parseToJsonElement(
            requireNotNull(target.inferenceSettings.getStringOrNull(INFERENCE_KEY)),
        ).jsonObject
        assertEquals(1, inference.getValue("models").jsonArray.size)
        assertFalse("records" in inference, "inference records are run history")
        assertEquals(true, decoded.contents.crashReportingEnabled)
        assertEquals(secrets, targetStore.written)
        assertEquals(1, summary.projects)
        assertEquals(1, summary.definitions)
    }

    @Test
    fun wrongPassphraseIsRejectedWithNothingWritten() = runBlocking {
        val encoded = exportFrom(SourceSetup.create())
        val target = InMemoryWorkflowPersistence()
        val targetStore = FakeSecretStore()

        val failure = assertFailsWith<SetupFileException> {
            importSetupFile(target, targetStore, encoded, "not the passphrase", onCrashReportingChange = { error("written") }, memoryLayer = null)
        }

        assertEquals(SetupFileFailure.WrongPassphrase, failure.failure)
        assertTrue(target.projects.all().isEmpty())
        assertTrue(target.definitions.all().isEmpty())
        assertTrue(target.roles.all().isEmpty())
        assertNull(targetStore.written)
    }

    @Test
    fun editedPlainPartFailsAuthentication() = runBlocking {
        val encoded = exportFrom(SourceSetup.create())
        val tampered = rewrite(encoded) { document ->
            val setupJson = (document.getValue("setupJson") as JsonPrimitive).content
            JsonObject(document + ("setupJson" to JsonPrimitive(setupJson.replace("Setup project", "Evil project"))))
        }

        val failure = assertFailsWith<SetupFileException> { SetupFileCodec.decode(tampered, PASSPHRASE) }
        assertEquals(SetupFileFailure.WrongPassphrase, failure.failure)
    }

    @Test
    fun futureFormatVersionIsRejected() = runBlocking {
        val encoded = exportFrom(SourceSetup.create())
        val future = rewrite(encoded) { JsonObject(it + ("formatVersion" to JsonPrimitive(SETUP_FILE_FORMAT_VERSION + 1))) }

        val failure = assertFailsWith<SetupFileException> { SetupFileCodec.decode(future, PASSPHRASE) }
        assertEquals(SetupFileFailure.UnsupportedVersion, failure.failure)
    }

    @Test
    fun foreignFileIsRejected() = runBlocking {
        val project = assertFailsWith<SetupFileException> { SetupFileCodec.decode("""{"format":"the-aive-project"}""", PASSPHRASE) }
        assertEquals(SetupFileFailure.NotASetupFile, project.failure)
        val garbage = assertFailsWith<SetupFileException> { SetupFileCodec.decode("not json", PASSPHRASE) }
        assertEquals(SetupFileFailure.NotASetupFile, garbage.failure)
    }

    @Test
    fun runsEventsAndPlainSecretsAreNotInTheFile() = runBlocking {
        val encoded = exportFrom(SourceSetup.create())

        assertFalse(RUN_OBJECTIVE in encoded, "workflow runs are not setup")
        assertFalse("ApprovalDecisionReceived" in encoded, "workflow events are not setup")
        assertFalse(INFERENCE_RECORD_MARKER in encoded, "inference records are not setup")
        assertFalse(UI_DRAFT_VALUE in encoded, "UI drafts are not setup")
        listOf(
            "sk-test-provider-key-123",
            "ghp_test_repository_token_456",
            "compute-test-token-789",
            "hostToken",
        ).forEach { secret -> assertFalse(secret in encoded, "secret stored in plain text: $secret") }
        val document = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(SETUP_FILE_FORMAT_VERSION, (document.getValue("formatVersion") as JsonPrimitive).content.toInt())
        assertTrue(SetupFileCodec.ITERATIONS >= 310_000)
        assertFalse("sk-test" in secrets.toString(), "SetupSecrets.toString must not reveal values")
    }

    private suspend fun exportFrom(source: SourceSetup): String = SetupFileCodec.encode(
        contents = SetupFileTransfer(source.persistence, source.settings).collect(crashReportingEnabled = true),
        secrets = FakeSecretStore(secrets).read(),
        passphrase = PASSPHRASE,
        createdAtEpochMillis = 1L,
    )

    private fun rewrite(encoded: String, transform: (JsonObject) -> JsonObject): String =
        transform(Json.parseToJsonElement(encoded).jsonObject).toString()

    private class FakeSecretStore(private val stored: SetupSecrets = SetupSecrets()) : SetupSecretStore {
        var written: SetupSecrets? = null
        override fun read(): SetupSecrets = stored
        override fun write(secrets: SetupSecrets) {
            written = secrets
        }
    }

    private class SourceSetup(
        val persistence: InMemoryWorkflowPersistence,
        val settings: InMemorySettings,
        val project: Project,
        val definition: WorkflowDefinition,
    ) {
        companion object {
            suspend fun create(): SourceSetup {
                val persistence = InMemoryWorkflowPersistence()
                val project = Project(
                    id = ProjectId("setup-project"),
                    name = "Setup project",
                    repository = RepositoryRef(owner = "octo", name = "repo", defaultBranch = "feature/setup"),
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 2L,
                )
                val taskId = TaskDefinitionId("build")
                val definition = WorkflowDefinition(
                    id = WorkflowDefinitionId("setup-definition"),
                    name = "Authored",
                    tasks = listOf(
                        TaskDefinition(
                            id = taskId,
                            name = "Build",
                            objective = "Build it",
                            roleId = BuiltInRoles.ImplementationEngineer.id,
                            executor = TaskExecutor.GitHubAction(workflow = "opencode.yml"),
                        ),
                    ),
                )
                persistence.projects.put(project)
                persistence.definitions.put(definition)
                persistence.roles.put(BuiltInRoles.Architect.copy(name = "Chief Architect"))
                persistence.runs.put(
                    WorkflowRun(
                        id = WorkflowRunId("setup-run"),
                        projectId = project.id,
                        workflowDefinitionId = definition.id,
                        objective = RUN_OBJECTIVE,
                        status = WorkflowRunStatus.Running,
                        taskRuns = emptyMap(),
                        createdAtEpochMillis = 1L,
                        updatedAtEpochMillis = 1L,
                    ),
                )
                persistence.events.append(
                    ApprovalDecisionReceived(
                        workflowRunId = WorkflowRunId("setup-run"),
                        taskDefinitionId = taskId,
                        gateId = ApprovalGateId("gate"),
                        approved = true,
                        decidedByRoleId = RoleDefinitionId("orchestrator"),
                        occurredAtEpochMillis = 1L,
                    ),
                )
                persistence.inferenceSettings.putString(
                    INFERENCE_KEY,
                    """{"version":1,"models":[{"logicalModelId":"lora-1"}],"agents":[],"data":[],""" +
                        """"records":[{"note":"$INFERENCE_RECORD_MARKER"}],"providerRunBindings":[]}""",
                )
                val settings = InMemorySettings()
                SETUP_SETTINGS_KEYS.forEach { key -> settings.putString(key, "value of $key") }
                settings.putString(MemoryLayerSettingsStore.DEFAULT_KEY, "{}")
                settings.putString(TERRARIUM_KEY, "1.0,2.0")
                settings.putString(UI_DRAFT_KEY, UI_DRAFT_VALUE)
                return SourceSetup(persistence, settings, project, definition)
            }
        }
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
        const val RUN_OBJECTIVE = "run objective that is history"
        const val INFERENCE_KEY = "haive.inference.fabric.v1"
        const val INFERENCE_RECORD_MARKER = "inference-record-marker"
        const val TERRARIUM_KEY = "haive.terrarium.layout.v1.0061.0062"
        const val UI_DRAFT_KEY = "aive.ui-state.v1"
        const val UI_DRAFT_VALUE = "half-typed draft"
    }
}
