package com.aria.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aria.ai.ui.navigation.AriaNavGraph
import com.aria.ai.ui.theme.AriaTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity Compose host for Aria Ai.
 *
 * Everything (home HUD, provider vault, settings) is a Compose destination in
 * [AriaNavGraph]; the activity itself stays a thin shell so the voice pipeline
 * always runs against Hilt-injected singletons.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AriaTheme {
                AriaNavGraph()
            }
        }
    }
}