package com.hereliesaz.geministrator.azphalt

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK

/**
 * cryptography-provider-jdk-bc registers BouncyCastle as the JDK provider on Android.
 * Keep the concrete BC class behind that dependency instead of leaking its implementation
 * package into Haive's compile classpath.
 */
internal actual fun platformAzphaltCryptographyProvider(): CryptographyProvider =
    CryptographyProvider.JDK
