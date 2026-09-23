package com.hereliesaz.geministrator

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.Weight
import com.hereliesaz.conveyance.compose.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One row of the live-steps feed.
 *
 * [slot] is the row's resting position, 0 = top, `maxRows - 1` = bottom. Rows are bottom-anchored:
 * the newest line always rests in the bottom slot and older lines sit above it. An [exiting] row
 * was just evicted from the top; its slot is -1 (one row above the feed) and it should be drawn
 * fading out while it travels there.
 */
data class LiveStepRow(
    val id: Long,
    val text: String,
    val slot: Int,
    val exiting: Boolean,
)

/**
 * The bookkeeping behind [LiveStepsFeed], kept free of Compose so it can be unit tested.
 *
 * Each [push] produces the complete set of rows the feed should animate toward in ONE motion:
 * the new line at the bottom slot, every retained line one slot higher than before, and -- once
 * the feed is full -- the oldest line marked [LiveStepRow.exiting] at slot -1. Rows that finished
 * exiting on a previous push are dropped (they are already fully transparent).
 */
class LiveStepsFeedModel(val maxRows: Int = 5) {
    init {
        require(maxRows > 0) { "maxRows must be positive" }
    }

    private data class Line(val id: Long, val text: String)

    private data class State(
        val nextId: Long = 0L,
        val lines: List<Line> = emptyList(),
        val rows: List<LiveStepRow> = emptyList(),
    )

    // Pushes arrive from background dispatchers (the on-device planner runs on Default), so the
    // whole transition is one atomic compare-and-set over an immutable snapshot.
    private val state = MutableStateFlow(State())
    private val _rows = MutableStateFlow<List<LiveStepRow>>(emptyList())
    val rows: StateFlow<List<LiveStepRow>> = _rows.asStateFlow()

    /** Appends [text] as the newest line. Blank and consecutive duplicate lines are ignored. */
    fun push(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        state.update { current ->
            if (current.lines.lastOrNull()?.text == clean) return@update current
            val appended = current.lines + Line(current.nextId, clean)
            val evicted = if (appended.size > maxRows) appended.first() else null
            val kept = if (evicted != null) appended.drop(1) else appended
            val base = maxRows - kept.size
            val visible = kept.mapIndexed { index, line ->
                LiveStepRow(line.id, line.text, slot = base + index, exiting = false)
            }
            State(
                nextId = current.nextId + 1,
                lines = kept,
                rows = listOfNotNull(evicted?.let { LiveStepRow(it.id, it.text, slot = -1, exiting = true) }) + visible,
            )
        }
        // Publish the latest snapshot (not this call's result) so concurrent pushes converge.
        _rows.value = state.value.rows
    }

    fun clear() {
        state.update { State(nextId = it.nextId) }
        _rows.value = emptyList()
    }
}

/**
 * A small bottom-anchored feed of up to [LiveStepsFeedModel.maxRows] status lines.
 *
 * Every row animates its vertical slot and its alpha with the SAME Conveyance motion spec
 * ([Motion.spec] at [Weight.Medium] -- clean settle, no overshoot) and each push retargets all rows
 * in the same frame, so the new line's fade-in-and-rise from one row below the bottom, the shift
 * of every retained line up one slot, and the oldest line's fade-out as it rises off the top are
 * one coordinated animation rather than sequential steps.
 */
@Composable
fun LiveStepsFeed(
    rows: List<LiveStepRow>,
    maxRows: Int,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 22.dp,
    color: Color = Color.White,
) {
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    Box(modifier.fillMaxWidth().height(rowHeight * maxRows)) {
        rows.forEach { row ->
            key(row.id) {
                // A row is born one slot below its resting position, fully transparent.
                val slot = remember { Animatable((row.slot + 1).toFloat()) }
                val alpha = remember { Animatable(0f) }
                val targetSlot = row.slot.toFloat()
                val targetAlpha = if (row.exiting) 0f else 1f
                LaunchedEffect(targetSlot, targetAlpha) {
                    launch { slot.animateTo(targetSlot, Motion.spec(Weight.Medium)) }
                    launch { alpha.animateTo(targetAlpha, Motion.spec(Weight.Medium)) }
                }
                Text(
                    text = row.text,
                    color = color,
                    style = AzphaltType.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .graphicsLayer {
                            translationY = slot.value * rowHeightPx
                            this.alpha = alpha.value.coerceIn(0f, 1f)
                        },
                )
            }
        }
    }
}

/**
 * Blurs whatever this modifier is applied to while a launch is in flight.
 *
 * [blur] is a real Gaussian blur on Android 12+ (RenderEffect) and on Skia targets (desktop/web);
 * on Android 11 and below Compose silently ignores it, which is why [LaunchStepsOverlay] also
 * draws a dark scrim -- legibility never depends on the blur alone.
 */
@Composable
internal fun Modifier.launchBackdrop(active: Boolean): Modifier {
    val radius by animateDpAsState(
        targetValue = if (active) 14.dp else 0.dp,
        animationSpec = Motion.spec(Weight.Medium),
        label = "launch-backdrop-blur",
    )
    return if (radius > 0.dp) blur(radius) else this
}

/**
 * The launch-in-progress overlay: a darkening scrim over the (blurred) app that swallows taps,
 * with the white [LiveStepsFeed] centred on it.
 */
@Composable
internal fun LaunchStepsOverlay(
    rows: List<LiveStepRow>,
    maxRows: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.widthIn(max = 520.dp)) {
            Text(
                "STARTING RUN",
                style = AzphaltType.eyebrow,
                color = Color.White,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            LiveStepsFeed(rows = rows, maxRows = maxRows, color = Color.White)
        }
    }
}
