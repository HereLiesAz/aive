package com.hereliesaz.geministrator.cli

import com.hereliesaz.geministrator.mcpbridge.ControlReply
import com.hereliesaz.geministrator.mcpbridge.ControlTransport
import com.hereliesaz.geministrator.mcpbridge.ControlUnreachable
import java.io.Console
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * The Aive's headless entrypoint: either own an independent workflow runtime, or send ordinary
 * control commands to the running headless process. No desktop application ever needs to start.
 */
fun main(args: Array<String>) {
    val exitCode = try {
        when (args.firstOrNull()) {
            "serve" -> if (args.size == 2 && args[1] in setOf("--help", "-h")) {
                println("aive serve: start the independent headless engine and authenticated loopback API")
                0
            } else {
                require(args.size == 1) { "Usage: aive serve" }
                runBlocking { serve() }
            }
            "run" -> if (args.drop(1) == listOf("--help")) {
                println("aive run --objective <text> [--project <name>] | --hive <id> [--objective <text>]")
                0
            } else runBlocking { runOnce(args.drop(1)) }
            "resume" -> {
                require(args.size == 1) { "Usage: aive resume" }
                runBlocking { resume() }
            }
            else -> {
                // Help works even when no service is listening.
                val help = args.isEmpty() || args.first() in setOf("--help", "-h", "help")
                if (help) {
                    println("The Aive headless engine:\n  aive serve               Start the daemon and local keeper console\n" +
                        "  aive run --objective ...  Plan and run in this terminal\n" +
                        "  aive run --hive <id>      Run a saved hive in this terminal\n" +
                        "  aive resume              Resume an interrupted run in this terminal\n")
                }
                val transport = if (help) {
                    ControlTransport { _, _, _ -> throw ControlUnreachable("Headless engine not started") }
                } else HeadlessConnection.transport()
                AiveCli(transport).run(args.asList(), System.out, System.err)
            }
        }
    } catch (failure: IllegalArgumentException) {
        System.err.println("aive: ${failure.message}")
        2
    } catch (failure: ControlUnreachable) {
        System.err.println("aive: ${failure.message}")
        3
    } catch (failure: IllegalStateException) {
        System.err.println("aive: ${failure.message}")
        1
    } catch (failure: Exception) {
        // No provider response or secret is emitted by the process-level exception handler.
        System.err.println("aive: headless runtime failed (${failure.javaClass.simpleName})")
        3
    }
    exitProcess(exitCode)
}

private fun keeperConsole(): KeeperConsole? =
    System.console()?.let { terminal -> KeeperConsole { prompt -> terminal.readLine("%s", prompt) } }

private suspend fun runOnce(args: List<String>): Int {
    val options = HeadlessRunArguments.parse(args)
    if (options.hiveId == null && keeperConsole() == null) {
        throw IllegalStateException("Planning a new workflow requires a local interactive keeper terminal")
    }
    HeadlessEngine.open().use { engine ->
        val runner = HeadlessForeground(engine, keeperConsole(), System.out)
        runner.launch(options)
        return runner.follow()
    }
}

private suspend fun resume(): Int = HeadlessEngine.open().use { engine ->
    HeadlessForeground(engine, keeperConsole(), System.out).follow()
}

private suspend fun serve(): Int = HeadlessEngine.open().use { engine ->
    HeadlessControlServer.start(engine).use { server ->
        println("The Aive headless engine listening at ${server.address}. State is independent of the desktop application.")
        val terminal = System.console()
        if (terminal == null) {
            // Service/CI/SSH deployments may have no controlling TTY. The server stays alive,
            // while owner decisions remain inaccessible to every HTTP or agent interface.
            CountDownLatch(1).await()
        } else {
            serveKeeperConsole(engine, terminal)
        }
    }
    0
}

private suspend fun serveKeeperConsole(engine: HeadlessEngine, terminal: Console) {
    val keeper = KeeperConsole { prompt -> terminal.readLine("%s", prompt) }
    val foreground = HeadlessForeground(engine, keeper, System.out)
    println("Keeper commands: status, approvals, approve <task-id>, quit")
    while (true) {
        val input = terminal.readLine("aive> ")?.trim() ?: return
        when {
            input == "quit" || input == "exit" -> return
            input == "status" -> println(engine.controlHost.status())
            input == "approvals" -> println(engine.controlHost.listApprovals())
            input.startsWith("approve ") -> {
                val id = input.removePrefix("approve ").trim()
                if (id.isEmpty()) {
                    println("Supply a task ID")
                } else {
                    try {
                        println(if (foreground.approve(id, keeper)) "Approved" else "Left pending")
                    } catch (e: IllegalArgumentException) {
                        println(e.message ?: "Invalid decision")
                    } catch (e: IllegalStateException) {
                        println(e.message ?: "Decision refused")
                    }
                }
            }
            input.isEmpty() -> Unit
            else -> println("Unknown keeper command: $input")
        }
    }
}
