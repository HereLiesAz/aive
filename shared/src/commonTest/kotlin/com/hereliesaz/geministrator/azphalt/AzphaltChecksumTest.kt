package com.hereliesaz.geministrator.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AzphaltChecksumTest {
    private val hex = "ab".repeat(32)

    @Test
    fun bothPrefixesAreAcceptedAndOthersAreNot() {
        assertEquals(hex, azphaltSha256Hex("sha256-$hex"))
        assertEquals(hex, azphaltSha256Hex("SHA256:${hex.uppercase()}"))
        assertNull(azphaltSha256Hex(hex))
        assertNull(azphaltSha256Hex("sha256-abc"))
        assertNull(azphaltSha256Hex("md5-$hex"))
        assertNull(azphaltSha256Hex(null))
    }
}
