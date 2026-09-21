package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.geministrator.workflow.AiveFlowchartGraph
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Composable
internal fun MermaidFlowchartPreview(
    graph: AiveFlowchartGraph,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.onSurface
    val fillColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val layout = remember(graph) { graph.previewLayout() }

    Canvas(modifier = modifier.height(320.dp)) {
        if (layout.nodes.isEmpty()) return@Canvas
        val nodeWidth = 132.dp.toPx()
        val nodeHeight = 58.dp.toPx()
        val horizontal = graph.direction.uppercase() in setOf("LR", "RL")
        val maxPrimary = max(1, layout.maxLevel)
        val paddingX = 24.dp.toPx()
        val paddingY = 24.dp.toPx()
        val usableWidth = (size.width - 2 * paddingX - nodeWidth).coerceAtLeast(1f)
        val usableHeight = (size.height - 2 * paddingY - nodeHeight).coerceAtLeast(1f)

        fun center(node: PreviewNode): Offset {
            val siblings = layout.nodesByLevel[node.level].orEmpty()
            val siblingIndex = siblings.indexOfFirst { it.id == node.id }.coerceAtLeast(0)
            val secondaryFraction = (siblingIndex + 1f) / (siblings.size + 1f)
            val primaryFraction = if (maxPrimary == 0) 0.5f else node.level.toFloat() / maxPrimary.toFloat()
            val normalizedPrimary = when (graph.direction.uppercase()) {
                "RL", "BT" -> 1f - primaryFraction
                else -> primaryFraction
            }
            return if (horizontal) {
                Offset(
                    x = paddingX + nodeWidth / 2 + usableWidth * normalizedPrimary,
                    y = paddingY + nodeHeight / 2 + usableHeight * secondaryFraction,
                )
            } else {
                Offset(
                    x = paddingX + nodeWidth / 2 + usableWidth * secondaryFraction,
                    y = paddingY + nodeHeight / 2 + usableHeight * normalizedPrimary,
                )
            }
        }

        val centers = layout.nodes.associate { it.id to center(it) }

        graph.edges.forEach { edge ->
            val from = centers[edge.from] ?: return@forEach
            val to = centers[edge.to] ?: return@forEach
            val vector = to - from
            val distance = vector.getDistance().coerceAtLeast(1f)
            val inset = if (horizontal) nodeWidth / 2 else nodeHeight / 2
            val unit = Offset(vector.x / distance, vector.y / distance)
            val start = from + unit * inset
            val end = to - unit * inset

            if (edge.style == "dotted") {
                val segments = 10
                repeat(segments) { index ->
                    if (index % 2 == 0) {
                        val a = index.toFloat() / segments
                        val b = (index + 1).toFloat() / segments
                        drawLine(
                            color = lineColor,
                            start = start + (end - start) * a,
                            end = start + (end - start) * b,
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }
                }
            } else {
                drawLine(
                    color = lineColor,
                    start = start,
                    end = end,
                    strokeWidth = 1.5.dp.toPx(),
                )
            }

            if (edge.style != "line") {
                val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
                val arrow = 9.dp.toPx()
                val wing = 0.55
                val left = Offset(
                    x = end.x - (arrow * cos(angle - wing)).toFloat(),
                    y = end.y - (arrow * sin(angle - wing)).toFloat(),
                )
                val right = Offset(
                    x = end.x - (arrow * cos(angle + wing)).toFloat(),
                    y = end.y - (arrow * sin(angle + wing)).toFloat(),
                )
                drawLine(lineColor, end, left, 1.5.dp.toPx())
                drawLine(lineColor, end, right, 1.5.dp.toPx())
            }

            edge.label?.takeIf(String::isNotBlank)?.let { label ->
                val measured = textMeasurer.measure(
                    text = label,
                    style = TextStyle(color = lineColor, fontSize = 10.sp),
                )
                val midpoint = Offset((start.x + end.x) / 2, (start.y + end.y) / 2)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        midpoint.x - measured.size.width / 2,
                        midpoint.y - measured.size.height - 3.dp.toPx(),
                    ),
                )
            }
        }

        layout.nodes.forEach { node ->
            val center = centers[node.id] ?: return@forEach
            val left = center.x - nodeWidth / 2
            val top = center.y - nodeHeight / 2
            val rect = Rect(left, top, left + nodeWidth, top + nodeHeight)

            when (node.shape) {
                "circle" -> {
                    val radius = minOf(nodeHeight, nodeWidth) / 2
                    drawCircle(fillColor, radius, center)
                    drawCircle(lineColor, radius, center, style = Stroke(1.5.dp.toPx()))
                }
                "decision" -> {
                    val path = Path().apply {
                        moveTo(center.x, top)
                        lineTo(left + nodeWidth, center.y)
                        lineTo(center.x, top + nodeHeight)
                        lineTo(left, center.y)
                        close()
                    }
                    drawPath(path, fillColor)
                    drawPath(path, lineColor, style = Stroke(1.5.dp.toPx()))
                }
                "rounded", "stadium" -> {
                    val radius = if (node.shape == "stadium") nodeHeight / 2 else 12.dp.toPx()
                    drawRoundRect(
                        color = fillColor,
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(nodeWidth, nodeHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    )
                    drawRoundRect(
                        color = lineColor,
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(nodeWidth, nodeHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
                else -> {
                    drawRect(fillColor, Offset(left, top), androidx.compose.ui.geometry.Size(nodeWidth, nodeHeight))
                    drawRect(
                        lineColor,
                        Offset(left, top),
                        androidx.compose.ui.geometry.Size(nodeWidth, nodeHeight),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
            }

            val measured = textMeasurer.measure(
                text = node.label.take(32),
                style = TextStyle(color = textColor, fontSize = 11.sp),
                maxLines = 2,
            )
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    center.x - measured.size.width / 2,
                    center.y - measured.size.height / 2,
                ),
            )
        }
    }
}

private data class PreviewNode(
    val id: String,
    val label: String,
    val shape: String,
    val level: Int,
)

private data class PreviewLayout(
    val nodes: List<PreviewNode>,
    val nodesByLevel: Map<Int, List<PreviewNode>>,
    val maxLevel: Int,
)

private fun AiveFlowchartGraph.previewLayout(): PreviewLayout {
    val incoming = nodes.associate { it.id to 0 }.toMutableMap()
    edges.forEach { edge ->
        if (edge.to in incoming && edge.from in incoming) {
            incoming[edge.to] = (incoming[edge.to] ?: 0) + 1
        }
    }
    val level = nodes.associate { it.id to 0 }.toMutableMap()
    val queue = ArrayDeque(nodes.filter { incoming[it.id] == 0 }.map { it.id })
    val outgoing = edges.groupBy { it.from }
    val visited = mutableSetOf<String>()

    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!visited.add(id)) continue
        val sourceLevel = level[id] ?: 0
        outgoing[id].orEmpty().forEach { edge ->
            level[edge.to] = max(level[edge.to] ?: 0, sourceLevel + 1)
            incoming[edge.to] = (incoming[edge.to] ?: 1) - 1
            if ((incoming[edge.to] ?: 0) <= 0) queue.addLast(edge.to)
        }
    }

    nodes.filterNot { it.id in visited }.forEachIndexed { index, node ->
        level[node.id] = max(level[node.id] ?: 0, index % 3)
    }

    val previewNodes = nodes.map { node ->
        PreviewNode(
            id = node.id,
            label = node.label,
            shape = node.shape,
            level = level[node.id] ?: 0,
        )
    }
    return PreviewLayout(
        nodes = previewNodes,
        nodesByLevel = previewNodes.groupBy(PreviewNode::level),
        maxLevel = previewNodes.maxOfOrNull(PreviewNode::level) ?: 0,
    )
}
