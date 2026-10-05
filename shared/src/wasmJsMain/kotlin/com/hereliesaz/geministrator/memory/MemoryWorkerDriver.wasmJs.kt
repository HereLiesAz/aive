package com.hereliesaz.geministrator.memory

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import org.w3c.dom.Worker

// webpack bundles the worker (and its sqlite-wasm import) from this exact `new Worker(new URL(…))` form.
// The worker's name selects its database: "" is the old shared one, "bank-<stem>" a project's bank.
private fun memoryWorker(name: String): Worker =
    js("""new Worker(new URL("aive-memory-worker/memory.worker.js", import.meta.url), { type: "module", name: name })""")

internal actual fun memoryWorkerDriver(name: String): SqlDriver = WebWorkerDriver(memoryWorker(name))
