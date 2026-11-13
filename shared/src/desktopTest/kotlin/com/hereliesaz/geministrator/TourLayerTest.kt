package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.hereliesaz.geministrator.tour.ExpectKind
import com.hereliesaz.geministrator.tour.LessonPayload
import com.hereliesaz.geministrator.tour.StepOutcome
import com.hereliesaz.geministrator.tour.TourController
import com.hereliesaz.geministrator.tour.TourExpect
import com.hereliesaz.geministrator.tour.TourOverlayHost
import com.hereliesaz.geministrator.tour.TourPhase
import com.hereliesaz.geministrator.tour.TourTargetId
import com.hereliesaz.geministrator.tour.TourTargetRegistry
import com.hereliesaz.geministrator.tour.tourTarget
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TourLayerTest {
    private val lesson = LessonPayload(
        target = "MESSAGES_BUTTON",
        sentence = "<script>alert(1)</script> [link](http://x.test)",
        expect = TourExpect(ExpectKind.Tapped, "MESSAGES_BUTTON"),
        explain = "e",
        ifMissed = "m",
        checkIn = "Are you with me so far?",
        anotherWay = "a",
        smaller = listOf("x", "y"),
    )

    @AfterTest
    fun reset() = TourController.stop()

    private fun withScene(body: (ImageComposeScene) -> Unit) {
        ImageComposeScene(width = 400, height = 300, density = Density(1f)) {
            LoadAzphaltFonts()
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(80.dp).background(Color.Gray).clickable { }.tourTarget(TourTargetId.MESSAGES_BUTTON))
                TourOverlayHost()
            }
        }.use(body)
    }

    @Test
    fun withNoTourTheLayerDrawsNothingAndNothingIsReported() = withScene { scene ->
        scene.render(0L)
        scene.render(100_000_000L)
        assertNull(TourController.state)
        assertNotNull(TourTargetRegistry.boundsOf(TourTargetId.MESSAGES_BUTTON))
    }

    @Test
    fun aTapOnTheRealControlPassesThroughTheLayerAndAdvancesTheTour() = withScene { scene ->
        TourController.start(runKey = null, total = 2)
        TourController.addLesson(lesson)
        scene.render(0L)
        scene.render(100_000_000L)
        val center = TourTargetRegistry.boundsOf(TourTargetId.MESSAGES_BUTTON)!!.center
        scene.sendPointerEvent(PointerEventType.Press, center)
        scene.sendPointerEvent(PointerEventType.Release, center)
        scene.render(200_000_000L)
        assertEquals(1, TourController.state!!.index)
        assertEquals(StepOutcome.Met, TourController.state!!.outcomes[0])
    }

    @Test
    fun escapeHidesTheLayerWithoutAdvancingAndTapsAreNoLongerReported() = withScene { scene ->
        TourController.start(runKey = null, total = 1)
        TourController.addLesson(lesson)
        scene.render(0L)
        TourController.escape()
        scene.render(100_000_000L)
        val center = TourTargetRegistry.boundsOf(TourTargetId.MESSAGES_BUTTON)!!.center
        scene.sendPointerEvent(PointerEventType.Press, center)
        scene.sendPointerEvent(PointerEventType.Release, center)
        scene.render(200_000_000L)
        assertEquals(TourPhase.Dismissed, TourController.state!!.phase)
        assertEquals(0, TourController.state!!.index)
    }
}
