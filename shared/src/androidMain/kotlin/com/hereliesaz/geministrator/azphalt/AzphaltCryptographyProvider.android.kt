package com.hereliesaz.geministrator.azphalt

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK
import org.bouncycastle.jce.provider.BouncyCastleProvider

internal actual fun platformAzphaltCryptographyProvider(): CryptographyProvider =
    CryptographyProvider.JDK(BouncyCastleProvider())
