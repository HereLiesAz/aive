package com.hereliesaz.geministrator.azphalt

/**
 * Host-neutral handoff for a package opened or shared into Haive.
 * [requestId] is host-generated so opening the same URI twice still produces two review requests.
 */
data class AzphaltPackageImportRequest(
    val requestId: Long,
    val bytes: ByteArray,
    val sourceLabel: String? = null,
) {
    init {
        require(bytes.isNotEmpty()) { "Azphalt import is empty" }
    }
}
