package com.hereliesaz.geministrator.persistence

import com.russhwolf.settings.Settings

/**
 * Settings decorator that stores selected String keys as generation-based chunks.
 *
 * JVM's Preferences-backed Settings implementation rejects values above its per-entry limit. The
 * inference snapshots are intentionally append-heavy, so a single JSON value eventually exceeds
 * that limit. Chunks are written under a new generation and the short manifest is switched last,
 * which also keeps readers on the previous complete generation if a write is interrupted.
 */
internal class ChunkedStringSettings(
    private val delegate: Settings,
    private val chunkedKeys: Set<String>,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) : Settings by delegate {
    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
    }

    override fun putString(key: String, value: String) {
        if (key !in chunkedKeys) {
            delegate.putString(key, value)
            return
        }

        val previous = readManifest(key)
        val generation = (previous?.generation ?: 0L) + 1L
        val chunks = value.chunked(chunkSize).ifEmpty { listOf("") }

        chunks.forEachIndexed { index, chunk ->
            delegate.putString(chunkKey(key, generation, index), chunk)
        }
        delegate.putString(manifestKey(key), "$generation:${chunks.size}")
        delegate.remove(key) // Remove a legacy single-value snapshot after successful migration.

        previous?.let { old ->
            repeat(old.chunkCount) { index ->
                delegate.remove(chunkKey(key, old.generation, index))
            }
        }
    }

    override fun getString(key: String, defaultValue: String): String =
        getStringOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? {
        if (key !in chunkedKeys) return delegate.getStringOrNull(key)

        val manifest = readManifest(key) ?: return delegate.getStringOrNull(key)
        return buildString {
            repeat(manifest.chunkCount) { index ->
                val chunk = delegate.getStringOrNull(chunkKey(key, manifest.generation, index))
                    ?: throw PersistenceCorruptionException(
                        "Chunked Settings value '$key' is incomplete at generation ${manifest.generation}, chunk $index.",
                    )
                append(chunk)
            }
        }
    }

    override fun remove(key: String) {
        if (key !in chunkedKeys) {
            delegate.remove(key)
            return
        }
        readManifest(key)?.let { manifest ->
            repeat(manifest.chunkCount) { index ->
                delegate.remove(chunkKey(key, manifest.generation, index))
            }
        }
        delegate.remove(manifestKey(key))
        delegate.remove(key)
    }

    override fun hasKey(key: String): Boolean =
        if (key in chunkedKeys) {
            delegate.hasKey(manifestKey(key)) || delegate.hasKey(key)
        } else {
            delegate.hasKey(key)
        }

    private fun readManifest(key: String): Manifest? {
        val encoded = delegate.getStringOrNull(manifestKey(key)) ?: return null
        val parts = encoded.split(':', limit = 2)
        val generation = parts.getOrNull(0)?.toLongOrNull()
        val chunkCount = parts.getOrNull(1)?.toIntOrNull()
        if (generation == null || generation < 1L || chunkCount == null || chunkCount < 1) {
            throw PersistenceCorruptionException("Chunked Settings manifest for '$key' is invalid.")
        }
        return Manifest(generation, chunkCount)
    }

    private fun manifestKey(key: String) = "$key.__chunks"

    private fun chunkKey(key: String, generation: Long, index: Int) =
        "$key.__chunk.$generation.$index"

    private data class Manifest(
        val generation: Long,
        val chunkCount: Int,
    )

    companion object {
        // Safely below java.util.prefs.Preferences.MAX_VALUE_LENGTH (8192 chars) with room for
        // platform encoding/implementation differences.
        const val DEFAULT_CHUNK_SIZE: Int = 3_500
    }
}
