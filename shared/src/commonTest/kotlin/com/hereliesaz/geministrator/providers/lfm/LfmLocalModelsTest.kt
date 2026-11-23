package com.hereliesaz.geministrator.providers.lfm

import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.azphalt.AzphaltAssetEntry
import com.russhwolf.settings.MapSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LfmLocalModelsTest {
    private fun asset(params: String, requirements: String? = null) = AzphaltAssetEntry(
        type = "model",
        params = Json.parseToJsonElement(params),
        requirements = requirements?.let(Json::parseToJsonElement),
    )

    @Test
    fun theProfileIsReadFromAnLfm3Package() {
        val profile = LfmModelProfile.fromAsset(
            asset("""{"chatFormat":"lfm3","toolFormat":"lfm3-xml","thinking":false,"contextTokens":4096,"imageInput":true}""", """{"minRamMB":4096}"""),
        )!!
        assertEquals(4096, profile.contextTokens)
        assertFalse(profile.thinking)
        assertEquals(4096, profile.minRamMegabytes)
        assertTrue(profile.imageInput)
    }

    @Test
    fun aModelInAnotherFormatIsNotAnLfm3Mind() {
        assertNull(LfmModelProfile.fromAsset(asset("""{"chatFormat":"chatml","toolFormat":"lfm3-xml"}""")))
        assertNull(LfmModelProfile.fromAsset(asset("""{"chatFormat":"lfm3"}""")))
        assertNull(LfmModelProfile.fromAsset(AzphaltAssetEntry(type = "model")))
    }

    @Test
    fun installedModelsPersistAndAppearAsProviders() {
        InstalledLocalLlms.useSettings(MapSettings())
        assertTrue(InstalledLocalLlms.all().isEmpty())
        InstalledLocalLlms.put(InstalledLocalLlm("com.example.lfm", "LFM3 1.7B", "/models/lfm.litertlm"))
        val id = "local:com.example.lfm"
        assertEquals("/models/lfm.litertlm", InstalledLocalLlms.byProviderId(id)!!.artifactPath)
        val entry = assertNotNull(ProviderCatalog.entry(id))
        assertTrue(entry.keyOptional)
        assertTrue(ProviderCatalog.isLocalModel(id))
        InstalledLocalLlms.remove("com.example.lfm")
        assertNull(ProviderCatalog.entry(id))
        InstalledLocalLlms.useSettings(MapSettings())
    }
}
