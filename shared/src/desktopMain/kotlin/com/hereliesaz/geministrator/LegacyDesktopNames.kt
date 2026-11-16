package com.hereliesaz.geministrator

import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.Preferences

/**
 * Read-side migration from the names desktop stores used before the product was renamed: the
 * Java Preferences nodes and OS keychain service names are moved to their current names the
 * first time a store is opened, so nothing a keeper saved is lost.
 */
object LegacyDesktopNames {
    // Legacy input names. They are only ever read (then retired); every write uses the current names.
    private const val LEGACY_PREFERENCES_PREFIX = "com/hereliesaz/haive"
    private const val CURRENT_PREFERENCES_PREFIX = "com/hereliesaz/aive"
    private const val LEGACY_SERVICE_PREFIX = "com.hereliesaz.haive"
    private const val CURRENT_SERVICE_PREFIX = "com.hereliesaz.aive"

    private val migratedNodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The keychain service name older builds used for the current [service] name. */
    fun legacyService(service: String): String = LEGACY_SERVICE_PREFIX + service.removePrefix(CURRENT_SERVICE_PREFIX)

    /** The Preferences node path older builds used for the current [node] path. */
    fun legacyPreferencesNode(node: String): String =
        LEGACY_PREFERENCES_PREFIX + node.removePrefix(CURRENT_PREFERENCES_PREFIX)

    /**
     * Once per process: copies every entry of the legacy Preferences node into [currentNode] where the
     * current node has no entry of that name, then removes the legacy node. Returns [currentNode] opened
     * on [root] so callers can chain it.
     */
    fun currentPreferencesNode(currentNode: String, root: Preferences = Preferences.userRoot()): Preferences {
        if (migratedNodes.add("${System.identityHashCode(root)}:$currentNode")) migratePreferencesNode(currentNode, root)
        return root.node(currentNode)
    }

    /** The one-time copy behind [currentPreferencesNode], also callable directly on a test root. */
    fun migratePreferencesNode(currentNode: String, root: Preferences) {
        if (!currentNode.startsWith(CURRENT_PREFERENCES_PREFIX)) return
        val legacyPath = legacyPreferencesNode(currentNode)
        runCatching {
            if (!root.nodeExists(legacyPath)) return@runCatching
            val legacy = root.node(legacyPath)
            val current = root.node(currentNode)
            legacy.keys().forEach { key ->
                if (current.get(key, null) == null) legacy.get(key, null)?.let { current.put(key, it) }
            }
            current.flush()
            legacy.removeNode()
            root.flush()
        }
    }
}
