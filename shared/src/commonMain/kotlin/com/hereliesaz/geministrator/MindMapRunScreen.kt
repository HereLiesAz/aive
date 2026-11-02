package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.domain.locatorInput
import com.hereliesaz.geministrator.domain.parseRepositoryRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_dismiss
import com.hereliesaz.geministrator.resources.overview_has_no_persisted_workflow_run
import com.hereliesaz.geministrator.resources.overview_a_workflow_gate_is_waiting_for
import com.hereliesaz.geministrator.resources.overview_aive_automatically_reopens_the_last_saved
import com.hereliesaz.geministrator.resources.overview_blocked
import com.hereliesaz.geministrator.resources.overview_branch
import com.hereliesaz.geministrator.resources.overview_branch_1
import com.hereliesaz.geministrator.resources.overview_choose_git_folder
import com.hereliesaz.geministrator.resources.overview_clear
import com.hereliesaz.geministrator.resources.overview_could_not_open_saved_project
import com.hereliesaz.geministrator.resources.overview_create_a_project_and_define_its
import com.hereliesaz.geministrator.resources.overview_create_project_start_run
import com.hereliesaz.geministrator.resources.overview_decision_required
import com.hereliesaz.geministrator.resources.overview_default_branch_optional
import com.hereliesaz.geministrator.resources.overview_github_url_or_owner_repository
import com.hereliesaz.geministrator.resources.overview_gitlab_url_or_group_repository
import com.hereliesaz.geministrator.resources.overview_invalid
import com.hereliesaz.geministrator.resources.overview_jump_to_active
import com.hereliesaz.geministrator.resources.overview_doing_now
import androidx.compose.runtime.collectAsState
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.workflow.SessionActivityFeeds
import com.hereliesaz.geministrator.workflow.SessionActivityKey
import com.hereliesaz.geministrator.workflow.doingNowText
import com.hereliesaz.geministrator.resources.overview_link_repository
import com.hereliesaz.geministrator.resources.overview_loading_runtime
import com.hereliesaz.geministrator.resources.overview_local_git_folder_path
import com.hereliesaz.geministrator.resources.overview_no_active_run
import com.hereliesaz.geministrator.resources.overview_no_decision_required
import com.hereliesaz.geministrator.resources.overview_no_project
import com.hereliesaz.geministrator.resources.overview_objective
import com.hereliesaz.geministrator.resources.overview_open_a_different_saved_project
import com.hereliesaz.geministrator.resources.overview_or_create_a_new_project
import com.hereliesaz.geministrator.resources.overview_owner_attention
import com.hereliesaz.geministrator.resources.overview_paste_the_repository_url_or_shorthand
import com.hereliesaz.geministrator.resources.overview_project
import com.hereliesaz.geministrator.resources.overview_project_load_failed
import com.hereliesaz.geministrator.resources.overview_project_name
import com.hereliesaz.geministrator.resources.overview_provider_settings
import com.hereliesaz.geministrator.resources.overview_reading_persisted_workflow_state
import com.hereliesaz.geministrator.resources.overview_repository_search_unavailable
import com.hereliesaz.geministrator.resources.overview_required
import com.hereliesaz.geministrator.resources.overview_resume_failed
import com.hereliesaz.geministrator.resources.overview_retry_runtime
import com.hereliesaz.geministrator.resources.overview_runtime_disconnected
import com.hereliesaz.geministrator.resources.overview_runtime_is_advancing_without_human_intervention
import com.hereliesaz.geministrator.resources.overview_search_github_repositories_or_paste_url
import com.hereliesaz.geministrator.resources.overview_search_gitlab_repositories_or_paste_url
import com.hereliesaz.geministrator.resources.overview_start_run
import com.hereliesaz.geministrator.resources.overview_start_typing_to_search_your_own
import com.hereliesaz.geministrator.resources.overview_swarm_activity
import com.hereliesaz.geministrator.resources.overview_swarm_execution
import com.hereliesaz.geministrator.resources.overview_this_runtime_can_link_the_selected
import com.hereliesaz.geministrator.resources.overview_workflow_definition_error
import com.hereliesaz.geministrator.resources.overview_workflow_errors
import com.hereliesaz.geministrator.resources.overview_yours
import com.hereliesaz.geministrator.resources.confirm_cancel
import com.hereliesaz.geministrator.resources.confirm_recover_action
import com.hereliesaz.geministrator.resources.confirm_recover_body
import com.hereliesaz.geministrator.resources.confirm_recover_title
import com.hereliesaz.geministrator.resources.overview_enter_name
import com.hereliesaz.geministrator.resources.overview_enter_name_and_objective
import com.hereliesaz.geministrator.resources.overview_enter_objective
import com.hereliesaz.geministrator.resources.overview_invalid_repository_location
import com.hereliesaz.geministrator.resources.overview_options
import com.hereliesaz.geministrator.resources.first_hive_options
import com.hereliesaz.geministrator.resources.first_hive_connect_hint
import com.hereliesaz.geministrator.resources.first_hive_connect
import com.hereliesaz.geministrator.resources.first_hive_feed
import com.hereliesaz.geministrator.resources.credential_keyless_hint
import com.hereliesaz.geministrator.resources.feed_gate_own_key
import com.hereliesaz.geministrator.resources.feed_gate_repository
import com.hereliesaz.geministrator.resources.feed_gate_use_free
import com.hereliesaz.geministrator.resources.feed_gate_unsupported_repository
import com.hereliesaz.geministrator.resources.first_hive_objective
import com.hereliesaz.geministrator.resources.hive_what_it_made
import com.hereliesaz.geministrator.resources.first_hive_hint
import com.hereliesaz.geministrator.resources.first_hive
import com.hereliesaz.geministrator.resources.overview_recover_delete_all_data
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MindMapRunScreen(
    modifier: Modifier,
    selectedTaskId: String?,
    onTaskSelected: (String) -> Unit,
    onLaunchWorkflow: (String, String, RepositoryRef?, List<WorkflowRunId>) -> Unit,
    onLoadLaunchableRuns: suspend () -> List<LaunchableRun> = { emptyList() },
    projectFileService: ProjectFileService? = null,
    onImportProjectFile: suspend (String) -> String? = { null },
    onRecoverFromCorruption: () -> Unit = {},
    onRetryRuntime: () -> Unit = {},
    /** Opens Settings, where every linked provider can be reconfigured. */
    onOpenSettings: () -> Unit = {},
    onValidateWorkflow: () -> List<String> = { emptyList() },
    availableRepositorySources: Set<RepositorySource> = setOf(RepositorySource.GitHub, RepositorySource.GitLab),
    onPickLocalRepository: (suspend () -> String?)? = null,
    connectedRepositoryServiceIds: Set<String> = emptySet(),
    onSearchRepositories: suspend (RepositorySource, String) -> List<RepositorySuggestion> = { _, _ -> emptyList() },
    compact: Boolean,
    onSurface: (() -> Unit)? = null,
    runtimeState: ApplicationRuntimeState,
    /** Where the connected agents can work; feeding is gated on it (docs/Keepers-Room.md, First launch). */
    providerReach: ProviderReach = ProviderReach.Unknown,
    /** Links a keyless provider in one tap: the gate's resolution, in place. */
    onConnectFreeProvider: (String) -> Unit = {},
    /** Why the last launch did not start; shown beside Feed it until the next edit or launch. */
    launchError: String? = null,
    onDismissLaunchError: () -> Unit = {},
    /** Live thinking and steps of each provider session; the active creature's newest is its "doing now". */
    activity: SessionActivityFeeds = SessionActivityFeeds.Shared,
) {
    val liveWorkflow = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    val scope = rememberCoroutineScope()
    val activityEntrance = remember { AzphaltEntrance.childBand() }
    var projectLoadError by remember(runtimeState) { mutableStateOf<String?>(null) }
    val noRunProject = (runtimeState as? ApplicationRuntimeState.NoRun)?.project
    val existingRepository = noRunProject?.repository
    val draftScope = noRunProject?.id?.value ?: "new-project"
    val selectableRepositorySources = remember(availableRepositorySources, existingRepository?.source) {
        (availableRepositorySources + listOfNotNull(existingRepository?.source))
            .ifEmpty { setOf(RepositorySource.GitHub, RepositorySource.GitLab) }
            .sortedBy { it.ordinal }
    }
    var projectName by rememberDurableStringState(
        key = "overview.$draftScope.project-name",
        initialValue = noRunProject?.name.orEmpty(),
    )
    var repositorySourceName by rememberDurableStringState(
        key = "overview.$draftScope.repository-source",
        initialValue = (existingRepository?.source ?: selectableRepositorySources.first()).name,
    )
    val repositorySource = selectableRepositorySources
        .firstOrNull { it.name == repositorySourceName }
        ?: selectableRepositorySources.first()
    var repositoryLocator by rememberDurableStringState(
        key = "overview.$draftScope.repository-locator",
        initialValue = existingRepository?.locatorInput().orEmpty(),
    )
    var defaultBranch by rememberDurableStringState(
        key = "overview.$draftScope.default-branch",
        initialValue = existingRepository?.defaultBranch.orEmpty(),
    )
    var recoverConfirm by remember { mutableStateOf(false) }
    val missingBothMessage = stringResource(Res.string.overview_enter_name_and_objective)
    val missingNameMessage = stringResource(Res.string.overview_enter_name)
    val missingObjectiveMessage = stringResource(Res.string.overview_enter_objective)
    val invalidRepositoryMessage = stringResource(Res.string.overview_invalid_repository_location)
    var optionsOpen by rememberDurableBooleanState(
        key = "overview.$draftScope.options-open",
        initialValue = existingRepository != null,
    )
    var launchFieldsError by remember(runtimeState) { mutableStateOf<String?>(null) }
    var repositoryError by remember(runtimeState) { mutableStateOf<String?>(null) }
    var repositorySearchError by remember(runtimeState) { mutableStateOf<String?>(null) }
    var repositorySuggestions by remember(runtimeState) { mutableStateOf<List<RepositorySuggestion>>(emptyList()) }
    var repositoryMenuExpanded by remember(runtimeState) { mutableStateOf(false) }
    var objective by rememberDurableStringState(
        key = "overview.$draftScope.objective",
        initialValue = "",
    )
    var lineage by remember(draftScope) { mutableStateOf(LaunchLineageState()) }
    var launchableRuns by remember(runtimeState) { mutableStateOf<List<LaunchableRun>?>(null) }
    LaunchedEffect(runtimeState, lineage.mode) {
        if (lineage.mode == LaunchLineageMode.New || launchableRuns != null) return@LaunchedEffect
        val loaded = runCatching { onLoadLaunchableRuns() }.getOrDefault(emptyList())
        launchableRuns = loaded
        lineage = lineage.retainOnly(loaded.map(LaunchableRun::id))
    }

    val repositorySearchConnected = when (repositorySource) {
        RepositorySource.GitHub -> RepositoryServiceCatalog.GITHUB_ID in connectedRepositoryServiceIds
        RepositorySource.GitLab -> RepositoryServiceCatalog.GITLAB_ID in connectedRepositoryServiceIds
        RepositorySource.Local -> false
    }

    LaunchedEffect(repositorySource, repositoryLocator, repositorySearchConnected) {
        repositorySuggestions = emptyList()
        repositorySearchError = null
        if (!repositorySearchConnected) return@LaunchedEffect
        delay(180)
        runCatching {
            onSearchRepositories(repositorySource, repositoryLocator.trim())
        }.fold(
            onSuccess = { suggestions ->
                repositorySuggestions = suggestions
            },
            onFailure = { failure ->
                // The raw reply (an HTTP body) is no help here: say what still works.
                platformDebugLog("AiveLaunch", "Repository search failed: ${failure.message}")
                repositorySearchError = getString(Res.string.overview_repository_search_unavailable)
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(if (compact) 16.dp else 26.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (liveWorkflow == null) {
            // A first launch opens on the hive (docs/Keepers-Room.md, stage 2): the creature, and one step to feed it.
            val firstLaunch = runtimeState is ApplicationRuntimeState.NoProject
            val stableHost = LocalStableHost.current
            // The keeper's pick, else the first starter: read live, since the catalog can load after this
            // screen composes, and Feed it must keep a creature either way.
            var explicitStarter by remember(stableHost) { mutableStateOf<String?>(null) }
            val pickedStarter = explicitStarter ?: stableHost?.catalog?.starters?.firstOrNull()?.id
            if (firstLaunch) {
                Text(stringResource(Res.string.first_hive), style = if (compact) AzphaltType.section else AzphaltType.hero, color = azphaltOnSurface())
                FirstCreature(picked = pickedStarter, onPick = { explicitStarter = it })
                Text(stringResource(Res.string.first_hive_hint), style = AzphaltType.body, color = azphaltOnSurface())
            } else {
                RuntimeStateRecord(runtimeState, compact)
            }
            val corrupted = (runtimeState as? ApplicationRuntimeState.ResumeFailed)?.isCorrupted == true
            if (corrupted) {
                AzphaltPill(
                    stringResource(Res.string.overview_recover_delete_all_data),
                    "recover-corruption",
                    onClick = { recoverConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (recoverConfirm) {
                    AzphaltAlertDialog(
                        title = stringResource(Res.string.confirm_recover_title),
                        text = stringResource(Res.string.confirm_recover_body),
                        confirmLabel = stringResource(Res.string.confirm_recover_action),
                        onConfirm = {
                            recoverConfirm = false
                            onRecoverFromCorruption()
                        },
                        onDismissRequest = { recoverConfirm = false },
                        dismissLabel = stringResource(Res.string.confirm_cancel),
                    )
                }
            }
            val recoverableRuntimeFailure =
                (runtimeState is ApplicationRuntimeState.ResumeFailed && !corrupted) ||
                    runtimeState is ApplicationRuntimeState.Disconnected
            if (recoverableRuntimeFailure) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AzphaltPill(
                        stringResource(Res.string.overview_retry_runtime),
                        "retry-runtime",
                        onClick = onRetryRuntime,
                        modifier = Modifier.weight(1f),
                    )
                    AzphaltPill(
                        stringResource(Res.string.overview_provider_settings),
                        "provider-settings",
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (runtimeState is ApplicationRuntimeState.NoProject || runtimeState is ApplicationRuntimeState.NoRun) {
                // Which project, and its name: up front for a project that exists, under Options on a first launch.
                val projectFields: @Composable () -> Unit = {
                    if (projectFileService != null) {
                        Text(
                            stringResource(Res.string.overview_project),
                            style = AzphaltType.eyebrow,
                            color = azphaltOnSurface(),
                        )
                        Text(
                            stringResource(Res.string.overview_aive_automatically_reopens_the_last_saved),
                            style = AzphaltType.body,
                            color = azphaltOnSurface(),
                        )
                        AzphaltPill(
                            label = stringResource(Res.string.overview_open_a_different_saved_project),
                            seed = "overview-open-different-project",
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        val opened = projectFileService.chooseAndRead() ?: return@launch
                                        onImportProjectFile(opened.content)
                                            ?: error("Project could not be loaded")
                                    }.onSuccess {
                                        projectLoadError = null
                                    }.onFailure { failure ->
                                        projectLoadError = failure.message ?: getString(Res.string.overview_project_load_failed)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        projectLoadError?.let { message ->
                            AzphaltRecord(
                                seed = "overview-project-load-error",
                                eyebrow = stringResource(Res.string.overview_project_load_failed),
                                title = stringResource(Res.string.overview_could_not_open_saved_project),
                                body = message,
                                endCap = stringResource(Res.string.common_dismiss),
                                onClick = { projectLoadError = null },
                            )
                        }
                        Text(
                            stringResource(Res.string.overview_or_create_a_new_project),
                            style = AzphaltType.eyebrow,
                            color = azphaltOnSurface(),
                        )
                    }

                    OutlinedTextField(
                        colors = azphaltTextFieldColors(),
                        value = projectName,
                        onValueChange = { projectName = it },
                        label = { Text(stringResource(Res.string.overview_project_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                }
                if (!firstLaunch) projectFields()

                // What feeding still needs, and where it is resolved (Conveyance: a Gate knows where it lives).
                val chosenSource = repositorySource.takeIf { repositoryLocator.isNotBlank() }
                val gate = providerReach.gateFor(chosenSource)
                val repositoryGated = gate is FeedGate.Repository || gate is FeedGate.UnsupportedRepository
                // A repository the agents can't use is swapped for one they can, before anything is typed.
                LaunchedEffect(gate) {
                    launchFieldsError = null
                    val usable = (gate as? FeedGate.Repository)?.sources.orEmpty()
                    if (usable.isNotEmpty() && repositorySource !in usable && repositoryLocator.isBlank()) {
                        selectableRepositorySources.firstOrNull { it in usable }?.let { repositorySourceName = it.name }
                    }
                }
                val repositoryFields: @Composable () -> Unit = {
                Text(
                    stringResource(Res.string.overview_link_repository),
                    style = AzphaltType.eyebrow,
                    color = azphaltOnSurface(),
                )
                // Gated, only the kinds the agents work in; one kind needs no choice at all.
                val shownSources = (gate as? FeedGate.Repository)?.sources
                    ?.let { usable -> selectableRepositorySources.filter { it in usable } }
                    ?.takeIf { it.isNotEmpty() }
                    ?: selectableRepositorySources
                if (shownSources.size > 1) Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shownSources.forEach { source ->
                        AzphaltPill(
                            label = source.displayName(),
                            seed = "repository-source-${source.name}",
                            selected = repositorySource == source,
                            onClick = {
                                if (repositorySource != source) {
                                    repositorySourceName = source.name
                                    repositoryLocator = ""
                                    repositoryError = null
                                    repositorySearchError = null
                                    repositorySuggestions = emptyList()
                                    repositoryMenuExpanded = false
                                }
                            },
                        )
                    }
                }
                if (repositorySource == RepositorySource.Local && onPickLocalRepository != null) {
                    AzphaltPill(
                        label = stringResource(Res.string.overview_choose_git_folder),
                        seed = "choose-local-git-folder",
                        onClick = {
                            scope.launch {
                                // Android imports the picked folder first, so this can take a moment.
                                runCatching { onPickLocalRepository() }
                                    .onSuccess { path ->
                                        path?.let {
                                            repositoryLocator = it
                                            repositoryError = null
                                        }
                                    }
                                    .onFailure { repositoryError = it.message }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (repositorySearchConnected) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            colors = azphaltTextFieldColors(),
                            value = repositoryLocator,
                            onValueChange = {
                                repositoryLocator = it
                                repositoryError = null
                                repositoryMenuExpanded = true
                            },
                            label = {
                                Text(
                                    when (repositorySource) {
                                        RepositorySource.GitHub -> stringResource(Res.string.overview_search_github_repositories_or_paste_url)
                                        RepositorySource.GitLab -> stringResource(Res.string.overview_search_gitlab_repositories_or_paste_url)
                                        RepositorySource.Local -> stringResource(Res.string.overview_local_git_folder_path)
                                    },
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focus ->
                                    if (focus.isFocused) repositoryMenuExpanded = true
                                },
                            isError = repositoryError != null,
                            supportingText = (repositoryError ?: repositorySearchError)?.let { message ->
                                { Text(message, modifier = Modifier.azphaltLiveRegion()) }
                            },
                        )
                        DropdownMenu(
                            expanded = repositoryMenuExpanded && repositorySuggestions.isNotEmpty(),
                            onDismissRequest = { repositoryMenuExpanded = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            repositorySuggestions.forEach { suggestion ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                buildString {
                                                    if (suggestion.ownedByCurrentUser) append(stringResource(Res.string.overview_yours))
                                                    append(suggestion.fullName)
                                                },
                                            )
                                            val detail = listOfNotNull(
                                                suggestion.defaultBranch?.let { stringResource(Res.string.overview_branch_1, it) },
                                                suggestion.description?.takeIf(String::isNotBlank),
                                            ).joinToString(" · ")
                                            if (detail.isNotBlank()) {
                                                Text(detail, style = AzphaltType.body)
                                            }
                                        }
                                    },
                                    onClick = {
                                        repositoryLocator = suggestion.webUrl
                                        defaultBranch = suggestion.defaultBranch.orEmpty()
                                        repositoryError = null
                                        repositorySearchError = null
                                        repositoryMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        colors = azphaltTextFieldColors(),
                        value = repositoryLocator,
                        onValueChange = {
                            repositoryLocator = it
                            repositoryError = null
                        },
                        label = {
                            Text(
                                when (repositorySource) {
                                    RepositorySource.GitHub -> stringResource(Res.string.overview_github_url_or_owner_repository)
                                    RepositorySource.GitLab -> stringResource(Res.string.overview_gitlab_url_or_group_repository)
                                    RepositorySource.Local -> stringResource(Res.string.overview_local_git_folder_path)
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = repositoryError != null,
                        supportingText = repositoryError?.let { message ->
                            { Text(message, modifier = Modifier.azphaltLiveRegion()) }
                        },
                    )
                }
                if (!repositoryGated) OutlinedTextField(
                    colors = azphaltTextFieldColors(),
                    value = defaultBranch,
                    onValueChange = { defaultBranch = it },
                    label = { Text(stringResource(Res.string.overview_default_branch_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!repositoryGated) Text(
                    when {
                        repositorySource == RepositorySource.Local ->
                            stringResource(Res.string.overview_this_runtime_can_link_the_selected)
                        repositorySearchConnected ->
                            stringResource(Res.string.overview_start_typing_to_search_your_own)
                        else ->
                            stringResource(Res.string.overview_paste_the_repository_url_or_shorthand)
                    },
                    style = AzphaltType.body,
                    color = azphaltOnSurface(),
                )
                }
                OutlinedTextField(
                    colors = azphaltTextFieldColors(),
                    value = objective,
                    onValueChange = {
                        objective = it
                        onDismissLaunchError()
                    },
                    label = { Text(stringResource(if (firstLaunch) Res.string.first_hive_objective else Res.string.overview_objective)) },
                    minLines = if (compact) 2 else 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                AzphaltPill(
                    label = stringResource(if (firstLaunch) Res.string.first_hive_options else Res.string.overview_options),
                    seed = "launch-options",
                    selected = optionsOpen,
                    onClick = { optionsOpen = !optionsOpen },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (optionsOpen) {
                if (firstLaunch) projectFields()
                if (!repositoryGated) repositoryFields()
                LaunchLineagePicker(
                    state = lineage,
                    runs = launchableRuns,
                    onStateChange = {
                        lineage = it
                        launchFieldsError = null
                    },
                )
                }
                // The gate sits right above Feed it, with its resolution in place: a provider to connect,
                // or the repository the agents need, its fields open here rather than under Options.
                val sourceNames = { sources: Set<RepositorySource> ->
                    sources.sortedBy { it.ordinal }.joinToString(" / ") { it.displayName() }
                }
                val gateMessage = when (gate) {
                    FeedGate.Open -> null
                    FeedGate.Provider -> stringResource(Res.string.first_hive_connect_hint)
                    is FeedGate.Repository -> stringResource(Res.string.feed_gate_repository, sourceNames(gate.sources))
                    is FeedGate.UnsupportedRepository -> stringResource(
                        Res.string.feed_gate_unsupported_repository,
                        gate.source.displayName(),
                        sourceNames(gate.sources),
                    )
                }
                gateMessage?.let { message ->
                    Text(message, style = AzphaltType.body, color = azphaltOnSurface())
                }
                if (repositoryGated) repositoryFields()
                if (gate == FeedGate.Provider || gate is FeedGate.Repository) {
                    // Something to think with, in one tap: the free gateways right here, the rest
                    // (your own key) in the Keeper's room.
                    val free = ProviderCatalog.builtInEntries.filter { it.keyOptional }.take(3)
                    free.forEachIndexed { index, entry ->
                        AzphaltPill(
                            tone = if (index == 0 && gate == FeedGate.Provider) PillTone.Primary else PillTone.Quiet,
                            label = stringResource(Res.string.feed_gate_use_free, entry.displayName),
                            seed = "first-hive-free-${entry.id}",
                            onClick = { onConnectFreeProvider(entry.id) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (free.isNotEmpty()) {
                        Text(stringResource(Res.string.credential_keyless_hint), style = AzphaltType.body, color = azphaltOnSurface())
                    }
                    AzphaltPill(
                        tone = PillTone.Quiet,
                        label = stringResource(Res.string.feed_gate_own_key),
                        seed = "first-hive-connect",
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AzphaltPill(
                    tone = if (gate == FeedGate.Provider) PillTone.Quiet else PillTone.Primary,
                    label = when {
                        firstLaunch -> stringResource(Res.string.first_hive_feed)
                        runtimeState is ApplicationRuntimeState.NoRun -> stringResource(Res.string.overview_start_run)
                        else -> stringResource(Res.string.overview_create_project_start_run)
                    },
                    seed = "project-start-run",
                    selected = (firstLaunch || projectName.isNotBlank()) && objective.isNotBlank(),
                    onClick = {
                        onDismissLaunchError()
                        // A gated Feed it escorts to what is missing rather than starting work no agent can take.
                        if (gate != FeedGate.Open) {
                            launchFieldsError = gateMessage
                            platformDebugLog("AiveLaunch", "Launch gated: $gate")
                            return@AzphaltPill
                        }
                        val locator = repositoryLocator.trim()
                        // A first hive needs no name to start: it takes one from what it was fed, once
                        // it has been fed (an empty tap must not name it "First hive" for good).
                        val name = projectName.trim().ifBlank {
                            if (firstLaunch && objective.isNotBlank()) firstHiveName(objective) else ""
                        }
                        // Permanent diagnostic: distinguishes "tap never arrived" from "tap arrived
                        // but launch failed" in any future "nothing happens" report.
                        platformDebugLog(
                            "AiveLaunch",
                            "CREATE PROJECT + START RUN tapped: project=$name " +
                                "repo=${locator.ifEmpty { "<none>" }} objectiveChars=${objective.trim().length}",
                        )
                        if (name.isBlank() || objective.isBlank()) {
                            // Previously a silent no-op: the only cue was the pill's unselected colour.
                            launchFieldsError = when {
                                firstLaunch || name.isNotBlank() -> missingObjectiveMessage
                                objective.isBlank() -> missingBothMessage
                                name.isBlank() -> missingNameMessage
                                else -> missingObjectiveMessage
                            }
                            platformDebugLog("AiveLaunch", "Launch blocked: $launchFieldsError")
                            return@AzphaltPill
                        }
                        lineage.validationError?.let { message ->
                            launchFieldsError = message
                            platformDebugLog("AiveLaunch", "Launch blocked: $message")
                            return@AzphaltPill
                        }
                        val parents = lineage.continuesWorkflowRunIds
                        launchFieldsError = null
                        if (firstLaunch) stableHost?.takeIf { it.stable.starter == null }?.let { host -> pickedStarter?.let(host::chooseStarter) }
                        if (locator.isEmpty()) {
                            repositoryError = null
                            onLaunchWorkflow(name, objective.trim(), null, parents)
                        } else {
                            runCatching {
                                parseRepositoryRef(
                                    source = repositorySource,
                                    locator = locator,
                                    defaultBranch = defaultBranch,
                                )
                            }.fold(
                                onSuccess = { repository ->
                                    repositoryError = null
                                    onLaunchWorkflow(name, objective.trim(), repository, parents)
                                },
                                onFailure = { failure ->
                                    repositoryError = failure.message ?: invalidRepositoryMessage
                                    optionsOpen = true
                                    platformDebugLog("AiveLaunch", "Launch blocked: invalid repository: $repositoryError")
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                (launchFieldsError ?: launchError)?.let { message ->
                    Text(
                        message,
                        style = AzphaltType.body,
                        color = azphaltOnSurface(),
                        modifier = Modifier.azphaltLiveRegion(),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        val run = liveWorkflow.run
        val completed = run.taskRuns.values.count { it.status == TaskRunStatus.Completed }
        val total = run.taskRuns.size

        // The breadcrumb names the hive; here, what it was fed and how far along it is.
        Text(run.objective, style = AzphaltType.section, color = azphaltOnSurface())
        Text(
            "${run.status.label()} · $completed/$total".uppercase(),
            style = AzphaltType.eyebrow,
            color = azphaltOnSurface(),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        // What it made, on the hive itself: the finished work of the creatures nothing else feeds on,
        // so the result is never three taps deep in a creature's panel. Selectable, to take it away.
        if (run.status == WorkflowRunStatus.Completed) {
            val tasks = liveWorkflow.definition.tasks
            val finalTasks = tasks.filter { task -> tasks.none { task.id in it.dependsOn } }.map { it.id }.toSet()
            val made = run.taskRuns.filterKeys { it in finalTasks }.values
                .flatMap { it.artifacts }
                .filter { !it.textContent.isNullOrBlank() }
            if (made.isNotEmpty()) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        made.forEach { artifact ->
                            AzphaltRecord(
                                seed = "made-${artifact.id.value}",
                                eyebrow = stringResource(Res.string.hive_what_it_made),
                                title = artifact.label,
                                richBody = markdownLite(
                                    artifact.textContent.orEmpty().trim().let { text ->
                                        if (text.length > MADE_PREVIEW_CHARS) text.take(MADE_PREVIEW_CHARS).trimEnd() + "…" else text
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }

        if (run.status.isFinished()) {
            ContinueRunPanel(
                runId = run.id,
                onContinue = { continuation ->
                    onLaunchWorkflow(liveWorkflow.project.name, continuation, liveWorkflow.project.repository, listOf(run.id))
                },
            )
        }

        liveWorkflow.project.repository?.let { repository ->
            RepositoryProgressRecord(
                repository = repository,
                run = run,
            )
        }

        // Only when a creature waits on you, and it opens that creature: the same "needs you" as the
        // header's badge and the creature's Needs.
        firstCreatureNeedingYou(liveWorkflow)?.let { waiting ->
            AzphaltRecord(
                seed = "owner-attention",
                eyebrow = stringResource(Res.string.overview_owner_attention),
                title = stringResource(Res.string.overview_decision_required),
                body = stringResource(Res.string.overview_a_workflow_gate_is_waiting_for),
                endCap = stringResource(Res.string.overview_required),
                onClick = { if (selectedTaskId != waiting) onTaskSelected(waiting) },
            )
        }

        val validationErrors = remember(liveWorkflow.definition) { onValidateWorkflow() }
        if (validationErrors.isNotEmpty()) {
            Text(stringResource(Res.string.overview_workflow_errors), style = AzphaltType.eyebrow, color = azphaltOnSurface())
            validationErrors.forEachIndexed { index, error ->
                AzphaltRecord(
                    seed = "validation-error-$index",
                    eyebrow = stringResource(Res.string.overview_invalid),
                    title = stringResource(Res.string.overview_workflow_definition_error),
                    body = error,
                    endCap = stringResource(Res.string.overview_blocked),
                )
            }
        }
        val activeTaskId = remember(run) {
            run.taskRuns.values
                .firstOrNull { it.status == TaskRunStatus.Running || it.status == TaskRunStatus.Planning }
                ?.taskDefinitionId?.value
        }

        if (activeTaskId != null) {
            AzphaltPill(
                label = stringResource(Res.string.overview_jump_to_active),
                seed = "jump-active",
                // Opens the working creature; never closes it if it is already open.
                onClick = { if (selectedTaskId != activeTaskId) onTaskSelected(activeTaskId) },
            )
            run.taskRuns.values.firstOrNull { it.taskDefinitionId.value == activeTaskId }?.let { activeRun ->
                val name = activeRun.assignedRoleId
                    ?.let { roleId -> liveWorkflow.roles.firstOrNull { it.id == roleId }?.name }
                    ?: liveWorkflow.definition.tasks.firstOrNull { it.id == activeRun.taskDefinitionId }?.name
                    ?: activeTaskId
                CreatureDoingNow(activeRun, name, activity)
            }
        }
        GeministratorWorkflowMindMap(
            definition = liveWorkflow.definition,
            run = run,
            roles = liveWorkflow.roles,
            selectedTaskId = selectedTaskId,
            onTaskSelected = onTaskSelected,
            modifier = Modifier.fillMaxWidth(),
            onSurface = onSurface,
        )

        Text(stringResource(Res.string.overview_swarm_activity), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        val recentTasks = liveWorkflow.definition.tasks
            .mapNotNull { task -> run.taskRuns[task.id]?.let { taskRun -> task to taskRun } }
            .sortedByDescending { (_, taskRun) -> taskRun.status.activityRank() }
            .take(8)
        recentTasks.forEachIndexed { index, (task, taskRun) ->
            val detail = buildString {
                append(taskRun.status.label())
                taskRun.progressMessage?.takeIf(String::isNotBlank)?.let {
                    append(" · ")
                    append(it)
                }
                taskRun.blockingReason?.message?.takeIf(String::isNotBlank)?.let {
                    append(" · ")
                    append(it)
                }
            }
            AzphaltNote(
                seed = "task-activity-${task.id.value}",
                label = task.name,
                value = detail,
                modifier = Modifier
                    .azphaltLiveRegion()
                    .azphaltEntrance(activityEntrance, index, recentTasks.size.coerceAtLeast(1)),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One short line: what the active creature is doing now, its newest thought or step. */
@Composable
private fun CreatureDoingNow(taskRun: TaskRun, name: String, activity: SessionActivityFeeds) {
    val providerRunId = taskRun.providerRunId ?: return
    val feeds by activity.feeds.collectAsState()
    val latest = feeds[SessionActivityKey(taskRun.id, providerRunId)]?.lastOrNull() ?: return
    AzphaltNote(
        seed = "doing-now-${taskRun.id.value}",
        label = stringResource(Res.string.overview_doing_now, name),
        value = latest.doingNowText(),
    )
}

@Composable
private fun RuntimeStateRecord(state: ApplicationRuntimeState, compact: Boolean) {
    val (title, body) = when (state) {
        ApplicationRuntimeState.Loading -> stringResource(Res.string.overview_loading_runtime) to stringResource(Res.string.overview_reading_persisted_workflow_state)
        is ApplicationRuntimeState.NoProject -> stringResource(Res.string.overview_no_project) to stringResource(Res.string.overview_create_a_project_and_define_its)
        is ApplicationRuntimeState.NoRun -> stringResource(Res.string.overview_no_active_run) to stringResource(Res.string.overview_has_no_persisted_workflow_run, state.project.name)
        is ApplicationRuntimeState.Disconnected -> stringResource(Res.string.overview_runtime_disconnected) to friendlyErrorMessage(state.message)
        is ApplicationRuntimeState.ResumeFailed -> stringResource(Res.string.overview_resume_failed) to
            if (state.isCorrupted) state.message else friendlyErrorMessage(state.message)
        is ApplicationRuntimeState.Live -> return
    }
    // One block, in plain words: what happened, then what to do (the actions follow it).
    Text(
        title,
        style = AzphaltType.section,
        color = azphaltOnSurface(),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Text(body, style = AzphaltType.body, color = azphaltOnSurface())
    val rawDetails = when {
        state is ApplicationRuntimeState.Disconnected -> state.message
        state is ApplicationRuntimeState.ResumeFailed && !state.isCorrupted -> state.message
        else -> null
    }
    ErrorDetailsToggle(rawDetails, seed = "runtime-state")
}

private fun TaskRunStatus.activityRank(): Int = when (this) {
    TaskRunStatus.Running, TaskRunStatus.Planning, TaskRunStatus.Verifying -> 8
    TaskRunStatus.AwaitingApproval, TaskRunStatus.Escalated -> 7
    TaskRunStatus.Retrying -> 6
    TaskRunStatus.Ready -> 5
    TaskRunStatus.Failed -> 4
    TaskRunStatus.Completed -> 3
    TaskRunStatus.Blocked -> 2
    TaskRunStatus.Created -> 1
    TaskRunStatus.Cancelled -> 0
}

/** How much of a finished result shows on the hive; the creature's Work holds all of it. */
private const val MADE_PREVIEW_CHARS = 2_400

/** A first hive's name, from the first words of what it was fed. */
internal fun firstHiveName(objective: String): String =
    objective.trim().split(Regex("\\s+")).take(4).joinToString(" ").take(40).trim().ifBlank { "First hive" }
