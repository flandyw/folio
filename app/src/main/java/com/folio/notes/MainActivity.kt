package com.folio.notes

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
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
    private val stylusActivity = StylusActivity()
    private val model: FolioViewModel by viewModels()
    private var shortcutRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            CompositionLocalProvider(LocalStylusActivity provides stylusActivity) {
                FolioApp(model, shortcutRequest)
            }
        }
    }
    private fun observeStylus(event: MotionEvent) {
        if ((0 until event.pointerCount).any {
                event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS ||
                    event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
            }) stylusActivity.record(event.eventTime)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        observeStylus(event)
        return super.dispatchTouchEvent(event)
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        observeStylus(event)
        return super.dispatchGenericMotionEvent(event)
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
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW -> {
                if (intent.type != "application/pdf") return
                val uris = buildList {
                    if (intent.action == Intent.ACTION_VIEW) intent.data?.let(::add)
                    if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
                    if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
                    }
                    intent.clipData?.let { clip ->
                        repeat(clip.itemCount) { clip.getItemAt(it).uri?.let(::add) }
                    }
                }.distinct().filter { it.scheme == "content" }
                model.preparePdfImport(uris)
            }
        }
    }
}
