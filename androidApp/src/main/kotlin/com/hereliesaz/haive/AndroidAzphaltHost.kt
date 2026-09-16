package com.hereliesaz.haive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hereliesaz.geministrator.azphalt.AZPHALT_PACKAGE_MEDIA_TYPE
import com.hereliesaz.geministrator.azphalt.AZPHALT_PACKAGE_MEDIA_TYPE_DEPRECATED
import com.hereliesaz.geministrator.azphalt.AzphaltPackageImportRequest
import com.hereliesaz.geministrator.azphalt.AzphaltPackageVerifier
import com.hereliesaz.geministrator.azphalt.AzphaltRepositoryClient
import com.hereliesaz.geministrator.azphalt.AzphaltStoreService
import com.hereliesaz.geministrator.azphalt.AzphaltWorkflowPackageInstaller
import com.hereliesaz.geministrator.azphalt.KmpZipAzphaltArchiveReader
import com.hereliesaz.geministrator.azphalt.SettingsAzphaltInstallStore
import com.hereliesaz.geministrator.azphalt.SettingsAzphaltPublisherPinStore
import com.hereliesaz.geministrator.azphalt.defaultAzphaltEd25519Verifier
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import io.ktor.client.HttpClient
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android boundary for opening/sharing `.azp` packages into the common verified Store flow. */
internal class AndroidAzphaltHost(
    context: Context,
    httpClient: HttpClient,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val installStore = SettingsAzphaltInstallStore()
    private val publisherPins = SettingsAzphaltPublisherPinStore()
    private val repository = AzphaltRepositoryClient(httpClient)
    private val verifier = AzphaltPackageVerifier(
        archiveReader = KmpZipAzphaltArchiveReader(),
        ed25519 = defaultAzphaltEd25519Verifier(),
        publisherPins = publisherPins,
    )

    val persistence = SettingsWorkflowPersistence.createDefault()
    val service = AzphaltStoreService(
        repository = repository,
        verifier = verifier,
        installer = AzphaltWorkflowPackageInstaller(
            persistence = persistence,
            installStore = installStore,
            publisherPins = publisherPins,
        ),
        installStore = installStore,
    )

    var importRequest by mutableStateOf<AzphaltPackageImportRequest?>(null)
        private set

    private var nextRequestId = 0L

    fun handleIntent(intent: Intent?) {
        val incoming = intent ?: return
        val uri = incoming.azphaltUri() ?: return
        if (!incoming.looksLikeAzphaltPackage(uri)) return
        val requestId = ++nextRequestId

        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { appContext.readAzphaltBytes(uri) }
                importRequest = AzphaltPackageImportRequest(
                    requestId = requestId,
                    bytes = bytes,
                    sourceLabel = uri.lastPathSegment?.substringAfterLast('/'),
                )
            } catch (failure: Exception) {
                val message = failure.message?.takeIf(String::isNotBlank)
                    ?: "The Azphalt package could not be opened."
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun consumeImport(requestId: Long) {
        if (importRequest?.requestId == requestId) {
            importRequest = null
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun Intent.azphaltUri(): Uri? = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> sharedStreamUri()
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun Intent.sharedStreamUri(): Uri? = getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    private fun Intent.looksLikeAzphaltPackage(uri: Uri): Boolean {
        val mediaType = type?.substringBefore(';')?.trim()?.lowercase()
        if (mediaType == AZPHALT_PACKAGE_MEDIA_TYPE || mediaType == AZPHALT_PACKAGE_MEDIA_TYPE_DEPRECATED) {
            return true
        }
        return uri.lastPathSegment?.substringBefore('?')?.endsWith(".azp", ignoreCase = true) == true
    }

    private fun Context.readAzphaltBytes(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri)
            ?: error("Unable to open Azphalt package")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= KmpZipAzphaltArchiveReader.DEFAULT_MAX_ARCHIVE_BYTES) {
                    "Azphalt package exceeds compressed size limit of ${KmpZipAzphaltArchiveReader.DEFAULT_MAX_ARCHIVE_BYTES} bytes"
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }
}
