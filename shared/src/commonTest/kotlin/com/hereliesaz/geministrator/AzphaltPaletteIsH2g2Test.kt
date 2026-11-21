package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2
import com.hereliesaz.conveyance.h2g2.grounds
import kotlin.test.Test
import kotlin.test.assertEquals

/** Aive's palette is conveyance-h2g2's (HG2Gui's own values): this pins that the two cannot drift apart. */
class AzphaltPaletteIsH2g2Test {
    @Test
    fun inkYellowAndWhiteAreTheLibrarys() {
        assertEquals(H2g2.ink, Azphalt.Ink)
        assertEquals(H2g2.yellow, Azphalt.Yellow)
        assertEquals(H2g2.white, Azphalt.White)
    }

    @Test
    fun theFourteenHuesAndTheirCapsAreTheLibrarys() {
        assertEquals(14, Azphalt.hues.size)
        assertEquals(H2g2.hues, Azphalt.hues)
        assertEquals(H2g2.caps, Azphalt.caps)
    }

    @Test
    fun aHueForASeedIsTheLibrarysHashOfIt() {
        listOf("hive-control-Stop", "story-act-0", "", "Mustard").forEach {
            assertEquals(H2g2.hueOf(it), Azphalt.hueIndex(it), it)
        }
    }

    @Test
    fun theGroundsAreTheLibrarysInOrderAndWeight() {
        assertEquals(grounds.map { it.name }, Azphalt.grounds.map { it.name })
        assertEquals(grounds.map { it.page }, Azphalt.grounds.map { it.page })
        assertEquals(grounds.map { it.foldLight }, Azphalt.grounds.map { it.foldLight })
        assertEquals(grounds.map { it.foldDark }, Azphalt.grounds.map { it.foldDark })
        assertEquals(grounds.map { it.weight }, Azphalt.grounds.map { it.weight })
    }
}
