package com.hereliesaz.haive

import android.content.Context
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRole
import com.hereliesaz.geministrator.orchestration.OrchestrationEpoch8ModelCatalog
import com.hereliesaz.geministrator.orchestration.OrchestrationModelReleaseBundle
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

internal data class InstalledOrchestrationModel(
    val role: OrchestrationAgentRole,
    val root: File,
    val onnxModel: File,
    val tokenizerJson: File,
    val tokenizerConfig: File?,
    val generationConfig: File?,
)

internal class AndroidOrchestrationModelInstaller(
    context: Context,
    private val httpClient: HttpClient,
) {
    private val installRoot = File(context.filesDir, "haive/orchestration")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ensureInstalled(role: OrchestrationAgentRole): InstalledOrchestrationModel =
        withContext(Dispatchers.IO) {
            val bundle = OrchestrationEpoch8ModelCatalog.bundleFor(role)
            val destination = File(installRoot, "${bundle.releaseTag}/${role.name.lowercase()}")
            findInstalled(role, destination)?.let { return@withContext it }

            val staging = File(installRoot, ".staging/${bundle.releaseTag}/${role.name.lowercase()}")
            staging.deleteRecursively()
            staging.mkdirs()

            try {
                val assets = loadReleaseAssets(bundle)
                val parts = assets
                    .filter { it.name.startsWith("${bundle.archiveName}.part") && !it.name.endsWith(".sha256") }
                    .sortedBy(ReleaseAsset::name)
                check(parts.isNotEmpty()) { "No release parts found for ${bundle.archiveName}" }

                val partFiles = parts.map { asset -> downloadVerifiedPart(asset, staging) }
                val archive = File(staging, bundle.archiveName)
                combineParts(partFiles, archive)

                val archiveChecksum = assets.singleOrNull { it.name == "${bundle.archiveName}.sha256" }
                    ?: error("Missing archive checksum for ${bundle.archiveName}")
                val expectedArchiveSha = downloadText(archiveChecksum.downloadUrl)
                    .trim()
                    .substringBefore(' ')
                    .lowercase()
                check(expectedArchiveSha.matches(Regex("[0-9a-f]{64}"))) {
                    "Invalid archive checksum for ${bundle.archiveName}"
                }
                verifySha256(archive, expectedArchiveSha)

                val extracted = File(staging, "extracted")
                extracted.mkdirs()
                extractTarGzSafely(archive, extracted)
                locateInstalledModel(role, extracted)

                destination.parentFile?.mkdirs()
                destination.deleteRecursively()
                if (!extracted.renameTo(destination)) {
                    extracted.copyRecursively(destination, overwrite = true)
                    check(destination.isDirectory) {
                        "Could not finalize orchestration model installation for $role"
                    }
                }
                staging.deleteRecursively()
                findInstalled(role, destination)
                    ?: error("Installed orchestration model could not be resolved for $role")
            } catch (failure: Throwable) {
                staging.deleteRecursively()
                throw failure
            }
        }

    suspend fun ensurePlannerAndRepairInstalled(): Map<OrchestrationAgentRole, InstalledOrchestrationModel> =
        mapOf(
            OrchestrationAgentRole.Planner to ensureInstalled(OrchestrationAgentRole.Planner),
            OrchestrationAgentRole.PlanRepair to ensureInstalled(OrchestrationAgentRole.PlanRepair),
        )

    private suspend fun loadReleaseAssets(bundle: OrchestrationModelReleaseBundle): List<ReleaseAsset> {
        val response = httpClient.get(bundle.releaseApiUrl)
        check(response.status.isSuccess()) { "Release metadata request failed: ${response.status}" }
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: error("Release metadata is not a JSON object")
        val assets = root["assets"] as? JsonArray ?: error("Release metadata has no assets array")
        return assets.mapNotNull { element ->
            val asset = element as? JsonObject ?: return@mapNotNull null
            val name = asset["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val digest = asset["digest"]?.jsonPrimitive?.content
            ReleaseAsset(name = name, downloadUrl = url, digest = digest)
        }
    }

    private suspend fun downloadVerifiedPart(asset: ReleaseAsset, destination: File): File {
        val expectedSha = asset.digest
            ?.removePrefix("sha256:")
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: error("Release asset ${asset.name} is missing a SHA-256 digest")
        val output = File(destination, asset.name)
        if (output.isFile && sha256(output) == expectedSha) return output

        val temporary = File(destination, "${asset.name}.download")
        temporary.delete()
        val response = httpClient.get(asset.downloadUrl)
        check(response.status.isSuccess()) { "Download failed for ${asset.name}: ${response.status}" }
        val digest = MessageDigest.getInstance("SHA-256")
        response.bodyAsChannel().let { channel ->
            FileOutputStream(temporary).buffered().use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (!channel.isClosedForRead) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count > 0) {
                        stream.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
        }
        val actualSha = digest.digest().toHex()
        check(actualSha == expectedSha) {
            temporary.delete()
            "SHA-256 mismatch for ${asset.name}: expected $expectedSha, got $actualSha"
        }
        output.delete()
        check(temporary.renameTo(output)) { "Could not finalize ${asset.name}" }
        return output
    }

    private suspend fun downloadText(url: String): String {
        val response = httpClient.get(url)
        check(response.status.isSuccess()) { "Download failed: ${response.status}" }
        return response.bodyAsText()
    }

    private fun combineParts(parts: List<File>, archive: File) {
        BufferedOutputStream(FileOutputStream(archive)).use { output ->
            parts.forEach { part ->
                BufferedInputStream(FileInputStream(part)).use { input -> input.copyTo(output) }
            }
        }
    }

    private fun extractTarGzSafely(archive: File, destination: File) {
        val root = destination.canonicalFile
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive))),
        ).use { tar ->
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
                        BufferedOutputStream(FileOutputStream(output)).use { stream -> tar.copyTo(stream) }
                    }
                }
            }
        }
    }

    private fun findInstalled(role: OrchestrationAgentRole, root: File): InstalledOrchestrationModel? =
        if (root.isDirectory) runCatching { locateInstalledModel(role, root) }.getOrNull() else null

    private fun locateInstalledModel(role: OrchestrationAgentRole, root: File): InstalledOrchestrationModel {
        val files = root.walkTopDown().filter(File::isFile).toList()
        val onnx = files.firstOrNull { it.name == "model.onnx" }
            ?: files.firstOrNull { it.extension.equals("onnx", ignoreCase = true) }
            ?: error("No ONNX model found in installed $role bundle")
        val tokenizer = files.firstOrNull { it.name == "tokenizer.json" }
            ?: error("No tokenizer.json found in installed $role bundle")
        return InstalledOrchestrationModel(
            role = role,
            root = root,
            onnxModel = onnx,
            tokenizerJson = tokenizer,
            tokenizerConfig = files.firstOrNull { it.name == "tokenizer_config.json" },
            generationConfig = files.firstOrNull { it.name == "generation_config.json" },
        )
    }

    private fun verifySha256(file: File, expected: String) {
        val actual = sha256(file)
        check(actual == expected) { "SHA-256 mismatch for ${file.name}: expected $expected, got $actual" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val digest: String?,
    )
}
