package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.workflow.FILM_IMAGE_BUDGET_CEILING
import com.hereliesaz.geministrator.workflow.FILM_IMAGE_BUDGET_DEFAULT
import com.hereliesaz.geministrator.workflow.FILM_IMAGE_BUDGET_ENV
import com.hereliesaz.geministrator.workflow.FilmFrameStore
import com.hereliesaz.geministrator.workflow.isValidShotId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A frame path that is not inside the film's folder in the store. */
internal class FilmPathException(message: String) : IllegalArgumentException(message)

/**
 * The desktop's frame store (docs/Short-Film.md): `<root>/<film id>/<shot id>.png`, under the app data
 * folder, one folder per film. A film id is `film-` and sixteen hex digits and a shot id is checked
 * with [isValidShotId], so no name can contain a separator or `..`. [verifiedFrame] is the only way
 * the assemble step gets a frame's path: it accepts a path only if it is exactly this film's folder
 * and shot, and the file is a plain file, not a link, whose real location is still inside that folder.
 */
internal class FileFilmFrameStore(private val root: File = defaultRoot()) : FilmFrameStore {
    private val rootPath: Path get() = root.toPath().toAbsolutePath().normalize()

    override suspend fun save(filmId: String, shotId: String, png: ByteArray): String = withContext(Dispatchers.IO) {
        requireShot(shotId)
        val folder = filmFolder(filmId)
        val target = folder.resolve("$shotId.png")
        prepare(folder)
        val temp = Files.createTempFile(folder, ".frame-", ".tmp")
        try {
            Files.write(temp, png)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        target.toUri().toString()
    }

    override suspend fun find(filmId: String, shotId: String): String? = withContext(Dispatchers.IO) {
        requireShot(shotId)
        val file = filmFolder(filmId).resolve("$shotId.png")
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) > 0) file.toUri().toString() else null
    }

    override suspend fun pendingRegeneration(filmId: String): Set<String> = withContext(Dispatchers.IO) {
        val file = filmFolder(filmId).resolve(PENDING_FILE)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            emptySet()
        } else {
            Files.readAllLines(file).map(String::trim).filter(::isValidShotId).toSet()
        }
    }

    private val counters = Mutex()

    override suspend fun reserveImage(workflowRunId: String, budget: Int): Boolean = counters.withLock {
        val used = readCount(workflowRunId)
        if (used >= budget) {
            false
        } else {
            writeSmall(BUDGET_DIR, workflowRunId, (used + 1).toString())
            true
        }
    }

    override suspend fun spentImages(workflowRunId: String): Int = counters.withLock { readCount(workflowRunId) }

    override suspend fun consumeOnce(key: String): Boolean = counters.withLock {
        val seen = withContext(Dispatchers.IO) { Files.isRegularFile(smallFile(ONCE_DIR, key), LinkOption.NOFOLLOW_LINKS) }
        if (!seen) writeSmall(ONCE_DIR, key, "1")
        !seen
    }

    private suspend fun readCount(workflowRunId: String): Int = withContext(Dispatchers.IO) {
        val file = smallFile(BUDGET_DIR, workflowRunId)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) 0 else Files.readString(file).trim().toIntOrNull()?.coerceAtLeast(0) ?: Int.MAX_VALUE
    }

    /** `<root>/<dir>/<hash of key>`: a key (a run id, a request) is text from elsewhere, so it names a file only through its hash. */
    private fun smallFile(dir: String, key: String): Path = rootPath.resolve(dir).resolve(fnv1a64Hex(key))

    private suspend fun writeSmall(dir: String, key: String, text: String) = withContext(Dispatchers.IO) {
        val folder = rootPath.resolve(dir)
        Files.createDirectories(folder)
        val temp = Files.createTempFile(folder, ".small-", ".tmp")
        try {
            Files.writeString(temp, text)
            Files.move(temp, smallFile(dir, key), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override suspend fun markForRegeneration(filmId: String, shotIds: Set<String>) = writePending(filmId) { it + shotIds.filter(::isValidShotId) }

    override suspend fun clearRegeneration(filmId: String, shotIds: Set<String>) = writePending(filmId) { it - shotIds }

    private suspend fun writePending(filmId: String, change: (Set<String>) -> Set<String>) {
        val updated = change(pendingRegeneration(filmId))
        withContext(Dispatchers.IO) {
            val folder = filmFolder(filmId)
            val file = folder.resolve(PENDING_FILE)
            if (updated.isEmpty()) {
                Files.deleteIfExists(file)
            } else {
                prepare(folder)
                val temp = Files.createTempFile(folder, ".pending-", ".tmp")
                try {
                    Files.write(temp, updated.sorted())
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
        }
    }

    /** The uri a frame of [shotId] in [filmId] has here, whether or not the file exists. */
    fun uriFor(filmId: String, shotId: String): String {
        requireShot(shotId)
        return filmFolder(filmId).resolve("$shotId.png").toUri().toString()
    }

    /**
     * The frame file for [shotId] of [filmId], if [uri] is exactly where this store keeps it. Rejects, by
     * throwing [FilmPathException] with the reason: ids that are not ids, any uri that is not this
     * film's folder and shot (another folder, `..`, a relative path, another scheme), a file that is
     * missing, empty or a link, and a path whose real location is outside the film's folder (a link in
     * the folder, or the folder itself being one).
     */
    fun verifiedFrame(filmId: String, shotId: String, uri: String): File {
        requireShot(shotId)
        val folder = filmFolder(filmId)
        val expected = folder.resolve("$shotId.png")
        val given = try {
            val parsed = java.net.URI(uri)
            if (parsed.scheme != "file" || parsed.rawAuthority != null && parsed.rawAuthority.isNotEmpty()) {
                throw FilmPathException("The frame for shot $shotId is not a file in this computer's film folder.")
            }
            Path.of(parsed)
        } catch (failure: FilmPathException) {
            throw failure
        } catch (failure: Exception) {
            throw FilmPathException("The frame location for shot $shotId is not a usable file address.")
        }
        if (given.toString() != expected.toString()) {
            throw FilmPathException("The frame for shot $shotId is not in this film's folder in the frame store, so it is not used.")
        }
        if (!Files.isRegularFile(expected, LinkOption.NOFOLLOW_LINKS)) {
            throw FilmPathException("The frame file for shot $shotId is missing or is a link. Run the frames step again.")
        }
        try {
            requireInside(folder)
            val real = expected.toRealPath()
            if (!real.startsWith(folder.toRealPath())) throw FilmPathException("The frame for shot $shotId resolves outside the film's folder.")
            if (Files.size(real) == 0L) throw FilmPathException("The frame file for shot $shotId is empty. Run the frames step again.")
        } catch (failure: IOException) {
            throw FilmPathException("The frame file for shot $shotId could not be checked: ${failure.message}")
        }
        return expected.toFile()
    }

    /** The folder [filmId] keeps its frames in: `<root>/<film id>`, and nothing else. */
    private fun filmFolder(filmId: String): Path {
        if (!FILM_ID.matches(filmId)) throw FilmPathException("Not a film id.")
        return rootPath.resolve(filmId)
    }

    private fun requireShot(shotId: String) {
        if (!isValidShotId(shotId)) throw FilmPathException("Not a shot id.")
    }

    /** Makes [folder] if it is not there; refuses one that is a link or a file, or that resolves outside the root. */
    private fun prepare(folder: Path) {
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            throw FilmPathException("The film folder is a link or a file, not a folder.")
        }
        Files.createDirectories(folder)
        requireInside(folder)
    }

    /** The folder must really be under the root (not a link out of it). */
    private fun requireInside(folder: Path) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw FilmPathException("The film folder is missing or is a link.")
        if (!folder.toRealPath().startsWith(rootPath.toRealPath())) throw FilmPathException("The film folder resolves outside the frame store.")
    }

    companion object {
        private const val PENDING_FILE = "regenerate.txt"
        private const val BUDGET_DIR = ".budget"
        private const val ONCE_DIR = ".once"

        private fun fnv1a64Hex(text: String): String {
            var hash = -3750763034362895579L
            for (byte in text.encodeToByteArray()) {
                hash = (hash xor (byte.toLong() and 0xff)) * 1099511628211L
            }
            return hash.toULong().toString(16).padStart(16, '0')
        }
        private val FILM_ID = Regex("^film-[0-9a-f]{16}$")

        fun defaultRoot(): File = File(System.getProperty("user.home").orEmpty(), ".aive/film/frames")
    }
}

/**
 * The image budget per run: the [FILM_IMAGE_BUDGET_ENV] environment variable when it is a whole number (kept
 * between 1 and [FILM_IMAGE_BUDGET_CEILING]), else [FILM_IMAGE_BUDGET_DEFAULT]. Read when the app starts.
 */
internal fun filmImageBudget(environment: Map<String, String> = System.getenv()): Int =
    environment[FILM_IMAGE_BUDGET_ENV]?.trim()?.toIntOrNull()?.coerceIn(1, FILM_IMAGE_BUDGET_CEILING) ?: FILM_IMAGE_BUDGET_DEFAULT
