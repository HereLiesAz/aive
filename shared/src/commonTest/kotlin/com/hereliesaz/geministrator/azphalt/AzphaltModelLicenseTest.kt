package com.hereliesaz.geministrator.azphalt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AzphaltModelLicenseTest {
    private fun license(json: String) = AzphaltModelLicense.from(Json.parseToJsonElement(json))

    @Test
    fun aConditionalLicenseIsReadAndHasToBeAccepted() {
        val license = license(
            """{"name":"LFM Open License v1.0","spdx":"LicenseRef-LFM-Open-1.0","url":"https://example.test/LICENSE","commercialUse":"conditional","terms":"Not for an organization with revenue of 10 million US dollars or more."}""",
        )!!
        assertEquals("LFM Open License v1.0", license.name)
        assertNull(license.commercialUse)
        assertTrue(license.needsAcceptance)
        assertEquals(
            "LFM Open License v1.0 — Not for an organization with revenue of 10 million US dollars or more. — https://example.test/LICENSE",
            license.summary(),
        )
    }

    @Test
    fun aFreelyCommercialLicenseNeedsNoAcceptance() {
        val license = license("""{"spdx":"Apache-2.0","commercialUse":true}""")!!
        assertEquals(true, license.commercialUse)
        assertFalse(license.needsAcceptance)
        assertEquals("Apache-2.0", license.summary())
    }

    @Test
    fun noCommercialUseOrAnUnstatedAnswerNeedsAcceptance() {
        assertTrue(license("""{"spdx":"CC-BY-NC-4.0","commercialUse":false}""")!!.needsAcceptance)
        assertTrue(license("""{"spdx":"Custom"}""")!!.needsAcceptance)
    }

    @Test
    fun anAbsentOrNonObjectLicenseIsNull() {
        assertNull(AzphaltModelLicense.from(null))
        assertNull(license("\"MIT\""))
    }
}
