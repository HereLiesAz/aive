package com.hereliesaz.geministrator.memory

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import org.w3c.dom.Worker

// webpack bundles the worker (and its sqlite-wasm import) from this exact `new Worker(new URL(…))` form.
private fun memoryWorker(): Worker =
    js("""new Worker(new URL("aive-memory-worker/memory.worker.js", import.meta.url), { type: "module" })""")

internal actual fun memoryWorkerDriver(): SqlDriver = WebWorkerDriver(memoryWorker())
