package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.inbox_badge_none
import com.hereliesaz.geministrator.resources.inbox_badge_unread
import com.hereliesaz.geministrator.resources.inbox_badge_unread_many
import com.hereliesaz.geministrator.resources.keeper_room
import com.russhwolf.settings.Settings
import org.jetbrains.compose.resources.stringResource

/** Counts above this show as "99+" in the badge. */
internal const val INBOX_BADGE_CAP = 99

private const val INBOX_READ_STORAGE_KEY = "aive.inbox.read.v1"
private const val INBOX_READ_CAPACITY = 200
private const val INBOX_READ_SEPARATOR = '\n'

/**
 * Stable identities of the things the Inbox lists for [run]: the run's Hall Monitor pause when it
 * has one (the Inbox shows only that report while the run is paused), otherwise each task waiting
 * on a human, a plan approval or a failure escalation.
 *
 * A key names the item, not just the task: the attempt and the kind are part of it, so a task that
 * escalates again after a retry is a new, unread item.
 */
internal fun inboxItemKeys(run: WorkflowRun): List<String> {
    val pause = run.globalPause
    if (pause != null) {
        return listOf(inboxKey("pause", run.id.value, pause.reportArtifactId.value))
    }
    return run.taskRuns.values
        .filter { it.status == TaskRunStatus.AwaitingApproval || it.status == TaskRunStatus.Escalated }
        .sortedBy { it.taskDefinitionId.value }
        .map { taskRun ->
            val kind = if (taskRun.status == TaskRunStatus.AwaitingApproval) "approval" else "escalation"
            inboxKey(kind, run.id.value, taskRun.taskDefinitionId.value, taskRun.attempt.toString())
        }
}

/** The Inbox items for whatever the application is showing; none unless a run is live. */
internal fun inboxItemKeys(state: ApplicationRuntimeState): List<String> =
    (state as? ApplicationRuntimeState.Live)?.presentation?.run?.let(::inboxItemKeys).orEmpty()

private fun inboxKey(vararg parts: String): String =
    parts.joinToString("|") { it.replace(INBOX_READ_SEPARATOR, ' ') }

/** How many of [keys] have not been seen yet. Duplicates count once. */
internal fun unreadInboxCount(keys: Collection<String>, read: Set<String>): Int =
    keys.distinct().count { it !in read }

/** The badge text: no number for zero (the badge stays quiet), "99+" above the cap. */
internal fun inboxBadgeLabel(unread: Int): String? = when {
    unread <= 0 -> null
    unread > INBOX_BADGE_CAP -> "$INBOX_BADGE_CAP+"
    else -> unread.toString()
}

/**
 * Remembers which Inbox items have been seen, durably, so an item is unread until the Inbox has
 * been open while it was listed. Keeps the most recent [capacity] keys so the record cannot grow
 * without bound.
 */
internal class InboxReadLog(
    private val settings: Settings,
    private val capacity: Int = INBOX_READ_CAPACITY,
) {
    private var ordered: List<String> = load()

    /** Keys already seen. Reading it in composition re-reads when [markRead] adds to it. */
    var read: Set<String> by mutableStateOf(ordered.toSet())
        private set

    /** Marks every key in [keys] seen and persists the result. A no-op when all were already seen. */
    fun markRead(keys: Collection<String>) {
        if (keys.all { it in read }) return
        val merged = ordered.toMutableList()
        keys.forEach { key ->
            merged.remove(key)
            merged.add(key)
        }
        ordered = if (merged.size > capacity) merged.takeLast(capacity) else merged
        read = ordered.toSet()
        settings.putString(INBOX_READ_STORAGE_KEY, ordered.joinToString(INBOX_READ_SEPARATOR.toString()))
    }

    private fun load(): List<String> =
        settings.getStringOrNull(INBOX_READ_STORAGE_KEY)
            ?.split(INBOX_READ_SEPARATOR)
            ?.filter { it.isNotEmpty() }
            .orEmpty()
}

/**
 * The header's inbox button. Quiet at zero: just the inbox glyph on the page. With unread items
 * it becomes an Ink pill (white glyph, Yellow count capsule with black text), so every colour is
 * the exact opposite of the surface it sits on. Announces "Inbox, N unread" and, as a live region,
 * re-announces when the count changes.
 */
@Composable
internal fun InboxBadge(
    unread: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = inboxBadgeLabel(unread)
    val description = when {
        unread <= 0 -> stringResource(Res.string.inbox_badge_none)
        unread > INBOX_BADGE_CAP -> stringResource(Res.string.inbox_badge_unread_many, INBOX_BADGE_CAP)
        else -> stringResource(Res.string.inbox_badge_unread, unread)
    }
    val pageSurface = azphaltSurface()
    val fill: Color? = if (label != null) Azphalt.Ink else null
    val surface = fill ?: pageSurface
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            }
            .defaultMinSize(minWidth = AzphaltMinTouchTarget, minHeight = AzphaltMinTouchTarget)
            .clip(RoundedCornerShape(999.dp))
            .then(if (fill != null) Modifier.background(fill) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = if (label != null) 14.dp else 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AzphaltSurface(surface) {
            InboxGlyph(color = surface.onOpposite())
            if (label != null) {
                val cap = Azphalt.Yellow
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(cap)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(label, style = AzphaltType.endCap, color = cap.onOpposite())
                }
            }
        }
    }
}

/** A 24dp inbox tray drawn as a stroked path in [color]. */
@Composable
private fun InboxGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val unit = size.width / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        val tray = Path().apply {
            moveTo(point(3f, 13f).x, point(3f, 13f).y)
            lineTo(point(6f, 4f).x, point(6f, 4f).y)
            lineTo(point(18f, 4f).x, point(18f, 4f).y)
            lineTo(point(21f, 13f).x, point(21f, 13f).y)
            lineTo(point(21f, 20f).x, point(21f, 20f).y)
            lineTo(point(3f, 20f).x, point(3f, 20f).y)
            close()
        }
        val slot = Path().apply {
            moveTo(point(3f, 13f).x, point(3f, 13f).y)
            lineTo(point(8f, 13f).x, point(8f, 13f).y)
            lineTo(point(9.5f, 16f).x, point(9.5f, 16f).y)
            lineTo(point(14.5f, 16f).x, point(14.5f, 16f).y)
            lineTo(point(16f, 13f).x, point(16f, 13f).y)
            lineTo(point(21f, 13f).x, point(21f, 13f).y)
        }
        val stroke = Stroke(width = 2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(tray, color, style = stroke)
        drawPath(slot, color, style = stroke)
    }
}

/**
 * The way into the Keeper's room from a phone's header (docs/Keepers-Room.md): a gear, beside the
 * inbox, so setup is always one tap away without a navigation row.
 */
@Composable
internal fun KeeperButton(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.keeper_room)
    val fill = if (selected) azphaltStrong() else null
    val glyph = fill?.onOpposite() ?: azphaltOnSurface()
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                this.selected = selected
            }
            .size(AzphaltMinTouchTarget)
            .clip(RoundedCornerShape(999.dp))
            .then(if (fill != null) Modifier.background(fill) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val unit = size.width / 24f
            for (i in 0 until 8) {
                val a = i * (kotlin.math.PI / 4).toFloat()
                val dir = Offset(kotlin.math.cos(a), kotlin.math.sin(a))
                drawLine(glyph, c + dir * (7f * unit), c + dir * (10.5f * unit), strokeWidth = 3.2f * unit)
            }
            drawCircle(glyph, radius = 7.5f * unit, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.4f * unit))
            drawCircle(glyph, radius = 2.6f * unit, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * unit))
        }
    }
}
