package com.hereliesaz.geministrator

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState as S
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AxonTest {

    @Test
    fun anAxonReadsTheClaspBetweenItsCreatures() {
        // The upstream finished and the downstream picks its result up: complete.
        assertEquals(AxonState.Complete, axonState(clasp(S.Complete, S.Active)))
        assertEquals(AxonState.Complete, axonState(clasp(S.Complete, S.Complete)))
        // The upstream works and the downstream waits on it: active.
        assertEquals(AxonState.Active, axonState(clasp(S.Active, S.Pending)))
        assertEquals(AxonState.Active, axonState(clasp(S.Gate, S.Ready)))
        // Nothing runs yet: waiting.
        assertEquals(AxonState.Waiting, axonState(clasp(S.Pending, S.Pending)))
        assertEquals(AxonState.Waiting, axonState(clasp(S.Ready, S.Pending)))
        // A failed upstream or a blocked downstream: failed.
        assertEquals(AxonState.Failed, axonState(clasp(S.Failed, S.Pending)))
        assertEquals(AxonState.Failed, axonState(clasp(S.Active, S.Blocked)))
    }

    @Test
    fun onlyAGateRingsTheReceptor() {
        assertTrue(axonGated(S.Gate))
        S.entries.filter { it != S.Gate }.forEach { assertFalse(axonGated(it), "$it") }
    }

    @Test
    fun vesiclesRideCompleteAndActiveAxonsUnlessMotionIsReduced() {
        assertEquals(listOf(Vesicle(0.25f, 0.84f)), axonVesicles(AxonState.Complete, 0.25f, reduceMotion = false))
        assertEquals(listOf(Vesicle(0.75f, 1f), Vesicle(0.25f, 0.67f)), axonVesicles(AxonState.Active, 0.75f, reduceMotion = false))
        assertTrue(axonVesicles(AxonState.Waiting, 0.5f, reduceMotion = false).isEmpty())
        assertTrue(axonVesicles(AxonState.Failed, 0.5f, reduceMotion = false).isEmpty())
        AxonState.entries.forEach { assertTrue(axonVesicles(it, 0.5f, reduceMotion = true).isEmpty(), "$it") }
    }

    @Test
    fun theAxonRunsFromTerminalToReceptorWithABow() {
        val a = Offset(0f, 0f)
        val b = Offset(100f, 0f)
        val control = axonControl(a, b)
        assertEquals(a, axonPoint(a, control, b, 0f))
        assertEquals(b, axonPoint(a, control, b, 1f))
        // Its middle bows off the straight run, by a gentle share of the length.
        val mid = axonPoint(a, control, b, 0.5f)
        assertEquals(50f, mid.x, 0.001f)
        assertEquals(-100f * AXON_BOW / 2f, mid.y, 0.001f)
        val line = axonPolyline(a, b, segments = 4)
        assertEquals(5, line.size)
        assertEquals(a, line.first())
        assertEquals(b, line.last())
    }
}
