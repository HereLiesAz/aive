package com.hereliesaz.geministrator.azphalt

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * A model asset's licence as its package states it (`assets[].modelLicense`), read for the keeper to see before an install.
 * [commercialUse] is `true` when the licence allows commercial use freely, `false` when it does not, and null (with
 * [terms] saying how) when it depends: a model licensed free below a revenue line, say. A model whose licence is not a
 * plain yes has to be accepted before it is installed ([needsAcceptance]); the keeper, not the app, decides whether their
 * use is allowed.
 */
data class AzphaltModelLicense(
    val name: String?,
    val spdx: String?,
    val url: String?,
    val commercialUse: Boolean?,
    val terms: String?,
) {
    /** True unless the package says commercial use is allowed outright. */
    val needsAcceptance: Boolean get() = commercialUse != true

    /** One line for the keeper: the licence's name, then its terms, then where to read it. */
    fun summary(): String = listOfNotNull(
        (name ?: spdx)?.takeIf(String::isNotBlank),
        terms?.takeIf(String::isNotBlank),
        url?.takeIf(String::isNotBlank),
    ).joinToString(" — ")

    companion object {
        /** Reads a `modelLicense` value; null when [element] is absent or not an object. */
        fun from(element: JsonElement?): AzphaltModelLicense? {
            val obj = element as? JsonObject ?: return null
            fun text(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            val commercial = (obj["commercialUse"] as? JsonPrimitive)?.let { it.booleanOrNull }
            return AzphaltModelLicense(text("name"), text("spdx"), text("url"), commercial, text("terms"))
        }
    }
}

/** The licence of this model asset, or null when the package states none. */
fun AzphaltAssetEntry.license(): AzphaltModelLicense? = AzphaltModelLicense.from(modelLicense)
