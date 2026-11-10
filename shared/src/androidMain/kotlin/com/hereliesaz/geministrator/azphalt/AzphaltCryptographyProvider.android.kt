package com.hereliesaz.geministrator.azphalt

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Android's default JDK provider cannot reliably verify Ed25519 (including on API 26).
 * Use the bundled BouncyCastle implementation explicitly, as the setup-file verifier does.
 * An unavailable algorithm must not masquerade as an invalid package signature.
 */
private val bundledAzphaltBouncyCastle by lazy { CryptographyProvider.JDK(BouncyCastleProvider()) }

internal actual fun platformAzphaltCryptographyProvider(): CryptographyProvider =
    bundledAzphaltBouncyCastle
