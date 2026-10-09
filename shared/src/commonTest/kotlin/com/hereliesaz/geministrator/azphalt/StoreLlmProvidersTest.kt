package com.hereliesaz.geministrator.azphalt

import com.hereliesaz.geministrator.ProviderCatalog
import com.russhwolf.settings.MapSettings
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreLlmProvidersTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    @AfterTest
    fun isolate() = StoreLlmProviders.useSettings(MapSettings())

    // Trimmed from https://azphalt.store/packages?kind=llm (2026-10-03).
    private val search = """
        {"packages":[
          {"id":"com.hereliesaz.azphalt.llm.kilo","name":"Kilo Auto (free)","kind":"llm","version":"1.0.0",
           "llm":{"tier":"endpoint",
             "inputs":[{"id":"providerKey","type":"promptString","password":true,"optional":true,"description":"Kilo account key (optional)"}],
             "setup":{"sandbox":"github-actions","script":"setup/setup.sh"},
             "endpoint":{"protocols":["openai-chat","github-actions-runner"],"baseUrl":"https://api.kilo.ai/api/gateway",
               "defaultModel":"kilo-auto/free","auth":"optional-bearer","authInput":"providerKey"},
             "dataHandling":{"prompts":"may-train","modelPinned":false,"operator":"Kilo Code"},"role":"text-generation"}},
          {"id":"com.hereliesaz.azphalt.llm.qwen2-5-1-5b","name":"Qwen2.5 1.5B (private sandbox)","kind":"llm","version":"1.0.0",
           "llm":{"tier":"sandbox-weights","setup":{"sandbox":"github-actions"},
             "endpoint":{"protocols":["github-actions-runner"],"defaultModel":"model.gguf","auth":"none"}}},
          {"id":"com.hereliesaz.azphalt.llm.groq","name":"Groq","kind":"llm","version":"1.0.0",
           "llm":{"tier":"endpoint","inputs":[{"id":"providerKey","password":true}],
             "endpoint":{"protocols":["openai-chat"],"baseUrl":"https://api.groq.com/openai/v1","defaultModel":"openai/gpt-oss-20b",
               "auth":"required-bearer","authInput":"providerKey"},
             "dataHandling":{"prompts":"logged","modelPinned":true,"operator":"Groq"}}},
          {"id":"com.hereliesaz.azphalt.llm.moyai","name":"Moyai","kind":"llm","version":"1.0.0",
           "llm":{"tier":"endpoint",
             "inputs":[{"id":"endpointUrl","type":"promptString","description":"Moyai URL"},
                       {"id":"workspacePassword","type":"promptString","password":true,"optional":true,"description":"Workspace password"}],
             "endpoint":{"protocols":["moyai-session"],"baseUrl":"${'$'}{input:endpointUrl}","auth":"none"},
             "dataHandling":{"prompts":"unknown","modelPinned":false,"operator":"Self-hosted Moyai"}}}
        ]}
    """.trimIndent()

    @Test
    fun onlyEndpointPackagesWithSupportedDirectProtocolsAreDirectlyUsable() {
        val packages = json.decodeFromString(AzphaltPackageSearchResponse.serializer(), search).packages
        assertEquals(
            listOf(
                "com.hereliesaz.azphalt.llm.kilo",
                "com.hereliesaz.azphalt.llm.groq",
                "com.hereliesaz.azphalt.llm.moyai",
            ),
            packages.filter { it.isDirectLlmPackage() }.map { it.id },
        )
        val sandbox = packages.single { it.id.endsWith("qwen2-5-1-5b") }.llm!!
        assertTrue(sandbox.directUseProblem()!!.contains("sandbox"))
    }

    @Test
    fun directUseNeedsHttpsAModelAndDisclosure() {
        val good = AzphaltLlm(
            tier = "endpoint",
            endpoint = AzphaltLlmEndpoint(listOf("openai-chat"), "https://example.test/v1", "m", "none"),
            dataHandling = AzphaltLlmDataHandling("not-retained"),
        )
        assertNull(good.directUseProblem())
        assertNotNull(good.copy(endpoint = good.endpoint!!.copy(baseUrl = "http://example.test/v1")).directUseProblem())
        assertNotNull(good.copy(endpoint = good.endpoint!!.copy(defaultModel = null)).directUseProblem())
        assertNotNull(good.copy(dataHandling = null).directUseProblem())
        assertNotNull(good.copy(endpoint = good.endpoint!!.copy(auth = "required-bearer", authInput = "missing")).directUseProblem())
    }

    @Test
    fun moyaiSessionAcceptsConfiguredHttpsEndpointWithoutModelId() {
        val moyai = json.decodeFromString(AzphaltPackageSearchResponse.serializer(), search)
            .packages.single { it.id.endsWith(".moyai") }.llm!!
        assertNull(moyai.directUseProblem())
        assertEquals("endpointUrl", moyai.endpointUrlInput()!!.id)
        assertTrue(moyai.inputs.single { it.id == "workspacePassword" }.password)
        assertNull(
            moyai.copy(endpoint = moyai.endpoint!!.copy(baseUrl = "http://moyai.example"))
                .endpointUrlInput(),
        )
        assertNotNull(
            moyai.copy(endpoint = moyai.endpoint!!.copy(baseUrl = "http://moyai.example"))
                .directUseProblem(),
        )
    }

    @Test
    fun installedLanguageModelJoinsTheProviderCatalogUntilRemoved() {
        val kilo = InstalledStoreLlm(
            packageId = "com.hereliesaz.azphalt.llm.kilo",
            version = "1.0.0",
            repositoryUrl = "https://azphalt.store",
            name = "Kilo Auto (free)",
            baseUrl = "https://api.kilo.ai/api/gateway",
            defaultModel = "kilo-auto/free",
            auth = "optional-bearer",
            prompts = "may-train",
            operator = "Kilo Code",
        )
        val builtIns = ProviderCatalog.builtInEntries.size
        StoreLlmProviders.put(kilo)

        val entry = assertNotNull(ProviderCatalog.entry("azphalt:com.hereliesaz.azphalt.llm.kilo"))
        assertTrue(entry.keyOptional)
        assertTrue("may log prompts and train on them" in entry.description)
        assertEquals(builtIns + 1, ProviderCatalog.entries.size)
        // Store models come after every built-in, so planning still prefers a built-in provider.
        assertEquals(entry.id, ProviderCatalog.entries.last().id)
        // A built-in id is never shadowed.
        assertEquals("Kilo Gateway", ProviderCatalog.entry(ProviderCatalog.KILO_ID)!!.displayName)

        StoreLlmProviders.remove(kilo.packageId)
        assertNull(ProviderCatalog.entry(entry.id))
        assertEquals(builtIns, ProviderCatalog.entries.size)
    }

    @Test
    fun installsPersistAcrossReloads() {
        val settings = MapSettings()
        StoreLlmProviders.useSettings(settings)
        StoreLlmProviders.put(
            InstalledStoreLlm(
                packageId = "p", version = "1.0.0", repositoryUrl = "https://azphalt.store", name = "P",
                baseUrl = "https://example.test/v1", defaultModel = "m", auth = "required-bearer",
            ),
        )
        StoreLlmProviders.useSettings(settings)
        val reloaded = StoreLlmProviders.all().single()
        assertEquals("azphalt:p", reloaded.providerId)
        assertEquals(listOf("openai-chat"), reloaded.protocols)
        assertFalse(reloaded.keyOptional)
    }
}
