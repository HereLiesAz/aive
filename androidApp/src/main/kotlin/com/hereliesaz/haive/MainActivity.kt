package com.hereliesaz.haive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hereliesaz.geministrator.App
import com.hereliesaz.geministrator.InitialProviderSetup
import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.ProviderCredentialSetup
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.jules.JulesApiKeyProvider
import com.hereliesaz.geministrator.providers.jules.JulesProvider
import com.hereliesaz.geministrator.providers.jules.JulesRestApi
import com.hereliesaz.geministrator.providers.llm.AnthropicProvider
import com.hereliesaz.geministrator.providers.llm.GeminiProvider
import com.hereliesaz.geministrator.providers.llm.LlmApiKeyProvider
import com.hereliesaz.geministrator.providers.llm.OpenAiProvider
import com.hereliesaz.geministrator.providers.llm.XaiProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val credentialStore = AndroidProviderCredentialStore(this)
        val initialCredentials = credentialStore.readAll()

        setContent {
            var credentials by remember { mutableStateOf(initialCredentials) }
            var setupComplete by remember { mutableStateOf(initialCredentials.isNotEmpty()) }
            var configuringProviderId by remember { mutableStateOf<String?>(null) }
            val providers = remember(credentials) { configuredAndroidProviders(credentials) }

            val providerId = configuringProviderId
            when {
                providerId != null -> ProviderCredentialSetup(
                    providerId = providerId,
                    onSave = { key ->
                        credentialStore.write(providerId, key)
                        credentials = credentialStore.readAll()
                        configuringProviderId = null
                        setupComplete = true
                    },
                    onCancel = {
                        configuringProviderId = null
                    },
                )

                !setupComplete -> InitialProviderSetup(
                    configuredProviderIds = credentials.keys,
                    onConfigure = { configuringProviderId = it },
                    onContinue = { setupComplete = true },
                )

                else -> App(
                    providers = providers,
                    onReconfigureProvider = { configuringProviderId = it },
                )
            }
        }
    }
}

internal fun configuredAndroidProviders(credentials: Map<String, String>): List<AgentProvider> = buildList {
    credentials.cleanKey(ProviderCatalog.JULES_ID)?.let { key ->
        add(
            JulesProvider(
                JulesRestApi(
                    JulesApiKeyProvider { key },
                ),
            ),
        )
    }
    credentials.cleanKey(ProviderCatalog.OPENAI_ID)?.let { key ->
        add(OpenAiProvider(LlmApiKeyProvider { key }))
    }
    credentials.cleanKey(ProviderCatalog.ANTHROPIC_ID)?.let { key ->
        add(AnthropicProvider(LlmApiKeyProvider { key }))
    }
    credentials.cleanKey(ProviderCatalog.GEMINI_ID)?.let { key ->
        add(GeminiProvider(LlmApiKeyProvider { key }))
    }
    credentials.cleanKey(ProviderCatalog.XAI_ID)?.let { key ->
        add(XaiProvider(LlmApiKeyProvider { key }))
    }
}

internal fun configuredAndroidProviders(julesApiKey: String?): List<AgentProvider> =
    configuredAndroidProviders(
        julesApiKey
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { mapOf(ProviderCatalog.JULES_ID to it) }
            .orEmpty(),
    )

private fun Map<String, String>.cleanKey(providerId: String): String? =
    this[providerId]?.trim()?.takeIf(String::isNotEmpty)
