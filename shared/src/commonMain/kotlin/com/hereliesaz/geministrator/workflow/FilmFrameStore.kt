package com.hereliesaz.geministrator.workflow

/**
 * Where a film's frames are kept between steps and between runs (docs/Short-Film.md).
 *
 * Frames are filed by [filmId], not by workflow run: the id is [FilmKey.of], a name for the storyboard and
 * style sheet the frames are drawn from. A run that is run again from the frames step therefore finds the
 * frames it already drew and draws only the shots sent back. The ids are checked by callers
 * ([isValidShotId]); an implementation that writes files must still refuse anything else.
 */
interface FilmFrameStore {
    /** Keeps [png] as the frame of [shotId] in [filmId] and returns its uri. Replaces an earlier frame of that shot. */
    suspend fun save(filmId: String, shotId: String, png: ByteArray): String

    /** The uri of the frame kept for [shotId] in [filmId], or null if there is none. */
    suspend fun find(filmId: String, shotId: String): String?

    /** Shots the frame review (or the keeper) sent back and that have not been drawn again yet. */
    suspend fun pendingRegeneration(filmId: String): Set<String>

    /** Records [shotIds] as sent back; they are drawn again the next time the frames step runs. */
    suspend fun markForRegeneration(filmId: String, shotIds: Set<String>)

    /** Forgets that [shotIds] were sent back, once they have been drawn again. */
    suspend fun clearRegeneration(filmId: String, shotIds: Set<String>)
}

/** A store that keeps frames in memory: for tests, and for a runtime that has no place to put files. */
class InMemoryFilmFrameStore : FilmFrameStore {
    private val frames = mutableMapOf<Pair<String, String>, ByteArray>()
    private val pending = mutableMapOf<String, Set<String>>()

    override suspend fun save(filmId: String, shotId: String, png: ByteArray): String {
        require(isValidShotId(shotId)) { "Not a shot id" }
        frames[filmId to shotId] = png
        return uriFor(filmId, shotId)
    }

    override suspend fun find(filmId: String, shotId: String): String? =
        if ((filmId to shotId) in frames) uriFor(filmId, shotId) else null

    override suspend fun pendingRegeneration(filmId: String): Set<String> = pending[filmId].orEmpty()

    override suspend fun markForRegeneration(filmId: String, shotIds: Set<String>) {
        pending[filmId] = pending[filmId].orEmpty() + shotIds
    }

    override suspend fun clearRegeneration(filmId: String, shotIds: Set<String>) {
        pending[filmId] = pending[filmId].orEmpty() - shotIds
    }

    fun bytes(filmId: String, shotId: String): ByteArray? = frames[filmId to shotId]

    /** Forgets a frame, as a deleted file would be. */
    fun remove(filmId: String, shotId: String) {
        frames.remove(filmId to shotId)
    }

    private fun uriFor(filmId: String, shotId: String) = "memory://$filmId/$shotId.png"
}
