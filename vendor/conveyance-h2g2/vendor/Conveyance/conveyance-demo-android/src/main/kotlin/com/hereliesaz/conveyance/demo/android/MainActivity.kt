package com.hereliesaz.conveyance.demo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hereliesaz.conveyance.demo.DemoApp

/**
 * The launcher, and nothing else.
 *
 * Everything the desktop demo already draws lives in conveyance-demo's shared `commonMain` --
 * [DemoApp] never knew it was desktop-only, it just never had anywhere else to run. This class
 * exists only because a phone needs an Activity to host a composable; it makes no decision the
 * shared code hasn't already made.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DemoApp()
        }
    }
}
