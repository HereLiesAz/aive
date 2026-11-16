package com.hereliesaz.aive

/**
 * Whether this distribution exposes distributed compute (the Compute screen and relay session).
 * Available in the GitHub release build for testing ahead of its production release.
 */
internal const val DISTRIBUTED_COMPUTE_AVAILABLE: Boolean = true

/**
 * Whether this distribution can hold the Remote access link open (a foreground service that keeps the relay
 * connection while the keeper has Remote access on). The GitHub release build declares it. The Google Play build
 * does not: it would need the Play Console foreground-service (specialUse) declaration, an owner step.
 */
internal const val REMOTE_ACCESS_AVAILABLE: Boolean = true
