package com.hereliesaz.aive

/**
 * Whether this distribution exposes distributed compute (the Compute screen and relay session).
 * Held back from the Play build for 1.0: the compute relay's shared-token trust model is not production-ready (see docs/THREAT_MODEL.md).
 */
internal const val DISTRIBUTED_COMPUTE_AVAILABLE: Boolean = false

/**
 * Whether this distribution can hold the Remote access link open. Off in the Play build until the Play Console
 * foreground-service (specialUse) declaration is made; the manifest here declares neither the service nor its
 * permissions (docs/Remote-API.md).
 */
internal const val REMOTE_ACCESS_AVAILABLE: Boolean = false
