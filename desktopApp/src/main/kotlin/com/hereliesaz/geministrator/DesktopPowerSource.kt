package com.hereliesaz.geministrator

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Whether this computer is on external power, so "Require external power" in the Compute part works on
 * desktop as it does on Android. Linux reads /sys/class/power_supply, macOS asks `pmset`, Windows asks
 * the battery through PowerShell. Null means it could not tell. A computer with no battery counts as
 * plugged in.
 */
internal object DesktopPowerSource {
    /** One /sys/class/power_supply entry: its `type` file and its `online` file (null when it has none). */
    data class LinuxSupply(val type: String, val online: String?)

    /** Pure. Any non-battery supply that is online means external power; a battery with none online means not. */
    fun linuxExternalPower(supplies: List<LinuxSupply>): Boolean? {
        if (supplies.isEmpty()) return null
        val mains = supplies.filter { !it.type.trim().equals("Battery", ignoreCase = true) }
        if (mains.any { it.online?.trim() == "1" }) return true
        // No battery at all: a desktop machine that can only be running from the wall.
        if (supplies.none { it.type.trim().equals("Battery", ignoreCase = true) }) return true
        return false
    }

    /** Pure. Reads `pmset -g batt`: "Now drawing from 'AC Power'" or "'Battery Power'". */
    fun macExternalPower(pmsetOutput: String): Boolean? = when {
        "'AC Power'" in pmsetOutput -> true
        "'Battery Power'" in pmsetOutput || "'UPS Power'" in pmsetOutput -> false
        else -> null
    }

    /**
     * Pure. Reads the BatteryStatus PowerShell prints for the first battery: 1 is discharging, the other
     * numbers are some form of charging or full; nothing printed means there is no battery.
     */
    fun windowsExternalPower(batteryStatusOutput: String): Boolean? {
        val text = batteryStatusOutput.trim()
        if (text.isEmpty()) return true
        val status = text.toIntOrNull() ?: return null
        return status != 1
    }

    fun probe(osName: String = System.getProperty("os.name").orEmpty()): Boolean? = runCatching {
        val os = osName.lowercase()
        when {
            "linux" in os -> linuxExternalPower(readLinuxSupplies())
            "mac" in os || "darwin" in os -> run("pmset", "-g", "batt")?.let(::macExternalPower)
            "windows" in os -> run(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-CimInstance Win32_Battery | Select-Object -First 1 -ExpandProperty BatteryStatus)",
            )?.let(::windowsExternalPower)
            else -> null
        }
    }.getOrNull()

    private fun readLinuxSupplies(): List<LinuxSupply> =
        File("/sys/class/power_supply").listFiles().orEmpty().mapNotNull { dir ->
            val type = runCatching { File(dir, "type").readText() }.getOrNull() ?: return@mapNotNull null
            LinuxSupply(type, runCatching { File(dir, "online").readText() }.getOrNull())
        }

    /** The command's output, or null when it fails or takes more than five seconds. */
    private fun run(vararg command: String): String? {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return if (process.exitValue() == 0) process.inputStream.bufferedReader().readText() else null
    }
}
