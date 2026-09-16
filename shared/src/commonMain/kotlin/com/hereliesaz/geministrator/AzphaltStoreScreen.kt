package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
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
import com.hereliesaz.geministrator.azphalt.AzphaltStoreService
import com.hereliesaz.geministrator.azphalt.AzphaltStoreSnapshot
import com.hereliesaz.geministrator.azphalt.InstalledAzphaltWorkflowPackage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Composable
internal fun AzphaltStoreScreen(
    service: AzphaltStoreService?,
    importRequest: AzphaltPackageImportRequest? = null,
    onImportHandled: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember(service) { mutableStateOf<AzphaltStoreSnapshot?>(null) }
    var packages by remember(service) { mutableStateOf<List<AzphaltPackageSummary>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selectedPackageId by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf<AzphaltPreparedInstall?>(null) }
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
            val plan = service.prepareLocalInstall(request.bytes)
            prepared = plan
            selectedPackageId = plan.detail.id
            approvedPermissions = service.installed()
                .firstOrNull { it.packageId == plan.detail.id }
                ?.approvedHostPermissions
                ?.toSet()
                .orEmpty()
            allowUntrustedSigner = false
            allowPublisherChange = false
            status = request.sourceLabel
                ?.takeIf(String::isNotBlank)
                ?.let { "Verified $it. Review trust, permissions, and dependencies before installing." }
                ?: "Verified imported package. Review trust, permissions, and dependencies before installing."
        } catch (failure: Exception) {
            error = failure.message ?: "Imported Azphalt package could not be verified."
        } finally {
            loading = false
            onImportHandled(request.requestId)
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
            snapshot?.repository?.name?.let { "$it · workflow packages for The Haive" }
                ?: "Verified workflow packages for The Haive",
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
            snapshot?.installed?.size?.let { count ->
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
            label = { Text("Search workflow packages") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!loading && packages.isEmpty()) {
            Text("No Haive workflow packages match this search.", style = AzphaltType.body, color = Azphalt.currentGround.onPage)
        }

        val installedById = snapshot?.installed.orEmpty().associateBy(InstalledAzphaltWorkflowPackage::packageId)
        val updatesById = snapshot?.updates.orEmpty().associateBy { it.id }
        val revoked = snapshot?.revocations.orEmpty().map { it.id to it.version }.toSet()

        packages.forEach { item ->
            val installed = installedById[item.id]
            val update = updatesById[item.id]
            val selected = selectedPackageId == item.id
            val isRevoked = (item.id to item.latest) in revoked || (installed != null && (item.id to installed.version) in revoked)
            AzphaltRecord(
                seed = "azphalt-package-${item.id}",
                eyebrow = item.author?.takeIf(String::isNotBlank) ?: "Azphalt workflow",
                title = item.name,
                body = item.description ?: item.id,
                endCap = when {
                    isRevoked -> "Revoked"
                    update?.updateAvailable == true -> "Update ${update.latest ?: item.latest}"
                    installed != null -> "Installed ${installed.version}"
                    item.priceStatus != "free" -> item.priceStatus
                    else -> item.latest
                },
                selected = selected,
                onClick = {
                    selectedPackageId = if (selected) null else item.id
                    prepared = prepared?.takeIf { it.detail.id == item.id }
                    error = null
                    status = null
                },
                well = if (selected) {
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(item.id, style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
                            Text(
                                "${item.kind} · ${item.priceStatus} · ${item.byteSize?.let(::formatByteSize) ?: "size not published"}",
                                style = AzphaltType.body,
                                color = Azphalt.currentGround.onPage,
                            )
                            if (isRevoked) {
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
                                selectedPackageId = dependencyPlan.detail.id
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
                            status = "Installed ${installed.packageId} ${installed.version}. Its workflow definitions are now available in Workflows."
                            prepared = null
                            refreshGeneration += 1
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
            if (plan.roles.isNotEmpty()) append(" · ${plan.roles.size} package-local agent${if (plan.roles.size == 1) "" else "s"}")
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
