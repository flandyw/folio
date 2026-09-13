package com.folio.notes

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private val model: FolioViewModel by viewModels()
    private var shortcutRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        if (savedInstanceState == null) handleIntent(intent)
        setContent { FolioApp(model, shortcutRequest) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The bars slide back after dialogs, the keyboard and app switches, so they are re-hidden here.
        if (hasFocus) hideSystemBars()
    }
    /**
     * The app runs fullscreen: the status bar and the gesture pill stay out of the way and only slide
     * back in for a swipe from their edge. Everything draws inside the display cutout inset instead.
     */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            "com.folio.notes.NEW_NOTE" -> shortcutRequest++
            Intent.ACTION_SEND -> if (intent.type == "application/pdf") {
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.clipData?.getItemAt(0)?.uri)?.let(model::importPdf)
            }
            Intent.ACTION_VIEW -> if (intent.type == "application/pdf") intent.data?.let(model::importPdf)
        }
    }
}
