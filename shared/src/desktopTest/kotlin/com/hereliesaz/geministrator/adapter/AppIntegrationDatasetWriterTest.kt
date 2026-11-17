package com.hereliesaz.geministrator.adapter

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes the adapter corpus. Skipped unless Gradle is given the output folder:
 *
 *     ./gradlew --no-daemon --console=plain :shared:desktopTest \
 *       --tests 'com.hereliesaz.geministrator.adapter.AppIntegrationDatasetWriterTest' \
 *       -Daive.appIntegration.outDir=<dir> [-Daive.appIntegration.commit=<sha>]
 *
 * `AIVE_APP_INTEGRATION_ROWS` sets the regular rows per family (default 1500).
 */
class AppIntegrationDatasetWriterTest {
    @Test
    fun writeWhenRequested() {
        val target = System.getProperty(OUT_PROPERTY)?.takeIf(String::isNotBlank) ?: return
        val out = File(target).apply { mkdirs() }
        val rowsPerFamily = System.getenv(AppIntegrationDatasetTest.ROWS_ENV)?.toIntOrNull() ?: AppIntegrationDatasetGenerator.DEFAULT_ROWS_PER_FAMILY
        val rows = AppIntegrationDatasetGenerator.generate(rowsPerFamily)
        val files = LinkedHashMap<String, File>()
        for (split in listOf("train", "validation", "test", "adversarial")) {
            val file = File(out, "$split.jsonl")
            file.writeText(rows.filter { it.split == split }.joinToString("") { AppIntegrationDatasetGenerator.chatJson(it) + "\n" })
            files[split] = file
        }
        findScorer()?.let { File(out, "score.py").writeBytes(it.readBytes()) }
        val manifest = JsonObject(
            linkedMapOf(
                "schema" to JsonPrimitive(1),
                "generator" to JsonPrimitive("shared/src/desktopTest/kotlin/com/hereliesaz/geministrator/adapter/AppIntegrationDatasetGenerator.kt"),
                "source_commit" to JsonPrimitive(commit()),
                "regular_rows_per_family" to JsonPrimitive(rowsPerFamily),
                "checks_version" to JsonPrimitive(CHECKS_VERSION),
                "files" to JsonObject(files.mapValues { (_, f) ->
                    JsonObject(mapOf("file" to JsonPrimitive(f.name), "sha256" to JsonPrimitive(sha256(f)), "rows" to JsonPrimitive(f.readLines().size)))
                }),
                "counts" to JsonObject(rows.groupBy { it.split }.mapValues { (_, list) ->
                    JsonObject(list.groupBy { it.family }.toSortedMap().mapValues { JsonPrimitive(it.value.size) })
                }),
                "stable_prefix_share" to JsonObject(rows.groupBy { it.family }.toSortedMap().mapValues { (_, list) ->
                    var stable = 0L
                    var total = 0L
                    list.forEach { val (s, t) = AppIntegrationLayout.tokenShare(it.system, it.user); stable += s; total += t }
                    JsonPrimitive(Math.round(stable * 1000.0 / total) / 1000.0)
                }),
                "stable_prefix_share_note" to JsonPrimitive("estimated tokens (4 characters each) of system prompt plus stable prefix over the whole input; cache layout only, the model emits no cache markers"),
            ),
        )
        File(out, "manifest.json").writeText(manifest.toString() + "\n")
        assertTrue(File(out, "manifest.json").exists() && files.values.all { it.length() > 0 })
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun commit(): String =
        System.getProperty(COMMIT_PROPERTY)?.takeIf(String::isNotBlank) ?: try {
            val p = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }.ifBlank { "unknown" }
        } catch (_: Exception) {
            "unknown"
        }

    private fun findScorer(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/app_integration_training/score.py")
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        return null
    }

    companion object {
        const val OUT_PROPERTY = "aive.appIntegration.outDir"
        const val COMMIT_PROPERTY = "aive.appIntegration.commit"
    }
}
