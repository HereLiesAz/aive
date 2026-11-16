package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.DesktopPowerSource.LinuxSupply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopPowerSourceTest {
    @Test
    fun linuxMainsOnlineMeansExternalPower() {
        assertEquals(true, DesktopPowerSource.linuxExternalPower(listOf(LinuxSupply("Battery\n", null), LinuxSupply("Mains\n", "1\n"))))
    }

    @Test
    fun linuxBatteryWithNoMainsOnlineMeansNot() {
        assertEquals(false, DesktopPowerSource.linuxExternalPower(listOf(LinuxSupply("Battery", null), LinuxSupply("Mains", "0"))))
        assertEquals(false, DesktopPowerSource.linuxExternalPower(listOf(LinuxSupply("Battery", null))))
    }

    @Test
    fun linuxWithNoBatteryIsAlwaysOnTheWall() {
        assertEquals(true, DesktopPowerSource.linuxExternalPower(listOf(LinuxSupply("Mains", "0"))))
    }

    @Test
    fun linuxWithNoSuppliesIsUnknown() {
        assertNull(DesktopPowerSource.linuxExternalPower(emptyList()))
    }

    @Test
    fun macReadsPmset() {
        assertEquals(true, DesktopPowerSource.macExternalPower("Now drawing from 'AC Power'\n -InternalBattery-0 100%"))
        assertEquals(false, DesktopPowerSource.macExternalPower("Now drawing from 'Battery Power'\n -InternalBattery-0 81%"))
        assertNull(DesktopPowerSource.macExternalPower("something else"))
    }

    @Test
    fun windowsReadsBatteryStatus() {
        assertEquals(false, DesktopPowerSource.windowsExternalPower("1\r\n"))
        assertEquals(true, DesktopPowerSource.windowsExternalPower("2"))
        assertEquals(true, DesktopPowerSource.windowsExternalPower(""))
        assertNull(DesktopPowerSource.windowsExternalPower("not a number"))
    }
}

class DesktopComputeNodeGateTest {
    private val needsPower = com.hereliesaz.geministrator.distributed.DistributedComputeConfiguration(
        relayUrl = "wss://relay.example", poolId = "p", nodeId = "n", displayName = "Desk",
        sharingEnabled = true, requireExternalPower = true,
    )

    @Test
    fun aDesktopOnBatteryHoldsWorkAndSaysWhy() {
        val node = desktopComputeNode(needsPower, emptySet(), onExternalPower = false)
        assertEquals(false, node.acceptsWork)
        assertEquals(com.hereliesaz.geministrator.distributed.ComputeWorkHold.NeedsExternalPower, com.hereliesaz.geministrator.distributed.ComputeWorkHold.of(node.capabilities))
    }

    @Test
    fun aDesktopThatCannotTellHoldsWorkWhenPowerIsRequired() {
        val node = desktopComputeNode(needsPower, emptySet(), onExternalPower = null)
        assertEquals(false, node.acceptsWork)
        assertEquals(com.hereliesaz.geministrator.distributed.ComputeWorkHold.PowerUnknown, com.hereliesaz.geministrator.distributed.ComputeWorkHold.of(node.capabilities))
    }

    @Test
    fun aDesktopOnTheWallTakesWork() {
        val node = desktopComputeNode(needsPower, emptySet(), onExternalPower = true)
        assertEquals(true, node.acceptsWork)
        assertEquals(true, "external-power" in node.capabilities)
    }

    @Test
    fun aDesktopWithoutThePowerRequirementNeverAsks() {
        val node = desktopComputeNode(needsPower.copy(requireExternalPower = false), emptySet(), onExternalPower = null)
        assertEquals(true, node.acceptsWork)
    }
}
