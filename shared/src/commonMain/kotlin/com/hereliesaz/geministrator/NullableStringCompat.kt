package com.hereliesaz.geministrator

/**
 * Transitional null-safe trim used only while the Compose node-creature renderer is being replaced
 * by the native/WASM render-packet host. Delete this with HaiveNodeTerrarium's procedural geometry.
 */
internal fun String?.trim(): String = this?.trim().orEmpty()
