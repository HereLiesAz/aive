package com.hereliesaz.geministrator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.jules.JulesApiKeyProvider
import com.hereliesaz.geministrator.providers.jules.JulesProvider
import com.hereliesaz.geministrator.providers.jules.JulesRestApi
import com.hereliesaz.geministrator.providers.llm.AnthropicProvider
import com.hereliesaz.geministrator.providers.llm.GeminiProvider
import com.hereliesaz.geministrator.providers.llm.LlmApiKeyProvider
import com.hereliesaz.geministrator.providers.llm.OpenAiProvider
import com.hereliesaz.geministrator.providers.llm.XaiProvider
import com.hereliesaz.geministrator.workflow.GitHubActionsExecutorIntegration
import com.hereliesaz.geministrator.workflow.GitHubRestActionsClient
import com.hereliesaz.geministrator.workflow.GitHubTokenProvider
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

fun main() {
    val credentialStore = DesktopProviderCredentialStore()
    val initialCredentials = readDesktopProviderCredentials(credentialStore)

    val githubToken = System.getenv("GITHUB_TOKEN")?.takeIf(String::isNotBlank)
    val githubClient = githubToken?.let { HttpClient(CIO) }
    val executorIntegrations = if (githubToken != null && githubClient != null) {
        TaskExecutorIntegrationRegistry(
            listOf(
                GitHubActionsExecutorIntegration(
                    GitHubRestActionsClient(
                        httpClient = githubClient,
                        tokenProvider = GitHubTokenProvider { githubToken },
                    ),
                ),
            ),
        )
    } else {
        TaskExecutorIntegrationRegistry.Empty
    }

    try {
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "The Haive",
                state = rememberWindowState(width = 1180.dp, height = 760.dp),
            ) {
                var credentials by remember { mutableStateOf(initialCredentials) }
                var setupComplete by remember { mutableStateOf(initialCredentials.isNotEmpty()) }
                var configuringProviderId by remember { mutableStateOf<String?>(null) }
                val providers = remember(credentials) { configuredDesktopProviders(credentials) }

                val providerId = configuringProviderId
                when {
                    providerId != null -> ProviderCredentialSetup(
                        providerId = providerId,
                        onSave = { key ->
                            credentialStore.write(providerId, key)
                            credentials = readDesktopProviderCredentials(credentialStore)
                            configuringProviderId = null
                            setupComplete = true
                        },
                        onCancel = { configuringProviderId = null },
                    )

                    !setupComplete -> InitialProviderSetup(
                        configuredProviderIds = credentials.keys,
                        onConfigure = { configuringProviderId = it },
                        onContinue = { setupComplete = true },
                    )

                    else -> App(
                        providers = providers,
                        executorIntegrations = executorIntegrations,
                        onReconfigureProvider = { configuringProviderId = it },
                        onDisconnectProvider = { providerId ->
                            credentialStore.clear(providerId)
                            credentials = readDesktopProviderCredentials(credentialStore)
                        },
                    )
                }
            }
        }
    } finally {
        githubClient?.close()
    }
}

internal fun configuredDesktopProviders(credentials: Map<String, String>): List<AgentProvider> = buildList {
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

private fun readDesktopProviderCredentials(store: DesktopProviderCredentialStore): Map<String, String> = buildMap {
    environmentProviderCredentials().forEach { (providerId, key) -> put(providerId, key) }
    store.readAll().forEach { (providerId, key) -> put(providerId, key) }
}

private fun environmentProviderCredentials(): Map<String, String> = buildMap {
    listOf(
        ProviderCatalog.JULES_ID to "JULES_API_KEY",
        ProviderCatalog.OPENAI_ID to "OPENAI_API_KEY",
        ProviderCatalog.ANTHROPIC_ID to "ANTHROPIC_API_KEY",
        ProviderCatalog.GEMINI_ID to "GEMINI_API_KEY",
        ProviderCatalog.XAI_ID to "XAI_API_KEY",
    ).forEach { (providerId, environmentName) ->
        System.getenv(environmentName)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { put(providerId, it) }
    }
}

private fun Map<String, String>.cleanKey(providerId: String): String? =
    this[providerId]?.trim()?.takeIf(String::isNotEmpty)
