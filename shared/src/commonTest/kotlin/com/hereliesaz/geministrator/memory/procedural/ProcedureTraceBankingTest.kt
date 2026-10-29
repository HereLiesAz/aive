package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.InMemoryMemoryStore
import com.hereliesaz.geministrator.memory.MemoryBanks
import com.hereliesaz.geministrator.memory.MemoryEngineProvider
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryLayerSettingsStore
import com.hereliesaz.geministrator.memory.MemoryRuntimeBridge
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcedureTraceBankingTest {
    private fun trace() = ProcedureTrace(
        workflowId = "workflow",
        projectId = "project",
        taskRunId = "run-task",
        taskDefinitionId = "task",
        attempt = 1,
        phase = ProcedureTracePhase.Reconcile,
        executorKind = "TestRunner",
        outcome = ProcedureTraceOutcome.Completed,
        artifactKinds = listOf("TestResult"),
        artifactCount = 1,
        recordedAtEpochMillis = 42L,
    )

    @Test
    fun optInRecordsProvenanceButDeduplicatesTerminalReconcilesAndRestarts() = runBlocking {
        val store = InMemoryMemoryStore()
        val banks = MemoryBanks.inMemory(mapOf("workflow" to store))
        val settings = MemoryLayerSettingsStore(MapSettings())
        val scope = CoroutineScope(Dispatchers.Unconfined)
        fun controller() = MemoryLayerController(
            banks = banks,
            settingsStore = settings,
            engineProvider = object : MemoryEngineProvider {},
            scope = scope,
            nowEpochMillis = { 1000L },
        )
        val first = controller()
        first.updateSettings { it.copy(proceduralTraceCaptureEnabled = true, consolidationPaused = true) }
        first.procedureTraceSink.record(trace())
        first.procedureTraceSink.record(trace())
        assertEquals(1, store.read().episodes.size)
        val entry = store.read().episodes.single()
        assertEquals("workflow", entry.workflowRunId)
        assertEquals("project", entry.projectId)
        assertEquals("run-task", entry.taskRunId)
        assertEquals("task", entry.taskDefinitionId)
        assertEquals("executor:run-task:1", entry.sourceSessionId)
        assertTrue(entry.userPrompt.contains("Verification: unknown"))
        assertFalse(entry.userPrompt.contains("sensitive"))

        val restarted = controller()
        restarted.procedureTraceSink.record(trace())
        assertEquals(1, store.read().episodes.size)
        assertEquals(1, store.read().queue.size)
    }

    @Test
    fun captureIsDisabledByDefaultAndWhenMemoryIsDisabled() = runBlocking {
        val store = InMemoryMemoryStore()
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(mapOf("workflow" to store)),
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        controller.procedureTraceSink.record(trace())
        assertTrue(store.read().episodes.isEmpty())
        controller.updateSettings { it.copy(proceduralTraceCaptureEnabled = true, enabled = false) }
        controller.procedureTraceSink.record(trace())
        assertTrue(store.read().episodes.isEmpty())
    }

    @Test
    fun detachRestoresNoOpBridge() = runBlocking {
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(),
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val old = MemoryRuntimeBridge.procedureTraceSink
        try {
            controller.attach()
            assertTrue(MemoryRuntimeBridge.procedureTraceSink === controller.procedureTraceSink)
            controller.detach()
            assertFalse(MemoryRuntimeBridge.procedureTraceSink === controller.procedureTraceSink)
        } finally {
            MemoryRuntimeBridge.procedureTraceSink = old
        }
    }
}
