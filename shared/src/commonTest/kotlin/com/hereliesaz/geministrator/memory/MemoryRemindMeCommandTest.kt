package com.hereliesaz.geministrator.memory

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryRemindMeCommandTest {
    @Test
    fun aBareCommandOnALineOfItsOwnIsRecognised() {
        assertTrue(MemoryRemindMeCommand.isIn("/remindme"))
        assertTrue(MemoryRemindMeCommand.isIn("Done with the first half.\n  /remindme  \nMore to do."))
        assertTrue(MemoryRemindMeCommand.isIn("a\r\n/remindme\r\nb"))
    }

    @Test
    fun anythingElseIsNotTheCommand() {
        assertFalse(MemoryRemindMeCommand.isIn("/remindme now"), "it takes no argument")
        assertFalse(MemoryRemindMeCommand.isIn("please /remindme"))
        assertFalse(MemoryRemindMeCommand.isIn("Write `/remindme` to send the session."))
        assertFalse(MemoryRemindMeCommand.isIn("/remindmenow"))
        assertFalse(MemoryRemindMeCommand.isIn("/remind me"))
        assertFalse(MemoryRemindMeCommand.isIn("no command here"))
    }
}
