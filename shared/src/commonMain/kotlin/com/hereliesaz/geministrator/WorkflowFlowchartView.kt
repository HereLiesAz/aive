package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.workflow.FlowNode
import com.hereliesaz.geministrator.workflow.WorkflowFlowchart

private val NodeWidth = 176.dp
private val NodeHeight = 76.dp
private val ColumnGap = 64.dp
private val RowGap = 18.dp
private val Pad = 16.dp

/**
 * The workflow as boxes and arrows, left to right: a step sits to the right of everything it waits
 * on. With a run, each box wears its step's state (the same colours as a creature's chip) and the
 * words for it; tapping a box selects that creature. It draws what [WorkflowFlowchart] says and
 * decides nothing: moving a box edits nothing.
 */
@Composable
internal fun WorkflowFlowchartView(
    flow: WorkflowFlowchart,
    selectedTaskId: String?,
    onTaskSelected: (String) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    if (flow.nodes.isEmpty()) {
        Text(emptyText, style = AzphaltType.body, color = azphaltOnSurface(), modifier = modifier)
        return
    }
    val textMeasurer = rememberTextMeasurer()
    val line = azphaltOnSurface()
    val labelColor = azphaltMutedOnSurface()
    val totalWidth = Pad * 2 + NodeWidth * flow.levels + ColumnGap * (flow.levels - 1).coerceAtLeast(0)
    val totalHeight = Pad * 2 + NodeHeight * flow.rows + RowGap * (flow.rows - 1).coerceAtLeast(0)
    val positions = remember(flow) { flow.nodes.associate { it.id to (it.level to it.row) } }

    Box(modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
        Box(Modifier.size(totalWidth, totalHeight)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = NodeWidth.toPx()
                val h = NodeHeight.toPx()
                val colStep = w + ColumnGap.toPx()
                val rowStep = h + RowGap.toPx()
                val pad = Pad.toPx()
                fun left(level: Int) = pad + colStep * level
                fun top(row: Int) = pad + rowStep * row
                flow.edges.forEach { edge ->
                    val from = positions[edge.from] ?: return@forEach
                    val to = positions[edge.to] ?: return@forEach
                    val x1 = left(from.first) + w
                    val y1 = top(from.second) + h / 2
                    val x2 = left(to.first)
                    val y2 = top(to.second) + h / 2
                    val mid = (x1 + x2) / 2
                    drawPath(
                        Path().apply {
                            moveTo(x1, y1)
                            cubicTo(mid, y1, mid, y2, x2, y2)
                        },
                        color = line.copy(alpha = 0.7f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    val head = 8.dp.toPx()
                    drawPath(
                        Path().apply {
                            moveTo(x2, y2)
                            lineTo(x2 - head, y2 - head * 0.6f)
                            lineTo(x2 - head, y2 + head * 0.6f)
                            close()
                        },
                        color = line.copy(alpha = 0.7f),
                    )
                    edge.label?.let { label ->
                        val measured = textMeasurer.measure(label, AzphaltType.endCap.copy(fontSize = 10.sp, color = labelColor))
                        drawText(measured, topLeft = Offset(mid - measured.size.width / 2f, (y1 + y2) / 2 - measured.size.height - 2.dp.toPx()))
                    }
                }
            }
            flow.nodes.forEach { node ->
                FlowBox(
                    node = node,
                    selected = node.id.value == selectedTaskId,
                    onClick = { onTaskSelected(node.id.value) },
                    modifier = Modifier
                        .offset(Pad + (NodeWidth + ColumnGap) * node.level, Pad + (NodeHeight + RowGap) * node.row)
                        .size(NodeWidth, NodeHeight),
                )
            }
        }
    }
}

@Composable
private fun FlowBox(node: FlowNode, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    // A step that was cancelled is usually one a condition skipped ("if it fails" when it did not), so it
    // is shown dimmed rather than as a failure; only a step that failed or was escalated is red.
    val skipped = node.status == TaskRunStatus.Cancelled
    val fill = when {
        skipped -> Azphalt.Ink.copy(alpha = 0.5f)
        else -> when (creatureStateTone(node.status)) {
            CreatureStateTone.Working -> Azphalt.Yellow
            CreatureStateTone.Complete -> ClaspGreen
            CreatureStateTone.Waiting -> Azphalt.Ink
            CreatureStateTone.Gated, CreatureStateTone.Failed -> AzphaltDanger
        }
    }
    val text = if (skipped) Color.White else fill.onOpposite()
    val state = node.status?.label()
    val who = listOfNotNull(node.role, node.kind.takeIf { node.role == null }).joinToString(" · ")
    val description = listOfNotNull(node.name, who.takeIf { it.isNotEmpty() }, state).joinToString(", ")
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .border(if (selected) 3.dp else 1.dp, if (selected) azphaltStrong() else Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(node.name, style = AzphaltType.body.copy(fontSize = 13.sp), color = text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (who.isNotEmpty()) {
            Text(who, style = AzphaltType.endCap.copy(fontSize = 10.sp), color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        state?.let { Text(it.uppercase(), style = AzphaltType.endCap.copy(fontSize = 10.sp), color = text, maxLines = 1) }
    }
}
