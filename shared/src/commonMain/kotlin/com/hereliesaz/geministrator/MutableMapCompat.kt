package com.hereliesaz.geministrator

/**
 * Cross-platform equivalent of Java's Map.putIfAbsent for common Kotlin code.
 *
 * Kotlin/JVM can inherit putIfAbsent from java.util.Map, but Kotlin/JS and Kotlin/Wasm do not expose
 * that API on MutableMap. Keeping the compatibility shim in commonMain lets shared workflow
 * projection code preserve insertion order and first-writer-wins semantics on every target.
 */
internal fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? {
    val existing = this[key]
    if (existing == null && !containsKey(key)) {
        this[key] = value
    }
    return existing
}
