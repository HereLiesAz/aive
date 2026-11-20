package com.hereliesaz.aive

import android.os.Build
import com.hereliesaz.geministrator.providers.cli.CliProbe
import com.hereliesaz.geministrator.providers.cli.CliRunResultParser
import com.hereliesaz.geministrator.providers.cli.CliRunScript
import io.ktor.client.HttpClient
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the hidden Linux runtime is doing, in terms the Keeper's room can say in plain words. Never shows a terminal.
 */
internal sealed interface CliRuntimeState {
    /** This build or this phone cannot have it; [reason] says why. */
    data class NotAvailable(val reason: String) : CliRuntimeState

    /** Nothing has been downloaded. It is prepared the first time the keeper presses Install or Test. */
    data object NotPrepared : CliRuntimeState

    /** Downloading, unpacking or installing the base packages. [percent] is known only while downloading. */
    data class Working(val stage: String, val percent: Int?) : CliRuntimeState

    data object Ready : CliRuntimeState

    data class Failed(val reason: String) : CliRuntimeState
}

/** What a run in the runtime printed: standard output and error together, its tail only, and how it ended. */
internal data class RuntimeExecResult(val exitCode: Int, val output: String, val timedOut: Boolean, val startFailure: String?)

/** The pinned pieces the runtime downloads. One place to change when the bootstrap is bumped (the proot pieces are pinned in androidApp/build.gradle.kts). */
internal object AndroidCliRuntimePins {
    /** Termux's own bootstrap release, as used by the design reference (HG2Gui): a tag, a URL and a SHA-256 are facts. */
    const val BOOTSTRAP_TAG = "bootstrap-2026.08.16-r1+apt.android-7"
    const val BOOTSTRAP_URL =
        "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.08.16-r1%2Bapt.android-7/bootstrap-aarch64.zip"
    const val BOOTSTRAP_SHA256 = "ed83429ff0835b97155c5f80f2c74abf892f691f9fb9259eaca52fe1fb88e8f0"
    const val BOOTSTRAP_BYTES = 32_649_082L
    const val ABI = "arm64-v8a"

    /** Bounds on what the unpacking will write, against an archive that is not what it claims to be. */
    const val MAX_UNPACKED_BYTES = 600L * 1024 * 1024
    const val MAX_ENTRIES = 20_000
    const val MIN_FREE_BYTES = 1_500L * 1024 * 1024
}

/**
 * The hidden Linux runtime that runs the vendors' CLIs on a phone (docs/Local-CLI-Agents.md, "On a phone"). Nothing is shown to
 * the keeper but a status. It is Termux's own pinned bootstrap, downloaded on first use into the app's private files and
 * checked against a SHA-256, run under **proot**: proot (with its loader, talloc and shmem libraries, fetched and hash-checked at
 * build time by androidApp/build.gradle.kts) ships in the APK as native libraries, the only place Android lets an app execute a
 * program from, and it maps the private Termux prefix to the paths Termux's programs expect (`/data/data/com.termux/files/usr`), so
 * nothing needs relocating. The guest programs are loaded by proot's loader, not by `execve` on a file in writable storage.
 *
 * No Android permission beyond INTERNET; everything lives under [dataRoot] and [remove] deletes it. **Not run on a device by this
 * project**: the flags below are from Termux's own `termux-chroot` script, and a first run may need them adjusted.
 */
internal class AndroidCliRuntime(
    private val nativeLibraryDir: File,
    private val dataRoot: File,
    httpClient: HttpClient,
    private val scope: CoroutineScope,
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    private val hostEnvironment: () -> Map<String, String> = { System.getenv() },
) {
    private val downloader = AndroidResumableFileDownloader(httpClient)
    private val random = SecureRandom()
    private val mutableState = MutableStateFlow<CliRuntimeState>(CliRuntimeState.NotPrepared)
    val state: StateFlow<CliRuntimeState> get() = mutableState

    private val root = File(dataRoot, "root")
    private val staging = File(dataRoot, "root.staging")
    private val previous = File(dataRoot, "root.previous")
    private val prefix = File(root, CliRunScript.RUNTIME_PREFIX.removePrefix("/"))
    private val home = File(root, CliRunScript.RUNTIME_HOME.removePrefix("/"))
    private val archive = File(dataRoot, "download/bootstrap-aarch64.zip")
    private val prootTmp = File(dataRoot, "proot-tmp")
    private val readyMarker = File(dataRoot, "ready")
    private val readyContent = AndroidCliRuntimePins.BOOTSTRAP_SHA256 + ":" + CliRunScript.BASE_PACKAGES.joinToString(",")

    private val prepareLock = Any()
    private var prepareJob: Job? = null
    private val runMutex = Mutex()

    init {
        mutableState.value = initialState()
    }

    private fun lib(name: String) = File(nativeLibraryDir, name)

    /** Why the runtime cannot exist here, or null. The proot libraries are put in the APK by the build; a build without them says so. */
    fun unavailableReason(): String? = when {
        AndroidCliRuntimePins.ABI !in supportedAbis -> "The Linux runtime needs a 64-bit ARM phone; this one is not."
        !lib("libproot.so").isFile || !lib("libproot-loader.so").isFile || !lib("libtalloc.so").isFile ->
            "The Linux runtime is not available in this build."
        else -> null
    }

    private fun initialState(): CliRuntimeState = unavailableReason()?.let { CliRuntimeState.NotAvailable(it) }
        ?: if (isPrepared()) CliRuntimeState.Ready else CliRuntimeState.NotPrepared

    private fun isPrepared(): Boolean =
        runCatching { readyMarker.isFile && readyMarker.readText() == readyContent && File(prefix, "bin/bash").exists() }.getOrDefault(false)

    /**
     * Prepares the runtime if it is not: download, unpack, base packages. The work runs in the runtime's own scope, so leaving the
     * screen does not cancel it; only [remove] does. Returns the state it ended in.
     */
    suspend fun ensureReady(): CliRuntimeState {
        unavailableReason()?.let { return CliRuntimeState.NotAvailable(it).also { s -> mutableState.value = s } }
        if (isPrepared()) return CliRuntimeState.Ready.also { mutableState.value = it }
        val job = synchronized(prepareLock) {
            prepareJob?.takeIf { it.isActive } ?: scope.launch(Dispatchers.IO) { prepare() }.also { prepareJob = it }
        }
        job.join()
        return mutableState.value
    }

    private suspend fun prepare() {
        try {
            dataRoot.mkdirs()
            if (!File(prefix, "bin/bash").exists()) {
                if (dataRoot.usableSpace < AndroidCliRuntimePins.MIN_FREE_BYTES) {
                    throw IOException("There is not enough free storage: the Linux runtime needs about 1.5 GB.")
                }
                download()
                mutableState.value = CliRuntimeState.Working("Unpacking the Linux runtime", null)
                unpack()
            }
            mutableState.value = CliRuntimeState.Working("Setting up the Linux runtime (packages from Termux's repository)", null)
            secondStage()
            val nonce = newNonce()
            val script = CliRunScript.installScript(nonce, CliRunScript.BASE_PACKAGES, null, update = true)
                ?: throw IOException("The base package list is not valid.")
            val result = runScript(script, INSTALL_TIMEOUT_MILLIS)
            val outcome = CliRunResultParser.parseInstall(result.output, nonce)
            if (result.startFailure != null) throw IOException("Could not start the runtime: ${result.startFailure}")
            if (!outcome.ok) throw IOException("Installing the base packages failed: ${outcome.summary}")
            readyMarker.writeText(readyContent)
            mutableState.value = CliRuntimeState.Ready
        } catch (cancelled: CancellationException) {
            mutableState.value = initialState()
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = CliRuntimeState.Failed((failure.message ?: "Preparing the Linux runtime failed.").take(600) + " Press Install to try again.")
        }
    }

    private suspend fun download() {
        archive.parentFile?.mkdirs()
        val partial = File(archive.parentFile, archive.name + ".download")
        mutableState.value = CliRuntimeState.Working("Downloading the Linux runtime", 0)
        coroutineScope {
            val ticker = launch {
                while (isActive) {
                    val percent = (partial.length() * 100 / AndroidCliRuntimePins.BOOTSTRAP_BYTES).toInt().coerceIn(0, 99)
                    mutableState.value = CliRuntimeState.Working("Downloading the Linux runtime", percent)
                    delay(500)
                }
            }
            try {
                downloader.downloadVerified(
                    AndroidCliRuntimePins.BOOTSTRAP_URL, archive, AndroidCliRuntimePins.BOOTSTRAP_SHA256, AndroidCliRuntimePins.BOOTSTRAP_BYTES,
                )
            } finally {
                ticker.cancel()
            }
        }
    }

    /** Unpacks the verified bootstrap into `root.staging`, checks it, and renames it over `root`; a failure leaves what was there. */
    private fun unpack() {
        deleteTree(staging)
        val stagedPrefix = File(staging, CliRunScript.RUNTIME_PREFIX.removePrefix("/"))
        stagedPrefix.mkdirs()
        File(staging, CliRunScript.RUNTIME_HOME.removePrefix("/")).mkdirs()
        val base = stagedPrefix.toPath().toAbsolutePath().normalize()
        var symlinks = ""
        var entries = 0
        var written = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                if (entries > AndroidCliRuntimePins.MAX_ENTRIES) throw IOException("The runtime archive has too many files.")
                if (entry.name == "SYMLINKS.txt") {
                    symlinks = zip.readBytes().decodeToString()
                } else {
                    val target = base.resolve(entry.name).normalize()
                    if (!target.startsWith(base)) throw IOException("The runtime archive names a path outside itself.")
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        written += writeEntry(zip, target.toFile())
                        if (written > AndroidCliRuntimePins.MAX_UNPACKED_BYTES) throw IOException("The runtime archive is larger than expected.")
                    }
                }
                zip.closeEntry()
            }
        }
        // Links last (as Termux does): each line is `target←./link`, the link relative to the prefix, the target as the guest sees it.
        symlinks.lineSequence().filter { it.contains('←') }.forEach { line ->
            val target = line.substringBefore('←')
            val link = base.resolve(line.substringAfter('←')).normalize()
            if (!link.startsWith(base) || target.isEmpty()) throw IOException("The runtime archive has a bad link.")
            Files.createDirectories(link.parent)
            if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(link, java.nio.file.Paths.get(target))
        }
        if (!File(stagedPrefix, "bin/bash").exists() || !File(stagedPrefix, "var/lib/dpkg/status").isFile) {
            deleteTree(staging)
            throw IOException("The runtime archive did not unpack into a working prefix.")
        }
        File(stagedPrefix, "tmp").mkdirs()
        swapIntoPlace()
    }

    /** Writes one entry (never through a link), marking it executable when it starts with `#!` or the ELF magic. Returns the bytes written. */
    private fun writeEntry(input: java.io.InputStream, file: File): Long {
        var total = 0L
        FileOutputStream(file).use { out ->
            val buffer = ByteArray(64 * 1024)
            var first = true
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (first && n >= 2) {
                    val script = buffer[0] == '#'.code.toByte() && buffer[1] == '!'.code.toByte()
                    val elf = n >= 4 && buffer[0] == 0x7f.toByte() && buffer[1] == 'E'.code.toByte() && buffer[2] == 'L'.code.toByte() && buffer[3] == 'F'.code.toByte()
                    if (script || elf) file.setExecutable(true, true)
                }
                first = false
                out.write(buffer, 0, n)
                total += n
            }
        }
        return total
    }

    private fun swapIntoPlace() {
        deleteTree(previous)
        val hadRoot = root.exists()
        if (hadRoot) move(root, previous)
        try {
            move(staging, root)
        } catch (failure: Exception) {
            if (hadRoot) runCatching { move(previous, root) }
            throw failure
        }
        if (hadRoot) deleteTree(previous)
        readyMarker.delete()
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IOException("Storage cannot rename a folder in one step.", unsupported)
        }
    }

    /** Termux's one-time package configuration after unpacking; a failure is logged in the state only if a later step needs it. */
    private suspend fun secondStage() {
        val stage = "${CliRunScript.RUNTIME_PREFIX}/etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"
        if (!File(root, stage.removePrefix("/")).isFile) return
        val result = execGuest(listOf("${CliRunScript.RUNTIME_PREFIX}/bin/bash", stage), SECOND_STAGE_TIMEOUT_MILLIS)
        if (result.startFailure != null) throw IOException("Could not start the runtime: ${result.startFailure}")
    }

    /** Which of [names] the runtime finds, or null when it is not ready. */
    suspend fun probe(names: List<String>): CliProbe? {
        if (mutableState.value != CliRuntimeState.Ready) return null
        val nonce = newNonce()
        val script = CliRunScript.probeScript(nonce, names) ?: return null
        val result = runScript(script, PROBE_TIMEOUT_MILLIS)
        return CliRunResultParser.parseProbe(result.output, nonce).takeIf { it.ready }
    }

    /**
     * Runs [script] with the runtime's bash. The script is a file under the guest's home for the length of the run (it holds the task's
     * prompt, as the run folder does) and is deleted afterwards. [extraEnvironment] is for the one-shot repository token: it is set
     * on this process only, never in an argument or on disk. One script at a time, so two runs never fight over the proot temp folder.
     */
    suspend fun runScript(script: String, timeoutMillis: Long, extraEnvironment: Map<String, String> = emptyMap()): RuntimeExecResult =
        runMutex.withLock {
            val folder = File(home, ".aive-scripts")
            val file = File(folder, newNonce() + ".sh")
            try {
                withContext(Dispatchers.IO) {
                    folder.mkdirs()
                    file.writeText(script)
                    file.setReadable(true, true)
                }
                val guestPath = CliRunScript.RUNTIME_HOME + "/.aive-scripts/" + file.name
                execGuest(listOf("${CliRunScript.RUNTIME_PREFIX}/bin/bash", guestPath), timeoutMillis, extraEnvironment)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { runCatching { file.delete() } }
            }
        }

    /** The proot command line: the same binds as Termux's `termux-chroot`, with this app's private tree as the root. */
    private fun prootCommand(guest: List<String>): List<String> = buildList {
        add(lib("libproot.so").absolutePath)
        add("--kill-on-exit")
        add("-r"); add(root.absolutePath)
        listOf("/system", "/apex", "/vendor", "/dev", "/proc").filter { File(it).exists() }.forEach { add("-b"); add(it) }
        if (File("/linkerconfig/ld.config.txt").exists()) { add("-b"); add("/linkerconfig/ld.config.txt") }
        add("-w"); add(CliRunScript.RUNTIME_HOME)
        addAll(guest)
    }

    private suspend fun execGuest(guest: List<String>, timeoutMillis: Long, extraEnvironment: Map<String, String> = emptyMap()): RuntimeExecResult {
        unavailableReason()?.let { return RuntimeExecResult(-1, "", false, it) }
        prootTmp.mkdirs()
        val builder = ProcessBuilder(prootCommand(guest))
        builder.redirectErrorStream(true)
        val environment = builder.environment()
        environment.clear()
        val host = hostEnvironment()
        ANDROID_VARIABLES.forEach { name -> host[name]?.let { environment[name] = it } }
        environment["PROOT_LOADER"] = lib("libproot-loader.so").absolutePath
        environment["PROOT_TMP_DIR"] = prootTmp.absolutePath
        environment["PROOT_NO_SECCOMP"] = "1"
        environment["LD_LIBRARY_PATH"] = nativeLibraryDir.absolutePath
        environment["HOME"] = CliRunScript.RUNTIME_HOME
        environment["PREFIX"] = CliRunScript.RUNTIME_PREFIX
        environment["PATH"] = CliRunScript.RUNTIME_PATH
        environment["TMPDIR"] = CliRunScript.RUNTIME_PREFIX + "/tmp"
        environment["LANG"] = "en_US.UTF-8"
        environment["TERM"] = "dumb"
        environment.putAll(extraEnvironment)
        return withContext(Dispatchers.IO) {
            val process = try {
                builder.start()
            } catch (failure: IOException) {
                return@withContext RuntimeExecResult(-1, "", false, failure.message ?: "proot would not start")
            }
            try {
                process.outputStream.close()
                val reader = async { readTail(process.inputStream, MAX_OUTPUT_BYTES) }
                val deadline = System.nanoTime() + timeoutMillis * 1_000_000
                var timedOut = false
                while (process.isAlive) {
                    ensureActive()
                    if (System.nanoTime() > deadline) {
                        timedOut = true
                        process.destroyForcibly()
                        break
                    }
                    delay(100)
                }
                val exit = if (timedOut) -1 else runCatching { process.waitFor() }.getOrDefault(-1)
                RuntimeExecResult(exit, reader.await(), timedOut, null)
            } finally {
                // Cancelled or finished: nothing of it is left running (proot's --kill-on-exit ends the guest with it).
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    /** Reads [input] to its end keeping only the last [max] bytes. */
    private fun readTail(input: java.io.InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max * 2) {
                val bytes = out.toByteArray()
                out.reset()
                out.write(bytes, bytes.size - max, max)
            }
        }
        val bytes = out.toByteArray()
        return String(bytes, maxOf(0, bytes.size - max), minOf(bytes.size, max), Charsets.UTF_8)
    }

    /** Deletes a run's folder from the guest's home, for a run that was stopped before its own script could clean up. */
    fun removeRun(runId: String) {
        runCatching { deleteTree(File(home, ".aive-cli/" + CliRunScript.safeRunId(runId))) }
    }

    /** Deletes everything the runtime holds and cancels a preparation in progress. */
    suspend fun remove() {
        synchronized(prepareLock) { prepareJob?.cancel() }
        prepareJob?.join()
        runMutex.withLock {
            withContext(Dispatchers.IO) {
                listOf(root, staging, previous, File(dataRoot, "download"), prootTmp).forEach { deleteTree(it) }
                readyMarker.delete()
            }
        }
        mutableState.value = initialState()
    }

    /** Bytes the runtime holds on disk, for the status line; 0 when none. */
    fun sizeOnDisk(): Long = runCatching {
        var total = 0L
        listOf(root, File(dataRoot, "download")).forEach { folder ->
            if (folder.exists()) Files.walkFileTree(folder.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
            })
        }
        total
    }.getOrDefault(0L)

    fun newNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Deletes [folder] without following a link out of it (the guest's links point at paths of its own). */
    private fun deleteTree(folder: File) {
        if (!Files.exists(folder.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(folder.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private companion object {
        const val MAX_OUTPUT_BYTES = 150_000
        const val PROBE_TIMEOUT_MILLIS = 60_000L
        const val SECOND_STAGE_TIMEOUT_MILLIS = 5 * 60_000L
        const val INSTALL_TIMEOUT_MILLIS = 40 * 60_000L

        /** What the system linker inside the guest needs from the app's own environment; nothing else is passed on. */
        val ANDROID_VARIABLES = listOf(
            "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_RUNTIME_ROOT", "ANDROID_TZDATA_ROOT", "ANDROID_I18N_ROOT", "ANDROID_ART_ROOT",
            "ANDROID_STORAGE", "EXTERNAL_STORAGE", "BOOTCLASSPATH",
        )
    }
}
