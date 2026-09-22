package com.lulu.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.lulu.music.ui.BeansApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The app draws its own glass backdrop edge-to-edge; bars stay transparent.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            BeansApp()
        }
    }
}
