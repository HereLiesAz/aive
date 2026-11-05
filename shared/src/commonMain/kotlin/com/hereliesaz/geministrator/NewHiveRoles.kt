package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.hive_can_approve_integration
import com.hereliesaz.geministrator.resources.hive_can_approve_plan
import com.hereliesaz.geministrator.resources.hive_can_approve_release
import com.hereliesaz.geministrator.resources.hive_can_author_tests
import com.hereliesaz.geministrator.resources.hive_can_diagnose_failure
import com.hereliesaz.geministrator.resources.hive_can_implement
import com.hereliesaz.geministrator.resources.hive_can_propose_plan
import com.hereliesaz.geministrator.resources.hive_can_reject_plan
import com.hereliesaz.geministrator.resources.hive_can_review_code
import com.hereliesaz.geministrator.resources.hive_can_select_environment
import com.hereliesaz.geministrator.resources.hive_can_verify
import com.hereliesaz.geministrator.resources.hive_fn_environment
import com.hereliesaz.geministrator.resources.hive_fn_implementer
import com.hereliesaz.geministrator.resources.hive_fn_planner
import com.hereliesaz.geministrator.resources.hive_fn_recovery
import com.hereliesaz.geministrator.resources.hive_fn_releaser
import com.hereliesaz.geministrator.resources.hive_fn_researcher
import com.hereliesaz.geministrator.resources.hive_fn_reviewer
import com.hereliesaz.geministrator.resources.hive_fn_test_writer
import com.hereliesaz.geministrator.resources.hive_fn_verifier
import com.hereliesaz.geministrator.resources.hive_new_bring_in
import com.hereliesaz.geministrator.resources.hive_new_tray
import com.hereliesaz.geministrator.resources.hive_role_add
import com.hereliesaz.geministrator.resources.hive_role_can
import com.hereliesaz.geministrator.resources.hive_role_card
import com.hereliesaz.geministrator.resources.hive_role_close
import com.hereliesaz.geministrator.resources.hive_role_does
import com.hereliesaz.geministrator.resources.hive_role_end
import com.hereliesaz.geministrator.resources.hive_role_gets_from
import com.hereliesaz.geministrator.resources.hive_role_hands_to
import com.hereliesaz.geministrator.resources.hive_role_needs
import com.hereliesaz.geministrator.resources.hive_role_no_description
import com.hereliesaz.geministrator.resources.hive_role_preview_title
import com.hereliesaz.geministrator.resources.hive_role_start
import com.hereliesaz.geministrator.resources.hive_role_unlinked
import com.hereliesaz.geministrator.resources.hive_role_why
import com.hereliesaz.geministrator.resources.hive_roles_all
import com.hereliesaz.geministrator.resources.hive_roles_suggested
import com.hereliesaz.geministrator.resources.hive_why_no_orchestrator
import com.hereliesaz.geministrator.resources.hive_why_nothing_feeds_builder
import com.hereliesaz.geministrator.resources.hive_why_nothing_feeds_checker
import com.hereliesaz.geministrator.resources.hive_why_nothing_reviews
import com.hereliesaz.geministrator.resources.hive_why_nothing_tests
import com.hereliesaz.geministrator.resources.hive_why_start_implementer
import com.hereliesaz.geministrator.resources.hive_why_start_planner
import org.jetbrains.compose.resources.stringResource

/*
 * The new hive's role tray: every role shown with its name and what it is for, a Suggested group
 * first (RoleSuggestions.kt), and a tap that opens a preview rather than adding. Adding is the
 * preview's "Add to hive" button, or the separate "+" on a card.
 */

private val CardWidth = 148.dp

@Composable
private fun RoleFunction.label(): String = stringResource(
    when (this) {
        RoleFunction.Researcher -> Res.string.hive_fn_researcher
        RoleFunction.Planner -> Res.string.hive_fn_planner
        RoleFunction.EnvironmentPlanner -> Res.string.hive_fn_environment
        RoleFunction.TestWriter -> Res.string.hive_fn_test_writer
        RoleFunction.Implementer -> Res.string.hive_fn_implementer
        RoleFunction.Verifier -> Res.string.hive_fn_verifier
        RoleFunction.Reviewer -> Res.string.hive_fn_reviewer
        RoleFunction.Releaser -> Res.string.hive_fn_releaser
        RoleFunction.Recovery -> Res.string.hive_fn_recovery
    },
)

@Composable
private fun RoleAuthority.label(): String = stringResource(
    when (this) {
        RoleAuthority.ProposePlan -> Res.string.hive_can_propose_plan
        RoleAuthority.RejectPlan -> Res.string.hive_can_reject_plan
        RoleAuthority.ApprovePlan -> Res.string.hive_can_approve_plan
        RoleAuthority.SelectEnvironment -> Res.string.hive_can_select_environment
        RoleAuthority.Implement -> Res.string.hive_can_implement
        RoleAuthority.AuthorTests -> Res.string.hive_can_author_tests
        RoleAuthority.Verify -> Res.string.hive_can_verify
        RoleAuthority.ReviewCode -> Res.string.hive_can_review_code
        RoleAuthority.ApproveIntegration -> Res.string.hive_can_approve_integration
        RoleAuthority.ApproveRelease -> Res.string.hive_can_approve_release
        RoleAuthority.DiagnoseFailure -> Res.string.hive_can_diagnose_failure
    },
)

@Composable
internal fun SuggestionReason.sentence(subject: String): String = stringResource(
    when (this) {
        SuggestionReason.StartPlanner -> Res.string.hive_why_start_planner
        SuggestionReason.StartImplementer -> Res.string.hive_why_start_implementer
        SuggestionReason.NothingReviews -> Res.string.hive_why_nothing_reviews
        SuggestionReason.NothingTests -> Res.string.hive_why_nothing_tests
        SuggestionReason.NothingFeedsBuilder -> Res.string.hive_why_nothing_feeds_builder
        SuggestionReason.NothingFeedsChecker -> Res.string.hive_why_nothing_feeds_checker
        SuggestionReason.NoOrchestrator -> Res.string.hive_why_no_orchestrator
    },
    subject,
)

/** A role as a portrait with its name and one line of purpose; a tap previews it, the "+" adds it. */
@Composable
internal fun RoleCard(
    role: RoleDefinition,
    suggested: Boolean,
    onPreview: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val purpose = role.description.ifBlank { stringResource(Res.string.hive_role_no_description) }
    val cardDescription = stringResource(Res.string.hive_role_card, role.name, purpose)
    val addDescription = stringResource(Res.string.hive_new_bring_in, role.name)
    val shape = RoundedCornerShape(20.dp)
    AzphaltThemedSurface(Azphalt.Ink) {
        Box(modifier = modifier.width(CardWidth)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Azphalt.Ink)
                    .then(if (suggested) Modifier.border(2.dp, Azphalt.Yellow, shape) else Modifier)
                    .semantics(mergeDescendants = true) {
                        this.role = Role.Button
                        contentDescription = cardDescription
                    }
                    .clickable(onClick = onPreview)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NodeMascotSurface(
                    roleLabel = role.name,
                    hueSeed = role.id.value,
                    state = H2g2WorkflowState.Pending,
                    motionPhase = { 0f },
                    modifier = Modifier.size(64.dp),
                )
                Text(
                    role.name,
                    style = AzphaltType.lead.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold),
                    color = azphaltOnSurface(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    purpose,
                    style = AzphaltType.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal),
                    color = azphaltMutedOnSurface(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The quick path, apart from the card: a plain "+" that adds the role without the preview.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(AzphaltMinTouchTarget)
                    .semantics {
                        this.role = Role.Button
                        contentDescription = addDescription
                    }
                    .clip(CircleShape)
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                val glyph = azphaltOnSurface()
                Canvas(Modifier.size(28.dp)) {
                    drawCircle(AzphaltLiftedInk)
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = size.width * 0.22f
                    val w = 2.4.dp.toPx()
                    drawLine(glyph, Offset(c.x - r, c.y), Offset(c.x + r, c.y), w, StrokeCap.Round)
                    drawLine(glyph, Offset(c.x, c.y - r), Offset(c.x, c.y + r), w, StrokeCap.Round)
                }
            }
        }
    }
}

/** The tray: Suggested first, then every role, each tap opening a preview; the preview's button adds. */
@Composable
internal fun NewHiveTray(
    definition: WorkflowDefinition,
    roles: Collection<RoleDefinition>,
    onEdit: (TerrariumDependencyEditResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val addable = terrariumAddableRoles(roles)
    if (addable.isEmpty()) return
    val suggestions = remember(definition, roles) { suggestRoles(definition, roles) }
    var previewing by remember(definition.id) { mutableStateOf<RoleDefinition?>(null) }
    val trayLabel = stringResource(Res.string.hive_new_tray)
    val add = { role: RoleDefinition -> onEdit(addTerrariumCreature(definition, TerrariumNewcomer.FromRole(role))) }

    Column(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = trayLabel },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (suggestions.isNotEmpty()) {
            TrayHeading(stringResource(Res.string.hive_roles_suggested))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { s ->
                    RoleCard(s.role, suggested = true, onPreview = { previewing = s.role }, onAdd = { add(s.role) })
                }
            }
        }
        TrayHeading(stringResource(Res.string.hive_roles_all))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            addable.forEach { role ->
                RoleCard(role, suggested = false, onPreview = { previewing = role }, onAdd = { add(role) })
            }
        }
    }
    previewing?.let { role ->
        RolePreviewSheet(
            role = role,
            suggestion = suggestions.firstOrNull { it.role.id == role.id },
            onAdd = {
                previewing = null
                add(role)
            },
            onClose = { previewing = null },
        )
    }
}

@Composable
private fun TrayHeading(text: String) {
    Text(
        text.uppercase(),
        style = AzphaltType.eyebrow,
        color = azphaltOnSurface(),
        modifier = Modifier.semantics { heading() },
    )
}

/** The preview as a bottom sheet, as the compact creature panel is. Closing never adds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RolePreviewSheet(role: RoleDefinition, suggestion: RoleSuggestion?, onAdd: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = Azphalt.Ink,
        contentColor = Azphalt.Ink.onOpposite(),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Azphalt.Ink.mutedOnOpposite()) },
    ) {
        RolePreviewContent(role, suggestion, onAdd, onClose, Modifier.fillMaxWidth())
    }
}

/** What a role is, does and connects to, with **Add to hive** and **Close**. */
@Composable
internal fun RolePreviewContent(
    role: RoleDefinition,
    suggestion: RoleSuggestion?,
    onAdd: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(Res.string.hive_role_preview_title, role.name)
    val from = role.usuallyReceivesFrom()
    val to = role.usuallyHandsTo()
    AzphaltThemedSurface(Azphalt.Ink) {
        Column(
            modifier = modifier
                .background(Azphalt.Ink)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                NodeMascotSurface(
                    roleLabel = role.name,
                    hueSeed = role.id.value,
                    state = H2g2WorkflowState.Pending,
                    motionPhase = { 0f },
                    modifier = Modifier.size(88.dp),
                )
                Text(
                    role.name,
                    style = AzphaltType.hero.copy(fontSize = 26.sp, lineHeight = 30.sp),
                    color = azphaltOnSurface(),
                    modifier = Modifier.weight(1f).semantics {
                        heading()
                        contentDescription = title
                    },
                )
            }
            suggestion?.let {
                Text(
                    stringResource(Res.string.hive_role_why, it.reason.sentence(it.subject)),
                    style = AzphaltType.body.copy(fontWeight = FontWeight.Bold),
                    color = Azphalt.Yellow,
                )
            }
            PreviewSection(stringResource(Res.string.hive_role_does)) {
                Text(
                    role.description.ifBlank { stringResource(Res.string.hive_role_no_description) },
                    style = AzphaltType.body,
                    color = azphaltOnSurface(),
                )
            }
            val can = role.authorities.sortedBy { it.ordinal }
            if (can.isNotEmpty()) {
                PreviewSection(stringResource(Res.string.hive_role_can)) {
                    CommaList(can.map { it.label() })
                }
            }
            val needs = role.capabilitiesRequired.sortedBy { it.ordinal }
            if (needs.isNotEmpty()) {
                PreviewSection(stringResource(Res.string.hive_role_needs)) { CommaList(needs.map(::capabilityName)) }
            }
            PreviewSection(stringResource(Res.string.hive_role_gets_from)) {
                when {
                    from.isNotEmpty() -> CommaList(from.map { it.label() })
                    to.isEmpty() -> Text(stringResource(Res.string.hive_role_unlinked), style = AzphaltType.body, color = azphaltOnSurface())
                    else -> Text(stringResource(Res.string.hive_role_start), style = AzphaltType.body, color = azphaltOnSurface())
                }
            }
            PreviewSection(stringResource(Res.string.hive_role_hands_to)) {
                when {
                    to.isNotEmpty() -> CommaList(to.map { it.label() })
                    from.isEmpty() -> Text(stringResource(Res.string.hive_role_unlinked), style = AzphaltType.body, color = azphaltOnSurface())
                    else -> Text(stringResource(Res.string.hive_role_end), style = AzphaltType.body, color = azphaltOnSurface())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)) {
                val addLabel = stringResource(Res.string.hive_role_add)
                RoleCapsule(addLabel, "$addLabel: ${role.name}", azphaltStrong(), onAdd)
                val closeLabel = stringResource(Res.string.hive_role_close)
                RoleCapsule(closeLabel, closeLabel, AzphaltLiftedInk, onClose)
            }
        }
    }
}

@Composable
private fun PreviewSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title.uppercase(), style = AzphaltType.eyebrow, color = azphaltMutedOnSurface(), modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun CommaList(items: List<String>) {
    Text(items.joinToString(", "), style = AzphaltType.body, color = azphaltOnSurface())
}

@Composable
private fun RoleCapsule(label: String, description: String, fill: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .defaultMinSize(minHeight = AzphaltMinTouchTarget, minWidth = AzphaltMinTouchTarget)
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = AzphaltType.capsule.copy(fontSize = 14.sp), color = fill.onOpposite())
    }
}
