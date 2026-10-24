package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.azphalt.platformAzphaltCryptographyProvider
import dev.whyoleg.cryptography.CryptographyProvider

internal actual fun setupFileCryptographyProvider(): CryptographyProvider = platformAzphaltCryptographyProvider()
