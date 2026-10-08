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
import com.hereliesaz.geministrator.azphalt.AzphaltPreparedLlmInstall
import com.hereliesaz.geministrator.azphalt.AzphaltPreparedModelInstall
import com.hereliesaz.geministrator.azphalt.AzphaltStoreService
import com.hereliesaz.geministrator.azphalt.AzphaltStoreSnapshot
import com.hereliesaz.geministrator.azphalt.InstalledAzphaltModelPackage
import com.hereliesaz.geministrator.azphalt.InstalledAzphaltWorkflowPackage
import com.hereliesaz.geministrator.azphalt.InstalledStoreLlm
import com.hereliesaz.geministrator.azphalt.describePromptHandling
import com.hereliesaz.geministrator.azphalt.isDirectLlmPackage
import com.hereliesaz.geministrator.azphalt.isModelAssetPackage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_connect
import com.hereliesaz.geministrator.resources.common_connected
import com.hereliesaz.geministrator.resources.common_remove
import com.hereliesaz.geministrator.resources.store_files
import com.hereliesaz.geministrator.resources.store_installed
import com.hereliesaz.geministrator.resources.store_unsupported_denied
import com.hereliesaz.geministrator.resources.store_workflow_definition
import com.hereliesaz.geministrator.resources.store_workflows_roles_models_and_llms
import com.hereliesaz.geministrator.resources.store_a_key_you_add_is_kept
import com.hereliesaz.geministrator.resources.store_a_repository_revocation_applies_to_this
import com.hereliesaz.geministrator.resources.store_a_repository_revocation_applies_to_this_2
import com.hereliesaz.geministrator.resources.store_a_repository_revocation_applies_to_this_3
import com.hereliesaz.geministrator.resources.store_aive_calls_this_model_directly_the
import com.hereliesaz.geministrator.resources.store_approve_publisher_key_change_for_this
import com.hereliesaz.geministrator.resources.store_azphalt
import com.hereliesaz.geministrator.resources.store_azphalt_package_import_is_unavailable_on
import com.hereliesaz.geministrator.resources.store_azphalt_store
import com.hereliesaz.geministrator.resources.store_azphalt_store_could_not_be_loaded
import com.hereliesaz.geministrator.resources.store_azphalt_store_search_failed
import com.hereliesaz.geministrator.resources.store_change_key
import com.hereliesaz.geministrator.resources.store_dependencies
import com.hereliesaz.geministrator.resources.store_dependency_preparation_failed
import com.hereliesaz.geministrator.resources.store_host_permission_requests
import com.hereliesaz.geministrator.resources.store_imported_azphalt_package_could_not_be
import com.hereliesaz.geministrator.resources.store_inspect_install
import com.hereliesaz.geministrator.resources.store_inspect_model_install
import com.hereliesaz.geministrator.resources.store_inspect_model_update
import com.hereliesaz.geministrator.resources.store_inspect_update
import com.hereliesaz.geministrator.resources.store_install_and_connect
import com.hereliesaz.geministrator.resources.store_install_despite_an_unrecognized_signing_key
import com.hereliesaz.geministrator.resources.store_install_required_dependencies_before_this_package
import com.hereliesaz.geministrator.resources.store_install_verified_model
import com.hereliesaz.geministrator.resources.store_install_verified_package
import com.hereliesaz.geministrator.resources.store_installed_1
import com.hereliesaz.geministrator.resources.store_installed_its_model_assets
import com.hereliesaz.geministrator.resources.store_installed_its_workflows_and
import com.hereliesaz.geministrator.resources.store_installed_connect_it_to_use
import com.hereliesaz.geministrator.resources.store_key
import com.hereliesaz.geministrator.resources.store_language_model_installation_failed
import com.hereliesaz.geministrator.resources.store_language_model_package_preparation_failed
import com.hereliesaz.geministrator.resources.store_llm
import com.hereliesaz.geministrator.resources.store_loading
import com.hereliesaz.geministrator.resources.store_model
import com.hereliesaz.geministrator.resources.store_model_1
import com.hereliesaz.geministrator.resources.store_model_installation_failed
import com.hereliesaz.geministrator.resources.store_model_license
import com.hereliesaz.geministrator.resources.store_model_package_preparation_failed
import com.hereliesaz.geministrator.resources.store_model_removal_failed
import com.hereliesaz.geministrator.resources.store_needs_a_key
import com.hereliesaz.geministrator.resources.store_no_azphalt_packages_match_this_search
import com.hereliesaz.geministrator.resources.store_no_key_is_needed
import com.hereliesaz.geministrator.resources.store_no_key_needed
import com.hereliesaz.geministrator.resources.store_no_key_needed_2
import com.hereliesaz.geministrator.resources.store_package_installation_failed
import com.hereliesaz.geministrator.resources.store_package_preparation_failed
import com.hereliesaz.geministrator.resources.store_prepare_dependency
import com.hereliesaz.geministrator.resources.store_prompts
import com.hereliesaz.geministrator.resources.store_publisher_key_change_requires_explicit_approval
import com.hereliesaz.geministrator.resources.store_refresh
import com.hereliesaz.geministrator.resources.store_remove_model
import com.hereliesaz.geministrator.resources.store_removed
import com.hereliesaz.geministrator.resources.store_revoked
import com.hereliesaz.geministrator.resources.store_search_workflows_roles_models_and_llms
import com.hereliesaz.geministrator.resources.store_size_not_published
import com.hereliesaz.geministrator.resources.store_store
import com.hereliesaz.geministrator.resources.store_store_error
import com.hereliesaz.geministrator.resources.store_store_networking_is_unavailable_on_this
import com.hereliesaz.geministrator.resources.store_terms
import com.hereliesaz.geministrator.resources.store_the_package_signature_is_valid_but
import com.hereliesaz.geministrator.resources.store_trusted
import com.hereliesaz.geministrator.resources.store_unknown_signer
import com.hereliesaz.geministrator.resources.store_unsigned
import com.hereliesaz.geministrator.resources.store_update
import com.hereliesaz.geministrator.resources.store_verified_review_model_trust_and
import com.hereliesaz.geministrator.resources.store_verified_review_trust_permissions_and
import com.hereliesaz.geministrator.resources.store_verified_imported_model_package_review_trust
import com.hereliesaz.geministrator.resources.store_verified_imported_package_review_trust_permissions
import com.hereliesaz.geministrator.resources.store_verified_install_plan
import com.hereliesaz.geministrator.resources.store_verified_language_model
import com.hereliesaz.geministrator.resources.store_verified_model_install
import com.hereliesaz.geministrator.resources.store_verified_workflows_roles_models_and_llms
import com.hereliesaz.geministrator.resources.store_where_prompts_go
import com.hereliesaz.geministrator.resources.store_with_your_key
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import com.hereliesaz.geministrator.resources.store_update_count
import com.hereliesaz.geministrator.resources.store_plan_reusable_role_count
import com.hereliesaz.geministrator.resources.store_plan_fragment_count
import org.jetbrains.compose.resources.pluralStringResource

private enum class AzphaltStoreCategory(val label: String) {
    All("All"),
    Workflows("Workflows"),
    Roles("Roles"),
    Models("Models"),
    Llms("LLMs"),
}

@OptIn(ExperimentalTime::class)
@Composable
internal fun AzphaltStoreScreen(
    service: AzphaltStoreService?,
    importRequest: AzphaltPackageImportRequest? = null,
    onImportHandled: (Long) -> Unit = {},
    connectedProviderIds: Set<String> = emptySet(),
    onConnectProvider: (String) -> Unit = {},
    onDisconnectProvider: (String) -> Unit = {},
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
    var preparedLlm by remember { mutableStateOf<AzphaltPreparedLlmInstall?>(null) }
    var llmInputValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
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
            .onFailure { failure -> error = failure.message ?: getString(Res.string.store_azphalt_store_could_not_be_loaded) }
        loading = false
    }

    LaunchedEffect(service, query, refreshGeneration) {
        if (service == null || snapshot == null) return@LaunchedEffect
        delay(250)
        runCatching { service.search(query).packages }
            .onSuccess { packages = it }
            .onFailure { failure -> error = failure.message ?: getString(Res.string.store_azphalt_store_search_failed) }
    }

    LaunchedEffect(service, importRequest?.requestId) {
        val request = importRequest ?: return@LaunchedEffect
        if (service == null) {
            error = getString(Res.string.store_azphalt_package_import_is_unavailable_on)
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
                    ?.let { getString(Res.string.store_verified_review_trust_permissions_and, it) }
                    ?: getString(Res.string.store_verified_imported_package_review_trust_permissions)
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
                        ?.let { getString(Res.string.store_verified_review_model_trust_and, it) }
                        ?: getString(Res.string.store_verified_imported_model_package_review_trust)
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
            error = failure.message ?: getString(Res.string.store_imported_azphalt_package_could_not_be)
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
            AzphaltStoreCategory.Llms -> item.isDirectLlmPackage()
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.store_azphalt_store), style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        Text(
            snapshot?.repository?.name?.let { stringResource(Res.string.store_workflows_roles_models_and_llms, it) }
                ?: stringResource(Res.string.store_verified_workflows_roles_models_and_llms),
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )

        if (service == null) {
            Text(
                stringResource(Res.string.store_store_networking_is_unavailable_on_this),
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AzphaltPill(
                label = if (loading) stringResource(Res.string.store_loading) else stringResource(Res.string.store_refresh),
                seed = "azphalt-refresh",
                onClick = { if (!loading) refreshGeneration += 1 },
            )
            snapshot?.let { loaded ->
                val count = loaded.installed.size + loaded.installedModels.size + loaded.installedLlms.size
                Text(stringResource(Res.string.store_installed, count), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
            }
            snapshot?.updates?.count { it.updateAvailable == true }?.takeIf { it > 0 }?.let { count ->
                Text(pluralStringResource(Res.plurals.store_update_count, count, count), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
            }
        }

        error?.let { message ->
            AzphaltNote(seed = "azphalt-error", label = stringResource(Res.string.store_store_error), value = message)
        }
        status?.let { message ->
            AzphaltNote(seed = "azphalt-status", label = stringResource(Res.string.store_store), value = message)
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(Res.string.store_search_workflows_roles_models_and_llms)) },
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
                    AzphaltStoreCategory.Llms -> packages.count(AzphaltPackageSummary::isDirectLlmPackage)
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
            Text(stringResource(Res.string.store_no_azphalt_packages_match_this_search), style = AzphaltType.body, color = Azphalt.currentGround.onPage)
        }

        val installedById = snapshot?.installed.orEmpty().associateBy(InstalledAzphaltWorkflowPackage::packageId)
        val installedModelsById = snapshot?.installedModels.orEmpty().associateBy(InstalledAzphaltModelPackage::packageId)
        val installedLlmsById = snapshot?.installedLlms.orEmpty().associateBy(InstalledStoreLlm::packageId)
        val updatesById = snapshot?.updates.orEmpty().associateBy { it.id }
        val revoked = snapshot?.revocations.orEmpty().map { it.id to it.version }.toSet()

        visiblePackages.forEach { item ->
            if (item.isDirectLlmPackage()) {
                val installedLlm = installedLlmsById[item.id]
                val selected = selectedPackageId == item.id
                val isRevoked = (item.id to item.latest) in revoked ||
                    (installedLlm != null && (item.id to installedLlm.version) in revoked)
                StoreLlmRecord(
                    item = item,
                    installed = installedLlm,
                    connected = installedLlm != null && installedLlm.providerId in connectedProviderIds,
                    revoked = isRevoked,
                    selected = selected,
                    onSelect = {
                        selectedPackageIdValue = if (selected) "" else item.id
                        preparedLlm = preparedLlm?.takeIf { it.detail.id == item.id }
                        error = null
                        status = null
                    },
                    onPrepare = {
                        if (!loading) {
                            scope.launch {
                                loading = true
                                error = null
                                status = null
                                runCatching { service.prepareLlmInstall(item.id, item.latest) }
                                    .onSuccess { plan ->
                                        preparedLlm = plan
                                        llmInputValues = emptyMap()
                                        prepared = null
                                        preparedModel = null
                                        allowUntrustedSigner = false
                                        allowPublisherChange = false
                                    }
                                    .onFailure { failure -> error = failure.message ?: getString(Res.string.store_language_model_package_preparation_failed) }
                                loading = false
                            }
                        }
                    },
                    onConnect = { installedLlm?.let { onConnectProvider(it.providerId) } },
                    onRemove = {
                        installedLlm?.let { llm ->
                            if (llm.providerId in connectedProviderIds) onDisconnectProvider(llm.providerId)
                            service.removeLlm(llm.packageId)
                            status = "Removed ${item.name}."
                            preparedLlm = null
                            llmInputValues = emptyMap()
                            refreshGeneration += 1
                        }
                    },
                )
                return@forEach
            }
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
                        stringResource(Res.string.store_model),
                        item.types.firstOrNull()?.uppercase(),
                        item.author?.takeIf(String::isNotBlank),
                    ).joinToString(" · ")
                } else {
                    item.author?.takeIf(String::isNotBlank) ?: stringResource(Res.string.store_azphalt, item.kind)
                },
                title = item.name,
                body = item.description ?: item.id,
                endCap = when {
                    isRevoked -> stringResource(Res.string.store_revoked)
                    update?.updateAvailable == true -> stringResource(Res.string.store_update, update.latest ?: item.latest)
                    installed != null -> stringResource(Res.string.store_installed_1, installed.version)
                    installedModel != null -> stringResource(Res.string.store_installed_1, installedModel.version)
                    item.priceStatus != "free" -> item.priceStatus
                    modelAsset -> stringResource(Res.string.store_model_1, item.latest)
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
                                    append(item.byteSize?.let(::formatByteSize) ?: stringResource(Res.string.store_size_not_published))
                                },
                                style = AzphaltType.body,
                                color = Azphalt.currentGround.onPage,
                            )
                            if (modelAsset) {
                                if (isRevoked) {
                                    Text(
                                        stringResource(Res.string.store_a_repository_revocation_applies_to_this),
                                        style = AzphaltType.body,
                                        color = Azphalt.currentGround.onPage,
                                    )
                                } else {
                                    AzphaltPill(
                                        label = if (installedModel == null) stringResource(Res.string.store_inspect_model_install) else stringResource(Res.string.store_inspect_model_update),
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
                                                            error = failure.message ?: getString(Res.string.store_model_package_preparation_failed)
                                                        }
                                                    loading = false
                                                }
                                            }
                                        },
                                    )
                                    if (installedModel != null) {
                                        AzphaltPill(
                                            label = stringResource(Res.string.store_remove_model),
                                            seed = "azphalt-model-remove-${item.id}",
                                            endCap = installedModel.version,
                                            onClick = {
                                                if (!loading) {
                                                    scope.launch {
                                                        loading = true
                                                        error = null
                                                        runCatching { service.removeModel(item.id) }
                                                            .onSuccess {
                                                                status = getString(Res.string.store_removed, item.name)
                                                                preparedModel = null
                                                                refreshGeneration += 1
                                                            }
                                                            .onFailure { failure ->
                                                                error = failure.message ?: getString(Res.string.store_model_removal_failed)
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
                                    stringResource(Res.string.store_a_repository_revocation_applies_to_this_2),
                                    style = AzphaltType.body,
                                    color = Azphalt.currentGround.onPage,
                                )
                            } else {
                                AzphaltPill(
                                    label = if (installed == null) stringResource(Res.string.store_inspect_install) else stringResource(Res.string.store_inspect_update),
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
                                                        error = failure.message ?: getString(Res.string.store_package_preparation_failed)
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
                                inputValues = llmInputValues,
                                allowUntrustedSigner = allowUntrustedSigner,
                                allowPublisherChange = allowPublisherChange,
                            )
                        }.onSuccess { installed ->
                            status = getString(Res.string.store_installed_its_model_assets, installed.packageId, installed.version)
                            preparedModel = null
                            refreshGeneration += 1
                        }.onFailure { failure ->
                            error = failure.message ?: getString(Res.string.store_model_installation_failed)
                        }
                        loading = false
                    }
                },
            )
        }

        preparedLlm?.let { plan ->
            PreparedStoreLlmPanel(
                prepared = plan,
                inputValues = llmInputValues,
                onInputChanged = { id, value -> llmInputValues = llmInputValues + (id to value) },
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
                            service.installLlm(
                                prepared = plan,
                                nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
                                allowUntrustedSigner = allowUntrustedSigner,
                                allowPublisherChange = allowPublisherChange,
                            )
                        }.onSuccess { installed ->
                            status = getString(Res.string.store_installed_connect_it_to_use, installed.name) +
                                if (installed.keyOptional) getString(Res.string.store_no_key_is_needed) else getString(Res.string.store_with_your_key)
                            preparedLlm = null
                            llmInputValues = emptyMap()
                            refreshGeneration += 1
                            onConnectProvider(installed.providerId)
                        }.onFailure { failure ->
                            error = failure.message ?: getString(Res.string.store_language_model_installation_failed)
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
                            .onFailure { failure -> error = failure.message ?: getString(Res.string.store_dependency_preparation_failed) }
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
                            status = getString(Res.string.store_installed_its_workflows_and, installed.packageId, installed.version)
                            prepared = null
                            refreshGeneration += 1
                            workflowLibraryHost?.packagesChanged()
                        }.onFailure { failure ->
                            error = failure.message ?: getString(Res.string.store_package_installation_failed)
                        }
                        loading = false
                    }
                },
            )
        }
    }
}

@Composable
private fun StoreLlmRecord(
    item: AzphaltPackageSummary,
    installed: InstalledStoreLlm?,
    connected: Boolean,
    revoked: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onPrepare: () -> Unit,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
) {
    val llm = item.llm
    val endpoint = llm?.endpoint
    val keyless = endpoint?.auth != "required-bearer"
    AzphaltRecord(
        seed = "azphalt-llm-${item.id}",
        eyebrow = listOfNotNull(
            stringResource(Res.string.store_llm),
            if (keyless) stringResource(Res.string.store_no_key_needed) else stringResource(Res.string.store_needs_a_key),
            llm?.dataHandling?.operator?.takeIf(String::isNotBlank),
        ).joinToString(" · "),
        title = item.name,
        body = item.description ?: item.id,
        endCap = when {
            revoked -> stringResource(Res.string.store_revoked)
            connected -> stringResource(Res.string.common_connected)
            installed != null -> stringResource(Res.string.store_installed_1, installed.version)
            else -> item.latest
        },
        selected = selected,
        onClick = onSelect,
        well = if (selected) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(item.id, style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
                    endpoint?.let {
                        Text(
                            "${it.defaultModel} at ${it.baseUrl}. Aive calls it directly; nothing runs on your GitHub.",
                            style = AzphaltType.body,
                            color = Azphalt.currentGround.onPage,
                        )
                    }
                    llm?.dataHandling?.let { handling ->
                        AzphaltNote(seed = "azphalt-llm-data-${item.id}", label = stringResource(Res.string.store_prompts), value = describePromptHandling(handling.operator, handling.prompts, handling.modelPinned))
                    }
                    when {
                        revoked -> Text(
                            stringResource(Res.string.store_a_repository_revocation_applies_to_this_3),
                            style = AzphaltType.body,
                            color = Azphalt.currentGround.onPage,
                        )
                        installed == null -> AzphaltPill(
                            label = stringResource(Res.string.store_inspect_install),
                            seed = "azphalt-llm-prepare-${item.id}",
                            endCap = item.latest,
                            onClick = onPrepare,
                        )
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AzphaltPill(
                                label = if (connected) stringResource(Res.string.store_change_key) else stringResource(Res.string.common_connect),
                                seed = "azphalt-llm-connect-${item.id}",
                                onClick = onConnect,
                            )
                            AzphaltPill(
                                label = stringResource(Res.string.common_remove),
                                seed = "azphalt-llm-remove-${item.id}",
                                endCap = installed.version,
                                onClick = onRemove,
                            )
                        }
                    }
                }
            }
        } else null,
    )
}

@Composable
private fun PreparedStoreLlmPanel(
    prepared: AzphaltPreparedLlmInstall,
    inputValues: Map<String, String>,
    onInputChanged: (String, String) -> Unit,
    allowUntrustedSigner: Boolean,
    onAllowUntrustedSignerChanged: (Boolean) -> Unit,
    allowPublisherChange: Boolean,
    onAllowPublisherChangeChanged: (Boolean) -> Unit,
    onInstall: () -> Unit,
) {
    val verification = prepared.verification
    val endpoint = prepared.endpoint
    val handling = prepared.llm.dataHandling
    val configurationInputs = prepared.llm.inputs.filter { !it.password && it.id != endpoint.authInput }
    AzphaltRecord(
        seed = "azphalt-llm-prepared-${prepared.detail.id}",
        eyebrow = stringResource(Res.string.store_verified_language_model),
        title = "${prepared.detail.name} ${prepared.version}",
        body = listOfNotNull(
            endpoint.defaultModel?.takeIf(String::isNotBlank),
            endpoint.baseUrl?.takeIf(String::isNotBlank),
        ).joinToString(" at ").ifBlank { endpoint.protocols.joinToString() },
        endCap = if (verification.trusted) {
            stringResource(Res.string.store_trusted)
        } else if (verification.packageContents.signed) {
            stringResource(Res.string.store_unknown_signer)
        } else {
            stringResource(Res.string.store_unsigned)
        },
        selected = true,
        well = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(verification.trustReason, style = AzphaltType.body, color = Azphalt.currentGround.onPage)
                handling?.let {
                    AzphaltNote(
                        seed = "azphalt-llm-prepared-data",
                        label = stringResource(Res.string.store_where_prompts_go),
                        value = describePromptHandling(it.operator, it.prompts, it.modelPinned) +
                            (it.terms?.takeIf(String::isNotBlank)?.let { terms -> stringResource(Res.string.store_terms, terms) } ?: ""),
                    )
                }
                configurationInputs.forEach { input ->
                    OutlinedTextField(
                        value = inputValues[input.id].orEmpty(),
                        onValueChange = { onInputChanged(input.id, it) },
                        label = { Text(input.description ?: input.id) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AzphaltNote(
                    seed = "azphalt-llm-prepared-key",
                    label = stringResource(Res.string.store_key),
                    value = when (endpoint.auth) {
                        "required-bearer" -> stringResource(Res.string.store_needs_a_key) + (prepared.keyInput?.description?.let { ": $it" } ?: ".")
                        "optional-bearer" -> stringResource(Res.string.store_no_key_needed) + (prepared.keyInput?.description?.let { "; $it" } ?: ".")
                        else -> stringResource(Res.string.store_no_key_needed_2) +
                            (prepared.connectionCredentialInput?.description?.let { " $it" } ?: "")
                    } + stringResource(Res.string.store_a_key_you_add_is_kept),
                )
                Text(
                    stringResource(Res.string.store_aive_calls_this_model_directly_the),
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
                if (verification.packageContents.signed && !verification.trusted) {
                    ConfirmationRow(
                        checked = allowUntrustedSigner,
                        onCheckedChange = onAllowUntrustedSignerChanged,
                        text = stringResource(Res.string.store_install_despite_an_unrecognized_signing_key),
                    )
                }
                if (verification.publisherChanged) {
                    ConfirmationRow(
                        checked = allowPublisherChange,
                        onCheckedChange = onAllowPublisherChangeChanged,
                        text = stringResource(Res.string.store_approve_publisher_key_change_for_this),
                    )
                }
                val trustReady = !verification.packageContents.signed || verification.trusted || allowUntrustedSigner
                val publisherReady = !verification.publisherChanged || allowPublisherChange
                val inputsReady = configurationInputs.all { it.optional || inputValues[it.id]?.isNotBlank() == true }
                if (trustReady && publisherReady && inputsReady) {
                    AzphaltPill(
                        label = stringResource(Res.string.store_install_and_connect),
                        seed = "azphalt-llm-install-${prepared.detail.id}",
                        endCap = prepared.version,
                        onClick = onInstall,
                    )
                } else {
                    Text(
                        if (!publisherReady) {
                            stringResource(Res.string.store_publisher_key_change_requires_explicit_approval)
                        } else {
                            stringResource(Res.string.store_the_package_signature_is_valid_but)
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
        eyebrow = stringResource(Res.string.store_verified_model_install),
        title = "${prepared.detail.name} ${prepared.version}",
        body = prepared.assets.joinToString(" · ") { asset ->
            buildString {
                append(asset.type.uppercase())
                asset.role?.takeIf(String::isNotBlank)?.let { append(" / ").append(it) }
                asset.byteSize?.let { append(" / ").append(formatByteSize(it)) }
            }
        },
        endCap = if (verification.trusted) {
            stringResource(Res.string.store_trusted)
        } else if (verification.packageContents.signed) {
            stringResource(Res.string.store_unknown_signer)
        } else {
            stringResource(Res.string.store_unsigned)
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
                            if (asset.files.isNotEmpty()) append(stringResource(Res.string.store_files, asset.files.size))
                            asset.modelLicense?.let {
                                append(stringResource(Res.string.store_model_license))
                                append(it.toString())
                            }
                        },
                    )
                }

                if (verification.packageContents.signed && !verification.trusted) {
                    ConfirmationRow(
                        checked = allowUntrustedSigner,
                        onCheckedChange = onAllowUntrustedSignerChanged,
                        text = stringResource(Res.string.store_install_despite_an_unrecognized_signing_key),
                    )
                }
                if (verification.publisherChanged) {
                    ConfirmationRow(
                        checked = allowPublisherChange,
                        onCheckedChange = onAllowPublisherChangeChanged,
                        text = stringResource(Res.string.store_approve_publisher_key_change_for_this),
                    )
                }

                val trustReady =
                    !verification.packageContents.signed || verification.trusted || allowUntrustedSigner
                val publisherReady = !verification.publisherChanged || allowPublisherChange
                if (trustReady && publisherReady) {
                    AzphaltPill(
                        label = stringResource(Res.string.store_install_verified_model),
                        seed = "azphalt-model-install-${prepared.detail.id}",
                        endCap = prepared.version,
                        onClick = onInstall,
                    )
                } else {
                    Text(
                        if (!publisherReady) {
                            stringResource(Res.string.store_publisher_key_change_requires_explicit_approval)
                        } else {
                            stringResource(Res.string.store_the_package_signature_is_valid_but)
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
        eyebrow = stringResource(Res.string.store_verified_install_plan),
        title = "${plan.name} ${plan.version}",
        body = buildString {
            append(stringResource(Res.string.store_workflow_definition, plan.definitions.size))
            if (plan.definitions.size != 1) append('s')
            if (plan.roles.isNotEmpty()) append(pluralStringResource(Res.plurals.store_plan_reusable_role_count, plan.roles.size, plan.roles.size))
            if (plan.fragments.isNotEmpty()) append(pluralStringResource(Res.plurals.store_plan_fragment_count, plan.fragments.size, plan.fragments.size))
        },
        endCap = if (plan.trusted) stringResource(Res.string.store_trusted) else if (plan.signed) stringResource(Res.string.store_unknown_signer) else stringResource(Res.string.store_unsigned),
        selected = true,
        well = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(plan.trustReason, style = AzphaltType.body, color = Azphalt.currentGround.onPage)

                if (plan.requestedHostPermissions.isNotEmpty()) {
                    Text(stringResource(Res.string.store_host_permission_requests), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
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
                                if (supported) permission else stringResource(Res.string.store_unsupported_denied, permission),
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
                        text = stringResource(Res.string.store_install_despite_an_unrecognized_signing_key),
                    )
                }
                if (plan.publisherChanged) {
                    ConfirmationRow(
                        checked = allowPublisherChange,
                        onCheckedChange = onAllowPublisherChangeChanged,
                        text = stringResource(Res.string.store_approve_publisher_key_change_for_this),
                    )
                }

                if (prepared.dependencies.isNotEmpty()) {
                    Text(stringResource(Res.string.store_dependencies), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
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
                                label = stringResource(Res.string.store_prepare_dependency),
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
                        label = stringResource(Res.string.store_install_verified_package),
                        seed = "azphalt-install-${plan.packageId}",
                        endCap = plan.version,
                        onClick = onInstall,
                    )
                } else {
                    Text(
                        when {
                            !dependenciesReady -> stringResource(Res.string.store_install_required_dependencies_before_this_package)
                            !publisherReady -> stringResource(Res.string.store_publisher_key_change_requires_explicit_approval)
                            else -> stringResource(Res.string.store_the_package_signature_is_valid_but)
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
