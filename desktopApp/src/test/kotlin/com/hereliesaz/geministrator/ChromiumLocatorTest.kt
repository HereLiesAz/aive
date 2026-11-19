package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChromiumLocatorTest {
    @Test
    fun theUsualPlacesDifferByOperatingSystem() {
        assertTrue(ChromiumLocator.wellKnown("mac", emptyMap()).any { it.endsWith("Google Chrome") })
        val windows = ChromiumLocator.wellKnown("windows", mapOf("ProgramFiles" to "C:\\Program Files", "LOCALAPPDATA" to "C:\\Users\\k\\AppData\\Local"))
        assertTrue(windows.any { it.endsWith("chrome.exe") } && windows.any { it.endsWith("msedge.exe") })
        assertTrue(ChromiumLocator.wellKnown("linux", emptyMap()).contains("/usr/bin/chromium"))
        assertEquals(listOf(), ChromiumLocator.wellKnown("windows", emptyMap()))
    }

    @Test
    fun theOperatingSystemIsNamedFromItsProperty() {
        assertEquals("windows", ChromiumLocator.osName("Windows 11"))
        assertEquals("mac", ChromiumLocator.osName("Mac OS X"))
        assertEquals("linux", ChromiumLocator.osName("Linux"))
    }

    @Test
    fun thePortIsTheFirstLineOfTheDevToolsFile() {
        assertEquals(40123, ChromiumLocator.portFrom("40123\n/devtools/browser/abc"))
        assertNull(ChromiumLocator.portFrom(null))
        assertNull(ChromiumLocator.portFrom("not a port"))
        assertNull(ChromiumLocator.portFrom("0\n"))
    }

    @Test
    fun onlyALocalSocketAddressIsFollowed() {
        assertEquals("ws://127.0.0.1:9222/devtools/page/1", ChromiumLocator.socketFrom("""{"id":"1","webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/page/1"}"""))
        assertNull(ChromiumLocator.socketFrom("""{"webSocketDebuggerUrl":"ws://example.com/devtools/page/1"}"""))
        assertNull(ChromiumLocator.socketFrom("not json"))
    }
}
