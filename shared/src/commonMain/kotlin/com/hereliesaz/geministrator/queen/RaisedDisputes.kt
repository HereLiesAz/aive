package com.hereliesaz.geministrator.queen

/** The newest this many raised-dispute keys are kept. */
const val MAX_RAISED_DISPUTES = 64

/**
 * The keys of disputes already raised (a report that was disputed), kept bounded so a restart does not raise the same dispute
 * again. [load] and [save] are the app's durable settings; a store that fails to read or write leaves the keys in memory only.
 */
class RaisedDisputes(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val max: Int = MAX_RAISED_DISPUTES,
) {
    private val keys: LinkedHashSet<String> by lazy {
        val stored = try {
            load()
        } catch (_: Exception) {
            null
        }
        linkedSetOf<String>().also { set -> stored?.split('\n')?.filter { it.isNotBlank() }?.forEach(set::add) }
    }

    /** True when [key] is new (and is now remembered, durably); false when it was raised before. */
    fun add(key: String): Boolean {
        if (key.isBlank() || '\n' in key) return false
        if (!keys.add(key)) return false
        while (keys.size > max) keys.remove(keys.first())
        try {
            save(keys.joinToString("\n"))
        } catch (_: Exception) {
            // Kept in memory for this run of the app.
        }
        return true
    }

    fun keys(): List<String> = keys.toList()
}
