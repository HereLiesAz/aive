package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.memory.DesktopOrtMemorySessionManager
import com.hereliesaz.geministrator.memory.HostedMemoryEngineProvider
import com.hereliesaz.geministrator.memory.MemoryConsolidationStage
import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import com.hereliesaz.geministrator.memory.MemoryMicroAgentPlatform
import com.hereliesaz.geministrator.memory.MemoryMicroAgentRole
import com.hereliesaz.geministrator.memory.MemoryQueueId
import com.hereliesaz.geministrator.memory.MemoryWorkItem
import com.hereliesaz.geministrator.memory.MemoryWorkPacket
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.http
import io.ktor.http.Url
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Downloads two released epoch-8 memory models and runs them through the desktop engine: the
 * Sectioner (generative) and the Association Linker (embeddings). About 0.6 GB; skipped unless
 * `AIVE_LIVE_MEMORY_MODELS=1`. Models are cached under `AIVE_LIVE_MEMORY_MODEL_DIR` (default
 * build/live-memory-models) so reruns do not download again.
 */
class DesktopMemoryLocalModelsLiveTest {
    @Test
    fun installedModelsRunThroughTheDesktopEngine() = runBlocking {
        if (System.getenv("AIVE_LIVE_MEMORY_MODELS") != "1") return@runBlocking
        val root = File(System.getenv("AIVE_LIVE_MEMORY_MODEL_DIR") ?: "build/live-memory-models")
        HttpClient(CIO) {
            // CIO ignores the JVM proxy properties; honor HTTPS_PROXY when a sandbox routes through one.
            System.getenv("HTTPS_PROXY")?.takeIf(String::isNotBlank)?.let { proxy ->
                engine { this.proxy = ProxyBuilder.http(Url(proxy)) }
            }
        }.use { httpClient ->
            DesktopOrtMemorySessionManager().use { sessions ->
                val installer = DesktopMemoryModelInstaller(httpClient, root)
                val engines = DesktopMemoryEngineProvider(HostedMemoryEngineProvider(MemoryMicroAgentPlatform.Linux), installer, sessions)
                listOf(MemoryMicroAgentRole.Sectioner, MemoryMicroAgentRole.AssociationLinker).forEach { role ->
                    assertNotNull(installer.install(role))
                }

                val sections = engines.localAgent(MemoryMicroAgentRole.Sectioner).process(
                    MemoryWorkPacket(
                        queueId = MemoryQueueId("q"),
                        episodeId = MemoryEpisodeId("e"),
                        stage = MemoryConsolidationStage.Sectioning,
                        packetKey = "s0",
                        items = listOf(
                            MemoryWorkItem(
                                "chunk",
                                "chunk",
                                "The API timeout was raised to 60 seconds after the deploy failed. " +
                                    "Separately, the settings screen moved to a dark theme.",
                            ),
                        ),
                        instruction = "section",
                    ),
                )
                println("[live] sectioner: ${sections.sectionsToAdd.size} section(s)")
                assertTrue(sections.sectionsToAdd.isNotEmpty())

                val links = engines.localAgent(MemoryMicroAgentRole.AssociationLinker).process(
                    MemoryWorkPacket(
                        queueId = MemoryQueueId("q"),
                        episodeId = MemoryEpisodeId("e"),
                        stage = MemoryConsolidationStage.Associations,
                        packetKey = "a0",
                        items = listOf(MemoryWorkItem("a", "node:Summary", "API timeout is configured for 30 seconds.")),
                        neighborhood = listOf(
                            MemoryWorkItem("b", "node:Summary", "API timeout is configured for 60 seconds."),
                            MemoryWorkItem("c", "node:Summary", "The UI uses a dark theme."),
                        ),
                        instruction = "associate",
                    ),
                )
                println("[live] association linker: ${links.edgesToAdd.size} edge(s)")
            }
        }
    }
}
