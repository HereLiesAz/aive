package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.CardFlipEvent.Back
import com.hereliesaz.geministrator.CardFlipEvent.OtherNodeTapped
import com.hereliesaz.geministrator.CardFlipEvent.SheetExpanded
import com.hereliesaz.geministrator.CardFlipEvent.SheetPeeked
import com.hereliesaz.geministrator.CardFlipEvent.SwipeAny
import com.hereliesaz.geministrator.CardFlipEvent.Tap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardFlipTest {
    private val front = CardFlip()
    private val peek = CardFlip("a")
    private val expanded = CardFlip("a", expanded = true)

    @Test
    fun detentFollowsTheFlip() {
        assertEquals(SheetDetent.None, front.detent)
        assertEquals(SheetDetent.Peek, peek.detent)
        assertEquals(SheetDetent.Expanded, expanded.detent)
    }

    @Test
    fun tapOnAFrontFaceFlipsToTheCardAtItsPeek() {
        val next = reduceCardFlip(front, Tap("a"))
        assertEquals(peek, next)
        assertEquals(SheetDetent.Peek, next.detent)
    }

    @Test
    fun tapOnTheCardFlipsBackAndDismissesTheSheetFromPeekOrExpanded() {
        assertEquals(front, reduceCardFlip(peek, Tap("a")))
        assertEquals(front, reduceCardFlip(expanded, Tap("a")))
        assertEquals(SheetDetent.None, reduceCardFlip(expanded, Tap("a")).detent)
    }

    @Test
    fun anotherNodeTappedMovesTheFlipAndTheFirstFlipsBack() {
        assertEquals(CardFlip("b"), reduceCardFlip(peek, OtherNodeTapped("b")))
        assertEquals(CardFlip("b"), reduceCardFlip(expanded, OtherNodeTapped("b")))
        assertEquals(CardFlip("b"), reduceCardFlip(peek, Tap("b")))
        assertFalse(reduceCardFlip(peek, Tap("b")).isFlipped("a"))
    }

    @Test
    fun otherNodeTappedOnTheFlippedNodeChangesNothingAndFromFrontFlipsIt() {
        assertEquals(peek, reduceCardFlip(peek, OtherNodeTapped("a")))
        assertEquals(peek, reduceCardFlip(front, OtherNodeTapped("a")))
    }

    @Test
    fun swipeOnTheCardAtPeekFlipsBack() {
        assertEquals(front, reduceCardFlip(peek, SwipeAny("a")))
    }

    @Test
    fun swipeOnceTheSheetIsExpandedBelongsToTheSheet() {
        assertEquals(expanded, reduceCardFlip(expanded, SwipeAny("a")))
    }

    @Test
    fun swipeOnAFrontFaceOrAnotherNodeChangesNothing() {
        assertEquals(front, reduceCardFlip(front, SwipeAny("a")))
        assertEquals(peek, reduceCardFlip(peek, SwipeAny("b")))
    }

    @Test
    fun sheetExpandedAndPeekedOnlyMoveAFlippedCardsDetent() {
        assertEquals(expanded, reduceCardFlip(peek, SheetExpanded))
        assertEquals(peek, reduceCardFlip(expanded, SheetPeeked))
        assertEquals(front, reduceCardFlip(front, SheetExpanded))
        assertEquals(front, reduceCardFlip(front, SheetPeeked))
    }

    @Test
    fun backFlipsBackFromEveryState() {
        assertEquals(front, reduceCardFlip(front, Back))
        assertEquals(front, reduceCardFlip(peek, Back))
        assertEquals(front, reduceCardFlip(expanded, Back))
    }

    @Test
    fun afterFlippingBackTheNextTapFlipsAtPeekAgainNotExpanded() {
        val again = reduceCardFlip(reduceCardFlip(expanded, Back), Tap("a"))
        assertEquals(SheetDetent.Peek, again.detent)
    }

    @Test
    fun theCreatureSheetOpensInLiveAndInEditOnlyWhileACardIsFlipped() {
        assertTrue(creatureSheetAllowed(editing = false, flipped = false))
        assertTrue(creatureSheetAllowed(editing = false, flipped = true))
        assertFalse(creatureSheetAllowed(editing = true, flipped = false))
        assertTrue(creatureSheetAllowed(editing = true, flipped = true))
    }

    @Test
    fun aStoredFlowchartViewLandsOnEdit() {
        assertTrue(migratedToEdit(storedFlowchartView = true, startInEdit = false))
        assertFalse(migratedToEdit(storedFlowchartView = false, startInEdit = false))
        assertTrue(migratedToEdit(storedFlowchartView = false, startInEdit = true))
    }
}
