package com.hereliesaz.geministrator.providers.jules

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JulesDtosTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun sessionWithoutStateDeserializes() {
        val session = json.decodeFromString<JulesSession>(
            """
            {
              "name": "sessions/session-1",
              "id": "session-1",
              "title": "Existing Jules run"
            }
            """.trimIndent(),
        )

        assertEquals("sessions/session-1", session.name)
        assertEquals("session-1", session.id)
        assertEquals("Existing Jules run", session.title)
        assertNull(session.state)
    }

    @Test
    fun sessionStateStillDeserializesWhenPresent() {
        val session = json.decodeFromString<JulesSession>(
            """
            {
              "name": "sessions/session-1",
              "id": "session-1",
              "state": "IN_PROGRESS"
            }
            """.trimIndent(),
        )

        assertEquals("IN_PROGRESS", session.state)
    }
}
