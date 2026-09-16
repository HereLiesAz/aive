package com.hereliesaz.geministrator.azphalt

import dev.whyoleg.cryptography.CryptographyProvider

internal actual fun platformAzphaltCryptographyProvider(): CryptographyProvider =
    CryptographyProvider.Default
