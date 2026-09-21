package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.azphalt.AzphaltDependencyStatus
import com.hereliesaz.geministrator.azphalt.AzphaltPackageImportRequest
import com.hereliesaz.geministrator.azphalt.AzphaltPackageSummary
import com.hereliesaz.geministrator.azphalt.AzphaltPreparedInstall
import com.hereliesaz.geministrator.azphalt.AzphaltPreparedModelInstall
import com.hereliesaz.geministrator.azphalt.AzphaltStoreService
import com.hereliesaz.geministrator.azphalt.AzphaltStoreSnapshot
import com.hereliesaz.geministrator.azphalt.InstalledAzphaltModelPackage
import com.hereliesaz.geministrator.azphalt.InstalledAzphaltWorkflowPackage
import com.hereliesaz.geministrator.azphalt.isModelAssetPackage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private enum class AzphaltStoreCategory(val label: String) {
    All("All"),
    Workflows("Workflows"),
    Roles("Roles"),
    Models("Models"),
}

@OptIn(ExperimentalTime::class)
@Composable
internal fun AzphaltStoreScreen(
    service: AzphaltStoreService?,
    importRequest: AzphaltPackageImportRequest? = null,
    onImportHandled: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val workflowLibraryHost = LocalWorkflowLibraryHost.current
    var snapshot by remember(service) { mutableStateOf<AzphaltStoreSnapshot?>(null) }
    var packages by remember(service) { mutableStateOf<List<AzphaltPackageSummary>>(emptyList()) }
    var query by rememberDurableStringState("azphalt-store.query")
    var categoryName by rememberDurableStringState("azphalt-store.category", AzphaltStoreCategory.All.name)
    val category = AzphaltStoreCategory.entries.firstOrNull { it.name == categoryName } ?: AzphaltStoreCategory.All
    var selectedPackageIdValue by rememberDurableStringState("azphalt-store.selected-package")
    val selectedPackageId = selectedPackageIdValue.takeIf(String::isNotBlank)
    var prepared by remember { mutableStateOf<AzphaltPreparedInstall?>(null) }
    var preparedModel by remember { mutableStateOf<AzphaltPreparedModelInstall?>(null) }
    var approvedPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allowUntrustedSigner by remember { mutableStateOf(false) }
    var allowPublisherChange by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var refreshGeneration by remember { mutableStateOf(0) }

    LaunchedEffect(service, refreshGeneration) {
        if (service == null) return@LaunchedEffect
        loading = true
        runCatching { service.snapshot() }
            .onSuccess { loaded ->
                snapshot = loaded
                packages = loaded.packages
                error = null
            }
            .onFailure { failure -> error = failure.message ?: "Azphalt Store could not be loaded." }
        loading = false
    }

    LaunchedEffect(service, query, refreshGeneration) {
        if (service == null || snapshot == null) return@LaunchedEffect
        delay(250)
        runCatching { service.search(query).packages }
            .onSuccess { packages = it }
            .onFailure { failure -> error = failure.message ?: "Azphalt Store search failed." }
    }

    LaunchedEffect(service, importRequest?.requestId) {
        val request = importRequest ?: return@LaunchedEffect
        if (service == null) {
            error = "Azphalt package import is unavailable on this host."
            onImportHandled(request.requestId)
            return@LaunchedEffect
        }
        loading = true
        error = null
        status = null
        try {
            val workflowAttempt = runCatching { service.prepareLocalInstall(request.bytes) }
            val workflowPlan = workflowAttempt.getOrNull()
            if (workflowPlan != null) {
                prepared = workflowPlan
                preparedModel = null
                selectedPackageIdValue = workflowPlan.detail.id
                approvedPermissions = service.installed()
                    .firstOrNull { it.packageId == workflowPlan.detail.id }
                    ?.approvedHostPermissions
                    ?.toSet()
                    .orEmpty()
                allowUntrustedSigner = false
                allowPublisherChange = false
                status = request.sourceLabel
                    ?.takeIf(String::isNotBlank)
                    ?.let { "Verified $it. Review trust, permissions, and dependencies before installing." }
                    ?: "Verified imported package. Review trust, permissions, and dependencies before installing."
            } else {
                val workflowFailure = workflowAttempt.exceptionOrNull()
                val modelAttempt = runCatching { service.prepareLocalModelInstall(request.bytes) }
                val modelPlan = modelAttempt.getOrNull()
                if (modelPlan != null) {
                    preparedModel = modelPlan
                    prepared = null
                    selectedPackageIdValue = modelPlan.detail.id
                    allowUntrustedSigner = false
                    allowPublisherChange = false
                    status = request.sourceLabel
                        ?.takeIf(String::isNotBlank)
                        ?.let { "Verified $it. Review model trust and license metadata before installing." }
                        ?: "Verified imported model package. Review trust and license metadata before installing."
                } else {
                    val modelFailure = modelAttempt.exceptionOrNull()
                    throw if (workflowFailure?.message.orEmpty().contains("is kind asset")) {
                        modelFailure ?: workflowFailure ?: IllegalArgumentException("Imported package is invalid")
                    } else {
                        workflowFailure ?: modelFailure ?: IllegalArgumentException("Imported package is invalid")
                    }
                }
            }
        } catch (failure: Exception) {
            error = failure.message ?: "Imported Azphalt package could not be verified."
        } finally {
            loading = false
            onImportHandled(request.requestId)
        }
    }

    val visiblePackages = packages.filter { item ->
        when (category) {
            AzphaltStoreCategory.All -> true
            AzphaltStoreCategory.Workflows -> item.kind == "workflow"
            AzphaltStoreCategory.Roles -> item.kind == "role"
            AzphaltStoreCategory.Models -> item.isModelAssetPackage()
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AZPHALT STORE", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        Text(
            snapshot?.repository?.name?.let { "$it · workflows, roles, and models for The Aive" }
                ?: "Verified workflows, roles, and models for The Aive",
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )

        if (service == null) {
            Text(
                "Store networking is unavailable on this host.",
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AzphaltPill(
                label = if (loading) "Loading" else "Refresh",
                seed = "azphalt-refresh",
                onClick = { if (!loading) refreshGeneration += 1 },
            )
            snapshot?.let { loaded ->
                val count = loaded.installed.size + loaded.installedModels.size
                Text("$count installed", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
            }
            snapshot?.updates?.count { it.updateAvailable == true }?.takeIf { it > 0 }?.let { count ->
                Text("$count update${if (count == 1) "" else "s"}", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
            }
        }

        error?.let { message ->
            AzphaltNote(seed = "azphalt-error", label = "STORE ERROR", value = message)
        }
        status?.let { message ->
            AzphaltNote(seed = "azphalt-status", label = "STORE", value = message)
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search workflows, roles, and models") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AzphaltStoreCategory.entries.forEach { item ->
                val count = when (item) {
                    AzphaltStoreCategory.All -> packages.size
                    AzphaltStoreCategory.Workflows -> packages.count { it.kind == "workflow" }
                    AzphaltStoreCategory.Roles -> packages.count { it.kind == "role" }
                    AzphaltStoreCategory.Models -> packages.count(AzphaltPackageSummary::isModelAssetPackage)
                }
                AzphaltPill(
                    label = item.label,
                    seed = "azphalt-category-${item.name}",
                    selected = item == category,
                    endCap = count.toString(),
                    onClick = { categoryName = item.name },
                )
            }
        }

        if (!loading && visiblePackages.isEmpty()) {
            Text("No Azphalt packages match this search and filter.", style = AzphaltType.body, color = Azphalt.currentGround.onPage)
        }

        val installedById = snapshot?.installed.orEmpty().associateBy(InstalledAzphaltWorkflowPackage::packageId)
        val installedModelsById = snapshot?.installedModels.orEmpty().associateBy(InstalledAzphaltModelPackage::packageId)
        val updatesById = snapshot?.updates.orEmpty().associateBy { it.id }
        val revoked = snapshot?.revocations.orEmpty().map { it.id to it.version }.toSet()

        visiblePackages.forEach { item ->
            val modelAsset = item.isModelAssetPackage()
            val installed = installedById[item.id]
            val installedModel = installedModelsById[item.id]
            val update = updatesById[item.id]
            val selected = selectedPackageId == item.id
            val isRevoked =
                (item.id to item.latest) in revoked ||
                    (installed != null && (item.id to installed.version) in revoked) ||
                    (installedModel != null && (item.id to installedModel.version) in revoked)
            AzphaltRecord(
                seed = "azphalt-package-${item.id}",
                eyebrow = if (modelAsset) {
                    listOfNotNull(
                        "Model",
                        item.types.firstOrNull()?.uppercase(),
                        item.author?.takeIf(String::isNotBlank),
                    ).joinToString(" · ")
                } else {
                    item.author?.takeIf(String::isNotBlank) ?: "Azphalt ${item.kind}"
                },
                title = item.name,
                body = item.description ?: item.id,
                endCap = when {
                    isRevoked -> "Revoked"
                    update?.updateAvailable == true -> "Update ${update.latest ?: item.latest}"
                    installed != null -> "Installed ${installed.version}"
                    installedModel != null -> "Installed ${installedModel.version}"
                    item.priceStatus != "free" -> item.priceStatus
                    modelAsset -> "Model · ${item.latest}"
                    else -> item.latest
                },
                selected = selected,
                onClick = {
                    selectedPackageIdValue = if (selected) "" else item.id
                    prepared = prepared?.takeIf { it.detail.id == item.id }
                    preparedModel = preparedModel?.takeIf { it.detail.id == item.id }
                    error = null
                    status = null
                },
                well = if (selected) {
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(item.id, style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
                            Text(
                                buildString {
                                    append(item.kind)
                                    if (modelAsset && item.types.isNotEmpty()) {
                                        append(" · ")
                                        append(item.types.joinToString())
                                    }
                                    append(" · ")
                                    append(item.priceStatus)
                                    append(" · ")
                                    append(item.byteSize?.let(::formatByteSize) ?: "size not published")
                                },
                                style = AzphaltType.body,
                                color = Azphalt.currentGround.onPage,
                            )
                            if (modelAsset) {
                                if (isRevoked) {
                                    Text(
                                        "A repository revocation applies to this model package/version. Installation is blocked.",
                                        style = AzphaltType.body,
                                        color = Azphalt.currentGround.onPage,
                                    )
                                } else {
                                    AzphaltPill(
                                        label = if (installedModel == null) "Inspect model install" else "Inspect model update",
                                        seed = "azphalt-model-prepare-${item.id}",
                                        endCap = item.latest,
                                        onClick = {
                                            if (!loading) {
                                                scope.launch {
                                                    loading = true
                                                    error = null
                                                    status = null
                                                    runCatching { service.prepareModelInstall(item.id, item.latest) }
                                                        .onSuccess { plan ->
                                                            preparedModel = plan
                                                            prepared = null
                                                            allowUntrustedSigner = false
                                                            allowPublisherChange = false
                                                        }
                                                        .onFailure { failure ->
                                                            error = failure.message ?: "Model package preparation failed."
                                                        }
                                                    loading = false
                                                }
                                            }
                                        },
                                    )
                                    if (installedModel != null) {
                                        AzphaltPill(
                                            label = "Remove model",
                                            seed = "azphalt-model-remove-${item.id}",
                                            endCap = installedModel.version,
                                            onClick = {
                                                if (!loading) {
                                                    scope.launch {
                                                        loading = true
                                                        error = null
                                                        runCatching { service.removeModel(item.id) }
                                                            .onSuccess {
                                                                status = "Removed ${item.name}."
                                                                preparedModel = null
                                                                refreshGeneration += 1
                                                            }
                                                            .onFailure { failure ->
                                                                error = failure.message ?: "Model removal failed."
                                                            }
                                                        loading = false
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            } else if (isRevoked) {
                                Text(
                                    "A repository revocation applies to this package/version. Installation is blocked until a non-revoked version is selected.",
                                    style = AzphaltType.body,
                                    color = Azphalt.currentGround.onPage,
                                )
                            } else {
                                AzphaltPill(
                                    label = if (installed == null) "Inspect install" else "Inspect update",
                                    seed = "azphalt-prepare-${item.id}",
                                    endCap = item.latest,
                                    onClick = {
                                        if (!loading) {
                                            scope.launch {
                                                loading = true
                                                error = null
                                                status = null
                                                runCatching { service.prepareInstall(item.id, item.latest) }
                                                    .onSuccess { plan ->
                                                        prepared = plan
                                                        approvedPermissions = installed?.approvedHostPermissions?.toSet().orEmpty()
                                                        allowUntrustedSigner = false
                                                        allowPublisherChange = false
                                                    }
                                                    .onFailure { failure ->
                                                        error = failure.message ?: "Package preparation failed."
                                                    }
                                                loading = false
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else null,
            )
        }

        preparedModel?.let { plan ->
            PreparedAzphaltModelInstallPanel(
                prepared = plan,
                allowUntrustedSigner = allowUntrustedSigner,
                onAllowUntrustedSignerChanged = { allowUntrustedSigner = it },
                allowPublisherChange = allowPublisherChange,
                onAllowPublisherChangeChanged = { allowPublisherChange = it },
                onInstall = {
                    scope.launch {
                        loading = true
                        error = null
                        status = null
                        runCatching {
                            service.installModel(
                                prepared = plan,
                                nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
                                allowUntrustedSigner = allowUntrustedSigner,
                                allowPublisherChange = allowPublisherChange,
                            )
                        }.onSuccess { installed ->
                            status = "Installed ${installed.packageId} ${installed.version}. Its model assets are registered for local inference."
                            preparedModel = null
                            refreshGeneration += 1
                        }.onFailure { failure ->
                            error = failure.message ?: "Model installation failed."
                        }
                        loading = false
                    }
                },
            )
        }

        prepared?.let { plan ->
            PreparedAzphaltInstall(
                prepared = plan,
                approvedPermissions = approvedPermissions,
                onPermissionChanged = { permission, granted ->
                    approvedPermissions = if (granted) approvedPermissions + permission else approvedPermissions - permission
                },
                allowUntrustedSigner = allowUntrustedSigner,
                onAllowUntrustedSignerChanged = { allowUntrustedSigner = it },
                allowPublisherChange = allowPublisherChange,
                onAllowPublisherChangeChanged = { allowPublisherChange = it },
                onPrepareDependency = { dependencyId, version ->
                    scope.launch {
                        loading = true
                        error = null
                        runCatching { service.prepareInstall(dependencyId, version) }
                            .onSuccess { dependencyPlan ->
                                prepared = dependencyPlan
                                selectedPackageIdValue = dependencyPlan.detail.id
                                approvedPermissions = snapshot?.installed
                                    .orEmpty()
                                    .firstOrNull { it.packageId == dependencyPlan.detail.id }
                                    ?.approvedHostPermissions
                                    ?.toSet()
                                    .orEmpty()
                                allowUntrustedSigner = false
                                allowPublisherChange = false
                            }
                            .onFailure { failure -> error = failure.message ?: "Dependency preparation failed." }
                        loading = false
                    }
                },
                onInstall = {
                    scope.launch {
                        loading = true
                        error = null
                        status = null
                        runCatching {
                            service.install(
                                prepared = plan,
                                approvedHostPermissions = approvedPermissions,
                                nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
                                allowUntrustedSigner = allowUntrustedSigner,
                                allowPublisherChange = allowPublisherChange,
                            )
                        }.onSuccess { installed ->
                            status = "Installed ${installed.packageId} ${installed.version}. Its workflows and roles are available immediately."
                            prepared = null
                            refreshGeneration += 1
                            workflowLibraryHost?.packagesChanged()
                        }.onFailure { failure ->
                            error = failure.message ?: "Package installation failed."
                        }
                        loading = false
                    }
                },
            )
        }
    }
}

@Composable
private fun PreparedAzphaltModelInstallPanel(
    prepared: AzphaltPreparedModelInstall,
    allowUntrustedSigner: Boolean,
    onAllowUntrustedSignerChanged: (Boolean) -> Unit,
    allowPublisherChange: Boolean,
    onAllowPublisherChangeChanged: (Boolean) -> Unit,
    onInstall: () -> Unit,
) {
    val verification = prepared.verification
    AzphaltRecord(
        seed = "azphalt-model-prepared-${prepared.detail.id}",
        eyebrow = "Verified model install",
        title = "${prepared.detail.name} ${prepared.version}",
        body = prepared.assets.joinToString(" · ") { asset ->
            buildString {
                append(asset.type.uppercase())
                asset.role?.takeIf(String::isNotBlank)?.let { append(" / ").append(it) }
                asset.byteSize?.let { append(" / ").append(formatByteSize(it)) }
            }
        },
        endCap = if (verification.trusted) {
            "Trusted"
        } else if (verification.packageContents.signed) {
            "Unknown signer"
        } else {
            "Unsigned"
        },
        selected = true,
        well = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(verification.trustReason, style = AzphaltType.body, color = Azphalt.currentGround.onPage)
                prepared.assets.forEachIndexed { index, asset ->
                    AzphaltNote(
                        seed = "azphalt-model-asset-$index",
                        label = asset.role ?: asset.type,
                        value = buildString {
                            append(asset.type)
                            if (asset.files.isNotEmpty()) append(" · ${asset.files.size} files")
                            asset.modelLicense?.let {
                                append(" · model license: ")
                                append(it.toString())
                            }
                        },
                    )
                }

                if (verification.packageContents.signed && !verification.trusted) {
                    ConfirmationRow(
                        checked = allowUntrustedSigner,
                        onCheckedChange = onAllowUntrustedSignerChanged,
                        text = "Install despite an unrecognized signing key",
                    )
                }
                if (verification.publisherChanged) {
                    ConfirmationRow(
                        checked = allowPublisherChange,
                        onCheckedChange = onAllowPublisherChangeChanged,
                        text = "Approve publisher-key change for this package id",
                    )
                }

                val trustReady =
                    !verification.packageContents.signed || verification.trusted || allowUntrustedSigner
                val publisherReady = !verification.publisherChanged || allowPublisherChange
                if (trustReady && publisherReady) {
                    AzphaltPill(
                        label = "Install verified model",
                        seed = "azphalt-model-install-${prepared.detail.id}",
                        endCap = prepared.version,
                        onClick = onInstall,
                    )
                } else {
                    Text(
                        if (!publisherReady) {
                            "Publisher-key change requires explicit approval."
                        } else {
                            "The package signature is valid, but this signer is not trusted. Explicit approval is required."
                        },
                        style = AzphaltType.body,
                        color = Azphalt.currentGround.onPage,
                    )
                }
            }
        },
    )
}

@Composable
private fun PreparedAzphaltInstall(
    prepared: AzphaltPreparedInstall,
    approvedPermissions: Set<String>,
    onPermissionChanged: (String, Boolean) -> Unit,
    allowUntrustedSigner: Boolean,
    onAllowUntrustedSignerChanged: (Boolean) -> Unit,
    allowPublisherChange: Boolean,
    onAllowPublisherChangeChanged: (Boolean) -> Unit,
    onPrepareDependency: (String, String?) -> Unit,
    onInstall: () -> Unit,
) {
    val plan = prepared.plan
    AzphaltRecord(
        seed = "azphalt-prepared-${plan.packageId}",
        eyebrow = "Verified install plan",
        title = "${plan.name} ${plan.version}",
        body = buildString {
            append("${plan.definitions.size} workflow definition")
            if (plan.definitions.size != 1) append('s')
            if (plan.roles.isNotEmpty()) append(" · ${plan.roles.size} reusable role${if (plan.roles.size == 1) "" else "s"}")
            if (plan.fragments.isNotEmpty()) append(" · ${plan.fragments.size} fragment${if (plan.fragments.size == 1) "" else "s"}")
        },
        endCap = if (plan.trusted) "Trusted" else if (plan.signed) "Unknown signer" else "Unsigned",
        selected = true,
        well = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(plan.trustReason, style = AzphaltType.body, color = Azphalt.currentGround.onPage)

                if (plan.requestedHostPermissions.isNotEmpty()) {
                    Text("HOST PERMISSION REQUESTS", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
                    plan.requestedHostPermissions.sorted().forEach { permission ->
                        val supported = permission !in plan.unsupportedHostPermissions
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = permission in approvedPermissions,
                                onCheckedChange = if (supported) {
                                    { checked -> onPermissionChanged(permission, checked) }
                                } else null,
                                enabled = supported,
                            )
                            Text(
                                if (supported) permission else "$permission · unsupported / denied",
                                style = AzphaltType.body,
                                color = Azphalt.currentGround.onPage,
                            )
                        }
                    }
                }

                if (plan.signed && !plan.trusted) {
                    ConfirmationRow(
                        checked = allowUntrustedSigner,
                        onCheckedChange = onAllowUntrustedSignerChanged,
                        text = "Install despite an unrecognized signing key",
                    )
                }
                if (plan.publisherChanged) {
                    ConfirmationRow(
                        checked = allowPublisherChange,
                        onCheckedChange = onAllowPublisherChangeChanged,
                        text = "Approve publisher-key change for this package id",
                    )
                }

                if (prepared.dependencies.isNotEmpty()) {
                    Text("DEPENDENCIES", style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
                    prepared.dependencies.forEach { dependency ->
                        AzphaltNote(
                            seed = "azphalt-dependency-${dependency.dependency.id}",
                            label = dependency.packageName ?: dependency.dependency.id,
                            value = buildString {
                                append(dependency.status.name)
                                dependency.selectedVersion?.let { append(" · $it") }
                                dependency.dependency.note?.let { append(" · $it") }
                                dependency.detail?.let { append(" · $it") }
                            },
                        )
                        if (dependency.status == AzphaltDependencyStatus.InstallRequired) {
                            AzphaltPill(
                                label = "Prepare dependency",
                                seed = "azphalt-dependency-install-${dependency.dependency.id}",
                                endCap = dependency.selectedVersion,
                                onClick = { onPrepareDependency(dependency.dependency.id, dependency.selectedVersion) },
                            )
                        }
                    }
                }

                val trustReady = !plan.signed || plan.trusted || allowUntrustedSigner
                val publisherReady = !plan.publisherChanged || allowPublisherChange
                val dependenciesReady = prepared.blockingDependencies.isEmpty()
                if (trustReady && publisherReady && dependenciesReady) {
                    AzphaltPill(
                        label = "Install verified package",
                        seed = "azphalt-install-${plan.packageId}",
                        endCap = plan.version,
                        onClick = onInstall,
                    )
                } else {
                    Text(
                        when {
                            !dependenciesReady -> "Install required dependencies before this package."
                            !publisherReady -> "Publisher-key change requires explicit approval."
                            else -> "The package signature is valid, but this signer is not trusted. Explicit approval is required."
                        },
                        style = AzphaltType.body,
                        color = Azphalt.currentGround.onPage,
                    )
                }
            }
        },
    )
}

@Composable
private fun ConfirmationRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text, style = AzphaltType.body, color = Azphalt.currentGround.onPage)
    }
}

private fun formatByteSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    else -> "${bytes / (1024 * 1024)} MiB"
}
