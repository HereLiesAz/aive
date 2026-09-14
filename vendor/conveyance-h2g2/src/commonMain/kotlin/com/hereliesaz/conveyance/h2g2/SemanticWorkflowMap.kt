package com.hereliesaz.conveyance.h2g2

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.floor

/**
 * Deliberately discrete workflow-map zoom. There is no free camera scale or pan: navigation moves
 * through semantic areas so the map remains understandable and repeatable.
 */
enum class H2g2WorkflowZoomLevel {
    Overview,
    Band,
    Node,
}

class H2g2WorkflowViewportState internal constructor() {
    var zoomLevel: H2g2WorkflowZoomLevel by mutableStateOf(H2g2WorkflowZoomLevel.Overview)
        private set

    var focusedBandIndex: Int? by mutableStateOf(null)
        private set

    var focusedNodeId: String? by mutableStateOf(null)
        private set

    val canZoomOut: Boolean
        get() = zoomLevel != H2g2WorkflowZoomLevel.Overview

    fun reset() {
        zoomLevel = H2g2WorkflowZoomLevel.Overview
        focusedBandIndex = null
        focusedNodeId = null
    }

    fun focusBand(index: Int) {
        focusedBandIndex = index
        focusedNodeId = null
        zoomLevel = H2g2WorkflowZoomLevel.Band
    }

    fun focusNode(nodeId: String, bandIndex: Int) {
        focusedBandIndex = bandIndex
        focusedNodeId = nodeId
        zoomLevel = H2g2WorkflowZoomLevel.Node
    }

    fun zoomOut(): Boolean = when (zoomLevel) {
        H2g2WorkflowZoomLevel.Overview -> false
        H2g2WorkflowZoomLevel.Band -> {
            reset()
            true
        }
        H2g2WorkflowZoomLevel.Node -> {
            zoomLevel = H2g2WorkflowZoomLevel.Band
            focusedNodeId = null
            true
        }
    }

    internal fun coerceTo(bands: List<H2g2WorkflowBand>) {
        if (bands.isEmpty()) {
            reset()
            return
        }
        val bandIndex = focusedBandIndex
        if (bandIndex != null && bandIndex !in bands.indices) {
            reset()
            return
        }
        val nodeId = focusedNodeId
        if (nodeId != null && bands.none { band -> band.nodes.any { it.id == nodeId } }) {
            if (bandIndex != null) focusBand(bandIndex) else reset()
        }
    }
}

@Composable
fun rememberH2g2WorkflowViewportState(): H2g2WorkflowViewportState =
    remember { H2g2WorkflowViewportState() }

internal data class H2g2SemanticWorkflowView(
    val bands: List<H2g2WorkflowBand>,
    val edges: List<H2g2WorkflowEdge>,
    val sourceBandIndices: List<Int>,
    val selectedId: String?,
)

private const val BandSummaryPrefix = "__h2g2_band__"
private const val ZoomInThreshold = 1.16f
private const val ZoomOutThreshold = 0.86f

/**
 * Workflow renderer with semantic, snap-to-area navigation.
 *
 * Overview renders one summary subject per topological band. Selecting or pinching outward over a
 * summary moves to a fixed band-level view with neighboring context. Selecting/pinching outward
 * again moves to a fixed node-neighborhood view. Pinching inward or pressing system Back retreats
 * exactly one semantic level. A single gesture can never skip multiple levels.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun H2g2SemanticWorkflowMap(
    bands: List<H2g2WorkflowBand>,
    edges: List<H2g2WorkflowEdge>,
    selectedId: String? = null,
    onNodeSelected: (H2g2WorkflowNode) -> Unit = {},
    state: H2g2WorkflowViewportState = rememberH2g2WorkflowViewportState(),
    modifier: Modifier = Modifier,
) {
    if (bands.isEmpty()) return

    val nodeBandIndices = remember(bands) {
        buildMap {
            bands.forEachIndexed { bandIndex, band ->
                band.nodes.forEach { node -> put(node.id, bandIndex) }
            }
        }
    }

    LaunchedEffect(bands) {
        state.coerceTo(bands)
    }
    LaunchedEffect(selectedId) {
        val bandIndex = selectedId?.let(nodeBandIndices::get)
        if (selectedId != null && bandIndex != null && selectedId != state.focusedNodeId) {
            state.focusNode(selectedId, bandIndex)
        }
    }

    BackHandler(enabled = state.canZoomOut) {
        state.zoomOut()
    }

    val semanticView = remember(bands, edges, state.zoomLevel, state.focusedBandIndex, state.focusedNodeId) {
        buildSemanticWorkflowView(
            bands = bands,
            edges = edges,
            zoomLevel = state.zoomLevel,
            focusedBandIndex = state.focusedBandIndex,
            focusedNodeId = state.focusedNodeId,
        )
    }

    val fixedScale by animateFloatAsState(
        targetValue = when (state.zoomLevel) {
            H2g2WorkflowZoomLevel.Overview -> .92f
            H2g2WorkflowZoomLevel.Band -> .98f
            H2g2WorkflowZoomLevel.Node -> 1.04f
        },
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "h2g2-semantic-zoom",
    )

    H2g2WorkflowMap(
        bands = semanticView.bands,
        edges = semanticView.edges,
        selectedId = semanticView.selectedId,
        onNodeSelected = { node ->
            when (state.zoomLevel) {
                H2g2WorkflowZoomLevel.Overview -> {
                    bandIndexFromSummaryId(node.id)?.let(state::focusBand)
                }
                H2g2WorkflowZoomLevel.Band,
                H2g2WorkflowZoomLevel.Node,
                -> {
                    nodeBandIndices[node.id]?.let { bandIndex ->
                        state.focusNode(node.id, bandIndex)
                        onNodeSelected(node)
                    }
                }
            }
        },
        modifier = modifier
            .semanticZoomGesture(
                bands = bands,
                semanticView = semanticView,
                state = state,
                onNodeSelected = { node ->
                    nodeBandIndices[node.id]?.let { bandIndex ->
                        state.focusNode(node.id, bandIndex)
                        onNodeSelected(node)
                    }
                },
            )
            .graphicsLayer {
                scaleX = fixedScale
                scaleY = fixedScale
            },
    )
}

internal fun buildSemanticWorkflowView(
    bands: List<H2g2WorkflowBand>,
    edges: List<H2g2WorkflowEdge>,
    zoomLevel: H2g2WorkflowZoomLevel,
    focusedBandIndex: Int?,
    focusedNodeId: String?,
): H2g2SemanticWorkflowView {
    if (bands.isEmpty()) return H2g2SemanticWorkflowView(emptyList(), emptyList(), emptyList(), null)

    val nodeBandIndices = buildMap {
        bands.forEachIndexed { bandIndex, band ->
            band.nodes.forEach { node -> put(node.id, bandIndex) }
        }
    }

    return when (zoomLevel) {
        H2g2WorkflowZoomLevel.Overview -> {
            val sourceIndices = bands.indices.filter { bands[it].nodes.isNotEmpty() }
            val overviewBands = sourceIndices.map { index ->
                H2g2WorkflowBand(listOf(bandSummaryNode(index, bands[index])))
            }
            val visibleSourceIndices = sourceIndices.toSet()
            val overviewEdges = edges
                .mapNotNull { edge ->
                    val fromBand = nodeBandIndices[edge.from] ?: return@mapNotNull null
                    val toBand = nodeBandIndices[edge.to] ?: return@mapNotNull null
                    if (fromBand == toBand || fromBand !in visibleSourceIndices || toBand !in visibleSourceIndices) {
                        return@mapNotNull null
                    }
                    H2g2WorkflowEdge(
                        from = bandSummaryId(fromBand),
                        to = bandSummaryId(toBand),
                    )
                }
                .distinctBy { it.from to it.to }
            H2g2SemanticWorkflowView(
                bands = overviewBands,
                edges = overviewEdges,
                sourceBandIndices = sourceIndices,
                selectedId = null,
            )
        }

        H2g2WorkflowZoomLevel.Band -> {
            val focus = (focusedBandIndex ?: 0).coerceIn(bands.indices)
            val sourceIndices = ((focus - 1).coerceAtLeast(0)..(focus + 1).coerceAtMost(bands.lastIndex)).toList()
            subsetView(
                bands = bands,
                edges = edges,
                sourceBandIndices = sourceIndices,
                allowedNodeIds = null,
                selectedId = null,
            )
        }

        H2g2WorkflowZoomLevel.Node -> {
            val nodeId = focusedNodeId
            val focusBand = (nodeId?.let(nodeBandIndices::get) ?: focusedBandIndex ?: 0).coerceIn(bands.indices)
            if (nodeId == null) {
                subsetView(
                    bands = bands,
                    edges = edges,
                    sourceBandIndices = listOf(focusBand),
                    allowedNodeIds = null,
                    selectedId = null,
                )
            } else {
                val related = buildSet {
                    add(nodeId)
                    edges.forEach { edge ->
                        if (edge.from == nodeId) add(edge.to)
                        if (edge.to == nodeId) add(edge.from)
                    }
                }
                val sourceIndices = related
                    .mapNotNull(nodeBandIndices::get)
                    .distinct()
                    .sorted()
                subsetView(
                    bands = bands,
                    edges = edges,
                    sourceBandIndices = sourceIndices.ifEmpty { listOf(focusBand) },
                    allowedNodeIds = related,
                    selectedId = nodeId,
                )
            }
        }
    }
}

private fun subsetView(
    bands: List<H2g2WorkflowBand>,
    edges: List<H2g2WorkflowEdge>,
    sourceBandIndices: List<Int>,
    allowedNodeIds: Set<String>?,
    selectedId: String?,
): H2g2SemanticWorkflowView {
    val retainedSourceIndices = mutableListOf<Int>()
    val filteredBands = sourceBandIndices.mapNotNull { sourceIndex ->
        val nodes = bands[sourceIndex].nodes.filter { allowedNodeIds == null || it.id in allowedNodeIds }
        if (nodes.isEmpty()) {
            null
        } else {
            retainedSourceIndices += sourceIndex
            H2g2WorkflowBand(nodes)
        }
    }
    val visibleNodeIds = filteredBands.flatMap { it.nodes }.mapTo(mutableSetOf()) { it.id }
    return H2g2SemanticWorkflowView(
        bands = filteredBands,
        edges = edges.filter { it.from in visibleNodeIds && it.to in visibleNodeIds },
        sourceBandIndices = retainedSourceIndices,
        selectedId = selectedId?.takeIf(visibleNodeIds::contains),
    )
}

private fun bandSummaryNode(index: Int, band: H2g2WorkflowBand): H2g2WorkflowNode {
    val first = band.nodes.first()
    val taskCount = band.nodes.size
    val completeCount = band.nodes.count { it.state == H2g2WorkflowState.Complete }
    return H2g2WorkflowNode(
        id = bandSummaryId(index),
        label = if (taskCount == 1) first.label else "${first.label} + ${taskCount - 1}",
        subtitle = "Stage ${index + 1} · $taskCount ${if (taskCount == 1) "task" else "tasks"}",
        hueSeed = first.hueSeed,
        motionSeed = "semantic-band-$index",
        state = aggregateState(band.nodes),
        progress = completeCount.toFloat() / taskCount.toFloat(),
        injected = band.nodes.any(H2g2WorkflowNode::injected),
        detail = null,
    )
}

private fun aggregateState(nodes: List<H2g2WorkflowNode>): H2g2WorkflowState = when {
    nodes.any { it.state == H2g2WorkflowState.Failed } -> H2g2WorkflowState.Failed
    nodes.any { it.state == H2g2WorkflowState.Active } -> H2g2WorkflowState.Active
    nodes.any { it.state == H2g2WorkflowState.Blocked } -> H2g2WorkflowState.Blocked
    nodes.any { it.state == H2g2WorkflowState.Gate } -> H2g2WorkflowState.Gate
    nodes.any { it.state == H2g2WorkflowState.Ready } -> H2g2WorkflowState.Ready
    nodes.all { it.state == H2g2WorkflowState.Complete } -> H2g2WorkflowState.Complete
    else -> H2g2WorkflowState.Pending
}

private fun bandSummaryId(index: Int): String = "$BandSummaryPrefix$index"

private fun bandIndexFromSummaryId(id: String): Int? =
    if (id.startsWith(BandSummaryPrefix)) id.removePrefix(BandSummaryPrefix).toIntOrNull() else null

private fun Modifier.semanticZoomGesture(
    bands: List<H2g2WorkflowBand>,
    semanticView: H2g2SemanticWorkflowView,
    state: H2g2WorkflowViewportState,
    onNodeSelected: (H2g2WorkflowNode) -> Unit,
): Modifier = pointerInput(bands, semanticView, state.zoomLevel, state.focusedBandIndex, state.focusedNodeId) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var cumulativeZoom = 1f
        var lastCentroid = Offset.Unspecified
        var triggered = false
        var event: PointerEvent
        do {
            event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom.isFinite() && zoom > 0f) cumulativeZoom *= zoom
                val centroid = event.calculateCentroid(useCurrent = true)
                if (centroid.isFinitePoint()) lastCentroid = centroid

                if (!triggered && cumulativeZoom >= ZoomInThreshold) {
                    semanticZoomInAt(
                        centroid = lastCentroid,
                        viewportWidth = size.width.toFloat(),
                        viewportHeight = size.height.toFloat(),
                        bands = bands,
                        semanticView = semanticView,
                        state = state,
                        onNodeSelected = onNodeSelected,
                    )
                    triggered = true
                    event.changes.forEach { it.consume() }
                } else if (!triggered && cumulativeZoom <= ZoomOutThreshold) {
                    state.zoomOut()
                    triggered = true
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

private fun semanticZoomInAt(
    centroid: Offset,
    viewportWidth: Float,
    viewportHeight: Float,
    bands: List<H2g2WorkflowBand>,
    semanticView: H2g2SemanticWorkflowView,
    state: H2g2WorkflowViewportState,
    onNodeSelected: (H2g2WorkflowNode) -> Unit,
) {
    if (!centroid.isFinitePoint() || viewportWidth <= 0f || viewportHeight <= 0f) return
    when (state.zoomLevel) {
        H2g2WorkflowZoomLevel.Overview -> {
            val visibleIndices = semanticView.sourceBandIndices
            if (visibleIndices.isEmpty()) return
            val normalizedY = (centroid.y / viewportHeight).coerceIn(0f, .9999f)
            val visibleIndex = floor(normalizedY * visibleIndices.size).toInt().coerceIn(visibleIndices.indices)
            state.focusBand(visibleIndices[visibleIndex])
        }

        H2g2WorkflowZoomLevel.Band,
        H2g2WorkflowZoomLevel.Node,
        -> {
            val visibleBandIndex = nearestVisibleBandIndex(centroid.y, viewportHeight, semanticView)
            val band = semanticView.bands.getOrNull(visibleBandIndex) ?: return
            if (band.nodes.isEmpty()) return
            val normalizedX = (centroid.x / viewportWidth).coerceIn(0f, .9999f)
            val nodeIndex = floor(normalizedX * band.nodes.size).toInt().coerceIn(band.nodes.indices)
            onNodeSelected(band.nodes[nodeIndex])
        }
    }
}

private fun nearestVisibleBandIndex(
    y: Float,
    viewportHeight: Float,
    semanticView: H2g2SemanticWorkflowView,
): Int {
    if (semanticView.bands.size <= 1) return 0
    val normalizedY = (y / viewportHeight).coerceIn(0f, .9999f)
    return floor(normalizedY * semanticView.bands.size).toInt().coerceIn(semanticView.bands.indices)
}

private fun Offset.isFinitePoint(): Boolean = x.isFinite() && y.isFinite()
