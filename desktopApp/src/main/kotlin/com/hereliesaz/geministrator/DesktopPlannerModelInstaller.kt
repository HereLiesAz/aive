package com.hereliesaz.geministrator

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/** The optional desktop planner model, published as split parts on a GitHub release. */
internal object DesktopPlannerModel {
    const val RELEASE_API_URL = "https://api.github.com/repos/HereLiesAz/aive/releases/tags/orchestration-layer-epoch8"
    const val ARCHIVE_NAME = "haive-orch_planner-int8-epoch8.tar.gz"
    const val RUNTIME_ARTIFACT_ID = "orchestration:epoch8:planner:int8"
    const val QUANTIZATION = "int8"

    /** Fine-tuned Qwen2.5-1.5B-Instruct; the whole archive, all parts. */
    const val DOWNLOAD_BYTES = 3_860_050_311L

    val downloadSizeLabel: String
        get() = "%.1f GB".format(DOWNLOAD_BYTES / (1024.0 * 1024.0 * 1024.0))
}

internal data class InstalledPlannerModel(
    val root: File,
    val onnxModel: File,
)

/**
 * Installs the desktop planner model under ~/.aive/models on explicit request only; planning never
 * triggers a download. Completed parts and partial transfers are kept so a retry resumes.
 */
internal class DesktopPlannerModelInstaller(
    private val httpClient: HttpClient,
    private val installRoot: File = File(System.getProperty("user.home"), ".aive/models/orchestration-epoch8"),
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val downloader = DesktopResumableFileDownloader(httpClient)
    private val destination get() = File(installRoot, "planner")
    private val staging get() = File(installRoot, ".staging/planner")

    /** The installed model, or null. Cheap: no hashing. */
    fun installed(): InstalledPlannerModel? = locate(destination)

    /** Downloads, verifies and unpacks the model. [progress] receives human-readable status lines. */
    suspend fun install(progress: (String) -> Unit): InstalledPlannerModel = mutex.withLock {
        withContext(Dispatchers.IO) {
            installed()?.let { return@withContext it }
            staging.mkdirs()
            progress("Fetching release info…")
            val assets = loadReleaseAssets()
            // Only "<archive>.part001"-style assets; the release also carries "<archive>.parts.json"
            // and per-part ".sha256" files, which must not be joined into the archive.
            val partPattern = Regex(Regex.escape(DesktopPlannerModel.ARCHIVE_NAME) + """\.part\d+""")
            val parts = assets
                .filter { partPattern.matches(it.name) }
                .sortedBy(ReleaseAsset::name)
            check(parts.isNotEmpty()) { "No release parts found for ${DesktopPlannerModel.ARCHIVE_NAME}" }

            val partFiles = parts.mapIndexed { index, asset ->
                val label = "part ${index + 1} of ${parts.size}"
                downloadPart(asset) { event ->
                    when (event) {
                        is DownloadProgress.Transferring ->
                            progress("Downloading $label: ${formatTransfer(event.received, event.total ?: asset.size)}")
                        DownloadProgress.Verifying -> progress("Verifying $label…")
                    }
                }
            }

            val archive = File(staging, DesktopPlannerModel.ARCHIVE_NAME)
            progress("Joining ${parts.size} parts…")
            BufferedOutputStream(FileOutputStream(archive)).use { output ->
                partFiles.forEach { part -> BufferedInputStream(FileInputStream(part)).use { it.copyTo(output) } }
            }

            val checksumAsset = assets.singleOrNull { it.name == "${DesktopPlannerModel.ARCHIVE_NAME}.sha256" }
                ?: error("Missing archive checksum for ${DesktopPlannerModel.ARCHIVE_NAME}")
            val expectedSha = downloadText(checksumAsset.downloadUrl).trim().substringBefore(' ').lowercase()
            check(expectedSha.matches(SHA256_REGEX)) { "Invalid archive checksum" }
            progress("Verifying archive…")
            val actualSha = sha256(archive)
            check(actualSha == expectedSha) { "Archive checksum mismatch: expected $expectedSha, got $actualSha" }

            progress("Unpacking…")
            val extracted = File(staging, "extracted").apply {
                deleteRecursively()
                mkdirs()
            }
            extractTarGz(archive, extracted)
            locate(extracted) ?: error("Planner archive has no ONNX model and tokenizer")

            destination.deleteRecursively()
            destination.parentFile?.mkdirs()
            if (!extracted.renameTo(destination)) {
                extracted.copyRecursively(destination, overwrite = true)
            }
            staging.deleteRecursively()
            installed() ?: error("Installed planner could not be resolved")
        }
    }

    /** Deletes the installed model and any partial download. */
    suspend fun remove() = mutex.withLock {
        withContext(Dispatchers.IO) { installRoot.deleteRecursively() }
    }

    private fun locate(root: File): InstalledPlannerModel? {
        if (!root.isDirectory) return null
        val files = root.walkTopDown().filter(File::isFile).toList()
        val onnx = files.firstOrNull { it.name == "model.onnx" }
            ?: files.firstOrNull { it.extension.equals("onnx", ignoreCase = true) }
            ?: return null
        if (files.none { it.name == "tokenizer.json" } || files.none { it.name == "config.json" }) return null
        return InstalledPlannerModel(root = root, onnxModel = onnx)
    }

    private suspend fun loadReleaseAssets(): List<ReleaseAsset> {
        val response = httpClient.get(DesktopPlannerModel.RELEASE_API_URL)
        check(response.status.isSuccess()) { "Release metadata request failed: ${response.status}" }
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: error("Release metadata is not a JSON object")
        val assets = root["assets"] as? JsonArray ?: error("Release metadata has no assets")
        return assets.mapNotNull { element ->
            val asset = element as? JsonObject ?: return@mapNotNull null
            ReleaseAsset(
                name = asset["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                downloadUrl = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                digest = asset["digest"]?.jsonPrimitive?.content,
                size = asset["size"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }
    }

    private suspend fun downloadPart(asset: ReleaseAsset, onProgress: (DownloadProgress) -> Unit): File {
        val expectedSha = asset.digest?.removePrefix("sha256:")?.lowercase()?.takeIf { it.matches(SHA256_REGEX) }
            ?: error("Release asset ${asset.name} is missing a SHA-256 digest")
        return downloader.downloadVerified(
            url = asset.downloadUrl,
            output = File(staging, asset.name),
            expectedSha256 = expectedSha,
            expectedSize = asset.size,
            onProgress = onProgress,
        )
    }

    private suspend fun downloadText(url: String): String {
        val response = httpClient.get(url)
        check(response.status.isSuccess()) { "Download failed: ${response.status}" }
        return response.bodyAsText()
    }

    private fun extractTarGz(archive: File, destination: File) {
        val root = destination.canonicalFile
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val output = File(root, entry.name).canonicalFile
                check(output.path == root.path || output.path.startsWith(root.path + File.separator)) {
                    "Unsafe archive entry: ${entry.name}"
                }
                when {
                    entry.isDirectory -> output.mkdirs()
                    entry.isFile -> {
                        output.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(output)).use { tar.copyTo(it) }
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatTransfer(received: Long, total: Long?): String {
        val mb = 1024L * 1024L
        if (total == null || total <= 0L) return "${received / mb} MB"
        return "${received / mb} of ${total / mb} MB (${(received * 100 / total).coerceIn(0L, 100L)}%)"
    }

    private data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val digest: String?,
        val size: Long?,
    )

    private companion object {
        val SHA256_REGEX = Regex("[0-9a-f]{64}")
    }
}
