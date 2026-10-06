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
import androidx.compose.material3.MaterialTheme
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
import com.hereliesaz.geministrator.resources.overview_recover_clear_corrupted_data
import com.hereliesaz.geministrator.resources.overview_repository_search_unavailable
import com.hereliesaz.geministrator.resources.overview_required
import com.hereliesaz.geministrator.resources.overview_resume_failed
import com.hereliesaz.geministrator.resources.overview_retry_runtime
import com.hereliesaz.geministrator.resources.overview_runtime
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
    onPickLocalRepository: (() -> String?)? = null,
    connectedRepositoryServiceIds: Set<String> = emptySet(),
    onSearchRepositories: suspend (RepositorySource, String) -> List<RepositorySuggestion> = { _, _ -> emptyList() },
    compact: Boolean,
    runtimeState: ApplicationRuntimeState,
) {
    val liveWorkflow = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    val scope = rememberCoroutineScope()
    val activityEntrance = remember { AzphaltEntrance.childBand() }
    var projectLoadError by remember(runtimeState) { mutableStateOf<String?>(null) }
    val noRunProject = (runtimeState as? ApplicationRuntimeState.NoRun)?.project
    val existingRepository = noRunProject?.repository
    val draftScope = noRunProject?.id?.value ?: "new-project"
    var branchIsolation by rememberDurableBooleanState(
        key = "overview.$draftScope.branch-isolation",
        initialValue = false,
    )
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
                repositorySearchError = failure.message?.take(120) ?: getString(Res.string.overview_repository_search_unavailable)
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
            RuntimeStateRecord(runtimeState, compact)
            val corrupted = (runtimeState as? ApplicationRuntimeState.ResumeFailed)?.isCorrupted == true
            if (corrupted) {
                AzphaltPill(
                    stringResource(Res.string.overview_recover_clear_corrupted_data),
                    "recover-corruption",
                    onClick = onRecoverFromCorruption,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                if (projectFileService != null) {
                    Text(
                        stringResource(Res.string.overview_project),
                        style = AzphaltType.eyebrow,
                        color = Azphalt.currentGround.onPage,
                    )
                    Text(
                        stringResource(Res.string.overview_aive_automatically_reopens_the_last_saved),
                        style = AzphaltType.body,
                        color = Azphalt.currentGround.onPage,
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
                        color = Azphalt.currentGround.onPage,
                    )
                }

                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text(stringResource(Res.string.overview_project_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    stringResource(Res.string.overview_link_repository),
                    style = AzphaltType.eyebrow,
                    color = Azphalt.currentGround.onPage,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selectableRepositorySources.forEach { source ->
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
                            onPickLocalRepository()?.let { path ->
                                repositoryLocator = path
                                repositoryError = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (repositorySearchConnected) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
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
                                { Text(message) }
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
                            { Text(message) }
                        },
                    )
                }
                OutlinedTextField(
                    value = defaultBranch,
                    onValueChange = { defaultBranch = it },
                    label = { Text(stringResource(Res.string.overview_default_branch_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    when {
                        repositorySource == RepositorySource.Local ->
                            stringResource(Res.string.overview_this_runtime_can_link_the_selected)
                        repositorySearchConnected ->
                            stringResource(Res.string.overview_start_typing_to_search_your_own)
                        else ->
                            stringResource(Res.string.overview_paste_the_repository_url_or_shorthand)
                    },
                    style = AzphaltType.body,
                    color = Azphalt.currentGround.onPage,
                )
                LaunchLineagePicker(
                    state = lineage,
                    runs = launchableRuns,
                    onStateChange = {
                        lineage = it
                        launchFieldsError = null
                    },
                )
                OutlinedTextField(
                    value = objective,
                    onValueChange = { objective = it },
                    label = { Text(stringResource(Res.string.overview_objective)) },
                    minLines = if (compact) 2 else 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                AzphaltPill(
                    label = if (runtimeState is ApplicationRuntimeState.NoRun) stringResource(Res.string.overview_start_run) else stringResource(Res.string.overview_create_project_start_run),
                    seed = "project-start-run",
                    selected = projectName.isNotBlank() && objective.isNotBlank(),
                    onClick = {
                        val locator = repositoryLocator.trim()
                        // Permanent diagnostic: distinguishes "tap never arrived" from "tap arrived
                        // but launch failed" in any future "nothing happens" report.
                        platformDebugLog(
                            "AiveLaunch",
                            "CREATE PROJECT + START RUN tapped: project=${projectName.trim()} " +
                                "repo=${locator.ifEmpty { "<none>" }} objectiveChars=${objective.trim().length}",
                        )
                        if (projectName.isBlank() || objective.isBlank()) {
                            // Previously a silent no-op: the only cue was the pill's unselected colour.
                            launchFieldsError = when {
                                projectName.isBlank() && objective.isBlank() -> "Enter a project name and an objective to start a run."
                                projectName.isBlank() -> "Enter a project name to start a run."
                                else -> "Enter an objective to start a run."
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
                        if (locator.isEmpty()) {
                            repositoryError = null
                            onLaunchWorkflow(projectName.trim(), objective.trim(), null, parents)
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
                                    onLaunchWorkflow(projectName.trim(), objective.trim(), repository, parents)
                                },
                                onFailure = { failure ->
                                    repositoryError = failure.message ?: "Invalid repository location"
                                    platformDebugLog("AiveLaunch", "Launch blocked: invalid repository: $repositoryError")
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                launchFieldsError?.let { message ->
                    Text(message, style = AzphaltType.body, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        val run = liveWorkflow.run
        val completed = run.taskRuns.values.count { it.status == TaskRunStatus.Completed }
        val total = run.taskRuns.size

        Text(
            run.objective.uppercase(),
            style = if (compact) AzphaltType.section else AzphaltType.hero,
            color = Azphalt.currentGround.onPage,
        )
        Text(
            liveWorkflow.definition.name.uppercase(),
            style = AzphaltType.eyebrow,
            color = Azphalt.currentGround.onPage,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(
                run.status.name,
                "run-status",
                endCap = "$completed/$total",
                onClick = {},
            )
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

        val awaitingHuman = run.status == WorkflowRunStatus.AwaitingHuman
        AzphaltRecord(
            seed = "owner-attention",
            eyebrow = stringResource(Res.string.overview_owner_attention),
            title = if (awaitingHuman) stringResource(Res.string.overview_decision_required) else stringResource(Res.string.overview_no_decision_required),
            body = if (awaitingHuman) {
                stringResource(Res.string.overview_a_workflow_gate_is_waiting_for)
            } else {
                stringResource(Res.string.overview_runtime_is_advancing_without_human_intervention)
            },
            endCap = if (awaitingHuman) stringResource(Res.string.overview_required) else stringResource(Res.string.overview_clear),
        )

        val validationErrors = remember(liveWorkflow.definition) { onValidateWorkflow() }
        if (validationErrors.isNotEmpty()) {
            Text(stringResource(Res.string.overview_workflow_errors), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.overview_swarm_execution), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage, modifier = Modifier.weight(1f))
            if (activeTaskId != null) {
                AzphaltPill(
                    label = stringResource(Res.string.overview_jump_to_active),
                    seed = "jump-active",
                    onClick = { onTaskSelected(activeTaskId) },
                )
            }
            AzphaltPill(
                label = stringResource(Res.string.overview_branch),
                seed = "branch-isolation",
                selected = branchIsolation,
                onClick = { branchIsolation = !branchIsolation },
            )
        }
        GeministratorWorkflowMindMap(
            definition = liveWorkflow.definition,
            run = run,
            roles = liveWorkflow.roles,
            selectedTaskId = selectedTaskId,
            onTaskSelected = onTaskSelected,
            branchIsolation = branchIsolation,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(stringResource(Res.string.overview_swarm_activity), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
        val recentTasks = liveWorkflow.definition.tasks
            .mapNotNull { task -> run.taskRuns[task.id]?.let { taskRun -> task to taskRun } }
            .sortedByDescending { (_, taskRun) -> taskRun.status.activityRank() }
            .take(8)
        recentTasks.forEachIndexed { index, (task, taskRun) ->
            val detail = buildString {
                append(taskRun.status.name)
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
                modifier = Modifier.azphaltEntrance(activityEntrance, index, recentTasks.size.coerceAtLeast(1)),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RuntimeStateRecord(state: ApplicationRuntimeState, compact: Boolean) {
    val (title, body) = when (state) {
        ApplicationRuntimeState.Loading -> stringResource(Res.string.overview_loading_runtime) to stringResource(Res.string.overview_reading_persisted_workflow_state)
        is ApplicationRuntimeState.NoProject -> stringResource(Res.string.overview_no_project) to stringResource(Res.string.overview_create_a_project_and_define_its)
        is ApplicationRuntimeState.NoRun -> stringResource(Res.string.overview_no_active_run) to stringResource(Res.string.overview_has_no_persisted_workflow_run, state.project.name)
        is ApplicationRuntimeState.Disconnected -> stringResource(Res.string.overview_runtime_disconnected) to state.message
        is ApplicationRuntimeState.ResumeFailed -> stringResource(Res.string.overview_resume_failed) to state.message
        is ApplicationRuntimeState.Live -> return
    }
    Text(
        title,
        style = if (compact) AzphaltType.section else AzphaltType.hero,
        color = Azphalt.currentGround.onPage,
    )
    AzphaltRecord(
        seed = "runtime-state-$title",
        eyebrow = stringResource(Res.string.overview_runtime),
        title = title.lowercase().replaceFirstChar(Char::uppercase),
        body = body,
        endCap = null,
    )
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
