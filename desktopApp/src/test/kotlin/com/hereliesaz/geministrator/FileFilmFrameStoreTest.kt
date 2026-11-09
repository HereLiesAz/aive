package com.hereliesaz.geministrator

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class FileFilmFrameStoreTest {
    private val film = "film-0123456789abcdef"
    private val other = "film-fedcba9876543210"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    private fun withStore(test: suspend (File, FileFilmFrameStore) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("aive-film-store-").toFile()
        try {
            test(root, FileFilmFrameStore(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aFrameIsKeptUnderItsFilmAndShotAndFoundAgain(): Unit = withStore { root, store ->
        val uri = store.save(film, "s01", png)
        assertTrue(File(root, "$film/s01.png").isFile)
        assertEquals(uri, store.find(film, "s01"))
        assertEquals(uri, store.uriFor(film, "s01"))
        assertNull(store.find(film, "s02"))
        assertNull(store.find(other, "s01"), "another film's folder is another place")
        store.save(film, "s01", png + 9)
        assertEquals(12, File(root, "$film/s01.png").length(), "saving again replaces it")
        assertEquals(listOf("s01.png"), File(root, film).list()!!.toList(), "and leaves no temporary file")
        assertEquals(File(root, "$film/s01.png").canonicalFile, store.verifiedFrame(film, "s01", uri).canonicalFile)
    }

    @Test
    fun shotsSentBackAreRememberedUntilTheyAreDrawnAgain(): Unit = withStore { _, store ->
        assertEquals(emptySet(), store.pendingRegeneration(film))
        store.markForRegeneration(film, setOf("s02", "s05", "../x", "bad id"))
        assertEquals(setOf("s02", "s05"), store.pendingRegeneration(film), "only valid ids are written")
        store.clearRegeneration(film, setOf("s02"))
        assertEquals(setOf("s05"), store.pendingRegeneration(film))
        store.clearRegeneration(film, setOf("s05"))
        assertEquals(emptySet(), store.pendingRegeneration(film))
    }

    @Test
    fun namesThatAreNotIdsNeverBecomeAPath(): Unit = withStore { root, store ->
        listOf("../s", "a/b", "..", "", "s 1", "a\\b").forEach { shot ->
            assertFailsWith<FilmPathException>(shot) { store.save(film, shot, png) }
            assertFailsWith<FilmPathException>(shot) { store.find(film, shot) }
        }
        listOf("film-../..", "../film-0123456789abcdef", "film-ABC", "x", "").forEach { id ->
            assertFailsWith<FilmPathException>(id) { store.save(id, "s01", png) }
        }
        assertEquals(emptyList(), root.list()!!.toList(), "nothing was written anywhere")
    }

    @Test
    fun onlyTheExactFileTheStoreWroteIsAccepted(): Unit = withStore { root, store ->
        val uri = store.save(film, "s01", png)
        store.save(film, "s02", png)
        store.save(other, "s01", png)
        val folder = File(root, film)

        // Right place, wrong shot; another film's folder; a dot-dot path that lands on a real file.
        assertFailsWith<FilmPathException> { store.verifiedFrame(film, "s02", uri) }
        assertFailsWith<FilmPathException> { store.verifiedFrame(film, "s01", File(root, "$other/s01.png").toURI().toString()) }
        assertFailsWith<FilmPathException> { store.verifiedFrame(film, "s01", File(folder, "../$film/s01.png").toURI().toString()) }
        // Not file addresses, or not in this computer's folder at all.
        listOf("http://example.com/s01.png", "ftp://x/s01.png", "/etc/passwd", "s01.png", "file://host/etc/passwd", "file:///etc/passwd", "", "not a uri").forEach {
            assertFailsWith<FilmPathException>(it) { store.verifiedFrame(film, "s01", it) }
        }
        // A frame that is gone or empty.
        File(folder, "s02.png").writeBytes(ByteArray(0))
        assertFailsWith<FilmPathException> { store.verifiedFrame(film, "s02", store.uriFor(film, "s02")) }
        assertFailsWith<FilmPathException> { store.verifiedFrame(film, "s09", store.uriFor(film, "s09")) }
        // A shot id that is not one.
        assertFailsWith<FilmPathException> { store.verifiedFrame(film, "../s01", uri) }
        assertNotNull(store.verifiedFrame(film, "s01", uri))
    }

    @Test
    fun aLinkInsideTheFolderOrTheFolderItselfCannotCarryAFrameOutside(): Unit = withStore { root, store ->
        val outside = Files.createTempDirectory("aive-film-outside-").toFile()
        try {
            val secret = File(outside, "photo.png").apply { writeBytes(png) }
            val folder = File(root, film).apply { mkdirs() }
            val linked = try {
                Files.createSymbolicLink(File(folder, "s01.png").toPath(), secret.toPath())
                true
            } catch (unsupported: Exception) {
                false
            }
            if (linked) {
                assertFailsWith<FilmPathException>("a link to a file elsewhere") { store.verifiedFrame(film, "s01", store.uriFor(film, "s01")) }
                assertNull(store.find(film, "s01"), "and it is not even found")
            }

            val linkedFolder = File(root, other)
            val folderLinked = try {
                Files.createSymbolicLink(linkedFolder.toPath(), outside.toPath())
                true
            } catch (unsupported: Exception) {
                false
            }
            if (folderLinked) {
                assertFailsWith<FilmPathException>("the film folder is a link out") { store.verifiedFrame(other, "photo", store.uriFor(other, "photo")) }
                assertFailsWith<FilmPathException>("and nothing is saved through it") { store.save(other, "s01", png) }
                assertEquals(listOf("photo.png"), outside.list()!!.toList(), "nothing landed outside")
            }
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun theImageBudgetComesFromTheEnvironmentWithinItsLimits() {
        assertEquals(40, filmImageBudget(emptyMap()))
        assertEquals(100, filmImageBudget(mapOf("AIVE_FILM_IMAGE_BUDGET" to " 100 ")))
        assertEquals(500, filmImageBudget(mapOf("AIVE_FILM_IMAGE_BUDGET" to "100000")))
        assertEquals(1, filmImageBudget(mapOf("AIVE_FILM_IMAGE_BUDGET" to "-3")))
        assertEquals(40, filmImageBudget(mapOf("AIVE_FILM_IMAGE_BUDGET" to "lots")))
    }
}
