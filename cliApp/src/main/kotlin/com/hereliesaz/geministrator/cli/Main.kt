package com.hereliesaz.geministrator.cli

import com.hereliesaz.geministrator.mcpbridge.BridgeConfig
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val status = try {
        AiveCli(BridgeConfig.load()).run(args.asList(), System.out, System.err)
    } catch (_: IllegalArgumentException) {
        System.err.println("aive: Invalid local control API configuration")
        2
    }
    exitProcess(status)
}
