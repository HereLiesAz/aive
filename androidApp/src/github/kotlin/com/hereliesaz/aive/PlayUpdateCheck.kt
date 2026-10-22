package com.hereliesaz.aive

import androidx.activity.ComponentActivity

/** The GitHub build carries no Google Play library, so it has no Play update channel. */
@Suppress("UNUSED_PARAMETER")
internal fun createPlayUpdateCheck(activity: ComponentActivity): PlayUpdateCheck? = null
