package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.inference.LocalModelArtifactDescriptor
import com.hereliesaz.geministrator.inference.LocalModelArtifactKind
import com.hereliesaz.geministrator.inference.LocalModelLibrary
import com.hereliesaz.geministrator.inference.LocalModelLoadPlan
import com.hereliesaz.geministrator.inference.LocalModelRuntimeCapabilities
import com.hereliesaz.geministrator.inference.LocalModelSpecialistDescriptor

/** The one specialist id the app-integration notebook releases (`tools/app_integration_training`). */
const val APP_INTEGRATION_SPECIALIST_ID: String = "app-integration:assistant"

/**
 * The released app-integration LoRA adapter and the shared base it was trained on.
 *
 * It is a catalog entry of its own ([OrchestrationSpecialistCatalog.appIntegration]), not an orchestration
 * specialist: the on-device small-task generators feed its tensors to the shared base, and no orchestration
 * role reads it. [sharedBaseVariants] and [adapter] are the same descriptors the orchestration specialists use,
 * so the existing installers fetch and verify them (SHA-256, and size when [adapterSizeBytes] is listed).
 */
data class AppIntegrationRelease(
    val sharedBaseVariants: List<LocalModelArtifactDescriptor>,
    val adapter: LocalModelArtifactDescriptor,
    /** The adapter file's size as released, when the catalog lists it. */
    val adapterSizeBytes: Long? = null,
    /** The release gates in one line (worst family per gate), when the catalog carries scores. */
    val gateSummary: String? = null,
) {
    private val descriptor = LocalModelSpecialistDescriptor(
        specialistId = APP_INTEGRATION_SPECIALIST_ID,
        sharedBaseVariants = sharedBaseVariants,
        adapter = adapter,
    )

    init {
        require(adapter.kind == LocalModelArtifactKind.Adapter) { "the app-integration adapter must be an adapter artifact" }
        require(adapterSizeBytes == null || adapterSizeBytes > 0L) { "adapterSizeBytes must be positive" }
    }

    /** The shared base plus adapter this runtime would load, or null when it cannot run adapters or has no compatible base. */
    fun plan(runtime: LocalModelRuntimeCapabilities): LocalModelLoadPlan.SharedBaseAdapter? =
        runCatching { LocalModelLibrary(listOf(descriptor)).plan(APP_INTEGRATION_SPECIALIST_ID, runtime) }
            .getOrNull() as? LocalModelLoadPlan.SharedBaseAdapter

    /** The artifacts to install for [runtime], base first; empty when [plan] is null. */
    fun artifacts(runtime: LocalModelRuntimeCapabilities): List<LocalModelArtifactDescriptor> =
        plan(runtime)?.let { listOf(it.base, it.adapter) }.orEmpty()
}

/** Where the app-integration adapter stands on this device, for the Local orchestration line. */
sealed interface AppIntegrationInstallState {
    /** Nothing is released, or nothing released fits this runtime. */
    data object NotReleased : AppIntegrationInstallState

    /** Released, none of it installed. */
    data object NotInstalled : AppIntegrationInstallState

    /** Released and part installed: [missing] is the asset still to fetch. */
    data class Partial(val missing: String) : AppIntegrationInstallState

    /** The base and the adapter are both installed and verified. */
    data object Installed : AppIntegrationInstallState

    companion object {
        fun of(
            release: AppIntegrationRelease?,
            runtime: LocalModelRuntimeCapabilities,
            installed: (LocalModelArtifactDescriptor) -> Boolean,
        ): AppIntegrationInstallState {
            val artifacts = release?.artifacts(runtime).orEmpty()
            if (artifacts.isEmpty()) return NotReleased
            val missing = artifacts.filterNot(installed)
            return when {
                missing.isEmpty() -> Installed
                missing.size == artifacts.size -> NotInstalled
                else -> Partial(missing.first().assetName)
            }
        }
    }
}
