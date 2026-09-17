package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2TerrariumSubject

/**
 * Type-specific overload used while RustNodeTerrarium still contains its transitional generic
 * helper. Keeping subject and packet value types distinct prevents Kotlin from inferring the map
 * value as H2g2TerrariumSubject.
 */
internal inline fun Iterable<H2g2TerrariumSubject>.associateNotNull(
    transform: (H2g2TerrariumSubject) -> Pair<String, NodeCreatureRenderPacket>?,
): Map<String, NodeCreatureRenderPacket> = buildMap {
    this@associateNotNull.forEach { subject ->
        transform(subject)?.let { (key, value) -> put(key, value) }
    }
}
