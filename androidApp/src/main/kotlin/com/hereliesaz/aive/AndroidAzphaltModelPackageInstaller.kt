package com.hereliesaz.aive

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import com.hereliesaz.geministrator.azphalt.AzphaltAssetEntry
import com.hereliesaz.geministrator.azphalt.AzphaltModelPackageInstaller
import com.hereliesaz.geministrator.azphalt.AzphaltPreparedModelInstall
import com.hereliesaz.geministrator.azphalt.InstalledAzphaltModelFile
import com.hereliesaz.geministrator.azphalt.InstalledAzphaltModelPackage
import com.hereliesaz.geministrator.inference.InferenceModelDescriptor
import com.hereliesaz.geministrator.inference.SettingsInferenceStateStore
import io.ktor.client.HttpClient
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class AndroidAzphaltModelPackageInstaller(
    context: Context,
    httpClient: HttpClient,
) : AzphaltModelPackageInstaller {
    override val supportedAssetTypes: Set<String> = setOf(
        "onnx", "tflite", "litert", "sherpa-bundle", "model", "task", "vosk-bundle",
    )

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "azphalt/models")
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val downloader = AndroidResumableFileDownloader(httpClient)
    private val inferenceState = SettingsInferenceStateStore.createDurable()
    private val environment = OrtEnvironment.getEnvironment()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun installed(): List<InstalledAzphaltModelPackage> = withContext(Dispatchers.IO) {
        readInstalled()
    }

    override suspend fun install(
        prepared: AzphaltPreparedModelInstall,
        nowEpochMillis: Long,
    ): InstalledAzphaltModelPackage = withContext(Dispatchers.IO) {
        val packageId = prepared.detail.id
        val packageSegment = safeSegment(packageId)
        val versionSegment = safeSegment(prepared.version)
        val packageRoot = File(root, packageSegment)
        val finalDir = File(packageRoot, versionSegment)
        val staging = File(File(root, ".staging"), "$packageSegment-$versionSegment")
        staging.deleteRecursively()
        check(staging.mkdirs() || staging.isDirectory) { "Could not create model staging directory" }

        val previous = readInstalled().firstOrNull { it.packageId == packageId }
        val installedArtifacts = mutableListOf<InstalledAzphaltModelFile>()
        val descriptors = mutableListOf<InferenceModelDescriptor>()

        try {
            prepared.assets.forEachIndexed { index, asset ->
                val assetDir = File(staging, "asset-$index")
                check(assetDir.mkdirs() || assetDir.isDirectory) { "Could not create model asset directory" }
                val files = materializeAsset(prepared, asset, assetDir)
                val backend = validateAndResolveBackend(asset, files)
                val logicalModelId = buildLogicalModelId(packageId, asset, index)
                val artifactTarget = when (backend) {
                    BACKEND_SHERPA, BACKEND_VOSK -> assetDir
                    else -> choosePrimaryFile(asset, files)
                }
                val releaseDigest = aggregateDigest(files)
                val relativePath = when {
                    artifactTarget == assetDir -> "$packageSegment/$versionSegment/asset-$index"
                    else -> "$packageSegment/$versionSegment/asset-$index/${artifactTarget.name}"
                }

                installedArtifacts += InstalledAzphaltModelFile(
                    logicalModelId = logicalModelId,
                    type = asset.type.lowercase(),
                    role = asset.role,
                    relativePath = relativePath,
                    sha256 = releaseDigest,
                    byteSize = files.sumOf { it.file.length() },
                )

                val finalArtifactPath = File(root, relativePath).absolutePath
                descriptors += InferenceModelDescriptor(
                    logicalModelId = logicalModelId,
                    baseModelId = packageId,
                    backend = backend,
                    artifactPath = finalArtifactPath,
                    capabilities = buildSet {
                        add("local-model")
                        add("azphalt")
                        add("azphalt-package:$packageId")
                        add("azphalt-version:${prepared.version}")
                        add("asset-type:${asset.type.lowercase()}")
                        asset.role?.takeIf(String::isNotBlank)?.let { add("role:$it") }
                        asset.requirements?.jsonObject?.get("runtime")?.jsonPrimitive?.content
                            ?.takeIf(String::isNotBlank)?.let { add("runtime:$it") }
                        asset.requirements?.jsonObject?.get("quantization")?.jsonPrimitive?.content
                            ?.takeIf(String::isNotBlank)?.let { add("quantization:$it") }
                    },
                    releaseDigest = releaseDigest,
                )
            }

            packageRoot.mkdirs()
            finalDir.deleteRecursively()
            finalizeDirectory(staging, finalDir)
            descriptors.forEach { inferenceState.registerModel(it) }

            val installed = InstalledAzphaltModelPackage(
                packageId = packageId,
                version = prepared.version,
                repositoryUrl = prepared.repositoryUrl,
                files = installedArtifacts,
                signed = prepared.verification.packageContents.signed,
                signerPublicKey = prepared.verification.packageContents.signerPublicKey,
                installedAtEpochMillis = nowEpochMillis,
            )
            val next = readInstalled().filterNot { it.packageId == packageId } + installed
            check(writeInstalled(next)) { "Could not persist installed Azphalt model package" }

            previous?.files.orEmpty()
                .filterNot { old -> installedArtifacts.any { it.logicalModelId == old.logicalModelId } }
                .forEach { inferenceState.removeModel(it.logicalModelId) }

            packageRoot.listFiles()
                ?.filter { it.isDirectory && it.name != versionSegment }
                ?.forEach(File::deleteRecursively)
            installed
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
    }

    override suspend fun remove(packageId: String) = withContext(Dispatchers.IO) {
        val installed = readInstalled()
        val target = installed.firstOrNull { it.packageId == packageId } ?: return@withContext
        target.files.forEach { inferenceState.removeModel(it.logicalModelId) }
        File(root, safeSegment(packageId)).deleteRecursively()
        check(writeInstalled(installed.filterNot { it.packageId == packageId })) {
            "Could not persist Azphalt model removal"
        }
    }

    private suspend fun materializeAsset(
        prepared: AzphaltPreparedModelInstall,
        asset: AzphaltAssetEntry,
        destination: File,
    ): List<MaterializedModelFile> {
        val pkg = prepared.verification.packageContents
        if (asset.files.isNotEmpty()) {
            return asset.files.map { member ->
                val output = File(destination, safeRelativePath(member.name))
                output.parentFile?.mkdirs()
                val bundledPath = member.path?.takeIf(String::isNotBlank)
                if (bundledPath != null) {
                    val bytes = requireNotNull(pkg.payload[bundledPath]) { "Bundled model member $bundledPath is missing" }
                    writeBundled(bytes, output)
                    MaterializedModelFile(output, sha256(output))
                } else {
                    val url = requireNotNull(member.remoteUrl) { "Model member ${member.name} has no source" }
                    val digest = normalizeChecksum(requireNotNull(member.checksum) {
                        "Remote model member ${member.name} has no checksum"
                    })
                    MaterializedModelFile(
                        downloader.downloadVerified(url, output, digest, member.byteSize),
                        digest,
                    )
                }
            }
        }

        val bundledPath = asset.path.takeIf(String::isNotBlank)
        if (bundledPath != null) {
            val output = File(destination, safeSegment(File(bundledPath).name))
            val bytes = requireNotNull(pkg.payload[bundledPath]) { "Bundled model asset $bundledPath is missing" }
            writeBundled(bytes, output)
            return listOf(MaterializedModelFile(output, sha256(output)))
        }

        val url = requireNotNull(asset.remoteUrl) { "Model asset ${asset.role ?: asset.type} has no source" }
        val fileName = url.substringBefore('?').substringAfterLast('/').takeIf(String::isNotBlank)
            ?: "${asset.role ?: asset.type}.${asset.type}"
        val output = File(destination, safeSegment(fileName))
        val digest = normalizeChecksum(requireNotNull(asset.checksum) {
            "Remote model asset ${asset.role ?: asset.type} has no checksum"
        })
        return listOf(
            MaterializedModelFile(
                downloader.downloadVerified(url, output, digest, asset.byteSize),
                digest,
            ),
        )
    }

    private fun validateAndResolveBackend(
        asset: AzphaltAssetEntry,
        files: List<MaterializedModelFile>,
    ): String = when (asset.type.lowercase()) {
        "onnx" -> validateOnnx(files).let { BACKEND_ONNX }
        "tflite" -> validateTflite(choosePrimaryFile(asset, files)).let { BACKEND_TFLITE }
        "litert" -> validateTflite(choosePrimaryFile(asset, files)).let { BACKEND_LITERT }
        "task" -> validateTask(choosePrimaryFile(asset, files)).let { BACKEND_TASK }
        "sherpa-bundle" -> validateSherpa(files).let { BACKEND_SHERPA }
        "vosk-bundle" -> validateVosk(files).let { BACKEND_VOSK }
        "model" -> routeGenericModel(files)
        else -> error("Unsupported Azphalt model type ${asset.type}")
    }

    private fun validateOnnx(files: List<MaterializedModelFile>) {
        val models = files.filter { it.file.extension.equals("onnx", ignoreCase = true) }
        require(models.isNotEmpty()) { "ONNX asset contains no .onnx file" }
        models.forEach { model ->
            environment.createSession(model.file.absolutePath).use { session ->
                require(session.inputNames.isNotEmpty()) { "${model.file.name} exposes no ONNX inputs" }
                require(session.outputNames.isNotEmpty()) { "${model.file.name} exposes no ONNX outputs" }
            }
        }
    }

    private fun validateTflite(file: File) {
        require(file.isFile && file.length() >= 8L) { "${file.name} is not a valid model file" }
        FileInputStream(file).use { input ->
            val header = ByteArray(8)
            require(input.read(header) == header.size) { "${file.name} is truncated" }
            require(header.copyOfRange(4, 8).decodeToString() == "TFL3") {
                "${file.name} is not a TFLite/LiteRT flatbuffer"
            }
        }
    }

    private fun validateTask(file: File) {
        if (isTflite(file)) return
        require(file.isFile && file.length() > 0L) { "${file.name} is an empty task bundle" }
        runCatching {
            ZipFile(file).use { zip ->
                require(
                    zip.entries().asSequence().any { entry ->
                        !entry.isDirectory &&
                            (entry.name.endsWith(".tflite", ignoreCase = true) ||
                                entry.name.endsWith(".lite", ignoreCase = true))
                    },
                ) { "${file.name} task bundle contains no TFLite model" }
            }
        }.getOrElse {
            throw IllegalArgumentException("${file.name} is not a recognized MediaPipe/TFLite task bundle", it)
        }
    }

    private fun validateSherpa(files: List<MaterializedModelFile>) {
        val onnx = files.filter { it.file.extension.equals("onnx", ignoreCase = true) }
        require(onnx.isNotEmpty()) { "Sherpa bundle contains no ONNX graph" }
        require(files.any {
            it.file.name.equals("tokens.txt", ignoreCase = true) ||
                it.file.name.contains("tokens", ignoreCase = true)
        }) { "Sherpa bundle contains no token table" }
        validateOnnx(onnx)
    }

    private fun validateVosk(files: List<MaterializedModelFile>) {
        require(files.any { it.file.name.equals("final.mdl", ignoreCase = true) }) {
            "Vosk bundle contains no final.mdl"
        }
        require(files.any {
            it.file.name.equals("HCLG.fst", ignoreCase = true) ||
                it.file.name.equals("Gr.fst", ignoreCase = true)
        }) { "Vosk bundle contains no decoding graph" }
    }

    private fun routeGenericModel(files: List<MaterializedModelFile>): String {
        val names = files.map { it.file.name.lowercase() }
        return when {
            names.count { it.endsWith(".onnx") } > 1 && names.any { it.contains("tokens") } -> {
                validateSherpa(files)
                BACKEND_SHERPA
            }
            names.any { it.endsWith(".onnx") } -> {
                validateOnnx(files)
                BACKEND_ONNX
            }
            names.any { it.endsWith(".tflite") || it.endsWith(".lite") } -> {
                validateTflite(files.first {
                    it.file.extension.equals("tflite", true) || it.file.extension.equals("lite", true)
                }.file)
                BACKEND_TFLITE
            }
            names.any { it.endsWith(".task") } -> {
                validateTask(files.first { it.file.extension.equals("task", true) }.file)
                BACKEND_TASK
            }
            names.any { it == "final.mdl" } -> {
                validateVosk(files)
                BACKEND_VOSK
            }
            else -> BACKEND_GENERIC
        }
    }

    private fun choosePrimaryFile(asset: AzphaltAssetEntry, files: List<MaterializedModelFile>): File {
        require(files.isNotEmpty()) { "Model asset ${asset.role ?: asset.type} materialized no files" }
        val preferredExtensions = when (asset.type.lowercase()) {
            "onnx" -> listOf("onnx")
            "tflite", "litert" -> listOf("tflite", "lite")
            "task" -> listOf("task", "tflite")
            "model" -> listOf("onnx", "tflite", "lite", "task")
            else -> emptyList()
        }
        preferredExtensions.forEach { extension ->
            files.firstOrNull { it.file.extension.equals(extension, ignoreCase = true) }?.let { return it.file }
        }
        return files.first().file
    }

    private fun buildLogicalModelId(packageId: String, asset: AzphaltAssetEntry, index: Int): String {
        val suffix = asset.role?.trim()?.takeIf(String::isNotEmpty)
            ?: asset.type.trim().takeIf(String::isNotEmpty)
            ?: "model-$index"
        return "azphalt:$packageId:$suffix:$index"
    }

    private fun isTflite(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 8L) return@runCatching false
        FileInputStream(file).use { input ->
            val header = ByteArray(8)
            input.read(header) == header.size && header.copyOfRange(4, 8).decodeToString() == "TFL3"
        }
    }.getOrDefault(false)

    private fun writeBundled(bytes: ByteArray, output: File) {
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { it.write(bytes) }
    }

    private fun finalizeDirectory(staging: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (staging.renameTo(destination)) return
        staging.copyRecursively(destination, overwrite = true)
        check(staging.deleteRecursively()) { "Could not remove model staging directory" }
        check(destination.isDirectory) { "Could not finalize installed model directory" }
    }

    private fun aggregateDigest(files: List<MaterializedModelFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.file.path }.forEach { item ->
            digest.update(item.file.name.encodeToByteArray())
            digest.update(0.toByte())
            digest.update(item.sha256.encodeToByteArray())
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun normalizeChecksum(value: String): String =
        value.trim().lowercase().removePrefix("sha256-").also {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid model SHA-256 checksum" }
        }

    private fun safeSegment(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180).ifBlank { "model" }

    private fun safeRelativePath(value: String): String {
        val normalized = value.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank()) { "Model member name is blank" }
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Unsafe model member path $value"
        }
        return normalized
    }

    private fun readInstalled(): List<InstalledAzphaltModelPackage> {
        val encoded = prefs.getString(PREFS_KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(InstalledAzphaltModelPackage.serializer()), encoded)
        }.getOrElse { emptyList() }
    }

    private fun writeInstalled(value: List<InstalledAzphaltModelPackage>): Boolean =
        prefs.edit().putString(
            PREFS_KEY,
            json.encodeToString(ListSerializer(InstalledAzphaltModelPackage.serializer()), value),
        ).commit()

    private data class MaterializedModelFile(
        val file: File,
        val sha256: String,
    )

    private companion object {
        const val PREFS_NAME = "aive_azphalt_models"
        const val PREFS_KEY = "installed.v1"

        const val BACKEND_ONNX = "onnxruntime-android"
        const val BACKEND_TFLITE = "tflite-android"
        const val BACKEND_LITERT = "litert-android"
        const val BACKEND_TASK = "mediapipe-task-android"
        const val BACKEND_SHERPA = "sherpa-onnx-android"
        const val BACKEND_VOSK = "vosk-android"
        const val BACKEND_GENERIC = "generic-local-model"
    }
}
