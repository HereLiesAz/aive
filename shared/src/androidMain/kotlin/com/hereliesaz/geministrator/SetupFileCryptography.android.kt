package com.hereliesaz.geministrator

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK
import org.bouncycastle.jce.provider.BouncyCastleProvider

private val bundledBouncyCastle by lazy { CryptographyProvider.JDK(BouncyCastleProvider()) }

internal actual fun setupFileCryptographyProvider(): CryptographyProvider = bundledBouncyCastle
