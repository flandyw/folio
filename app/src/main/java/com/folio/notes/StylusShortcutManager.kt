package com.folio.notes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock

/**
 * One vendor input path that can report a stylus shortcut.
 *
 * Add an adapter per OEM so neither the drawing engine nor the editor depends on vendor APIs.
 * Adapters must tolerate being started on hardware that does not implement them.
 */
interface StylusShortcutAdapter {
    val name: String
    fun start(onShortcut: () -> Unit)
    fun stop()
}

/**
 * The OnePlus/OPPO Pencil double tap, delivered as a vendor broadcast.
 *
 * The matching receiver permission is declared `normal`, so a normally installed app can hold it
 * with no runtime prompt and no privileged install. This is unrelated to the protected IPE
 * haptics binder service (`com.oplus.permission.safe.IOT`), which stays unavailable.
 */
class OplusStylusShortcutAdapter(private val context: Context) : StylusShortcutAdapter {
    override val name = "OnePlus Pencil"
    private var receiver: BroadcastReceiver? = null

    override fun start(onShortcut: () -> Unit) {
        if (receiver != null) return
        val callback = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Early firmware may attach an int "action" extra; the broadcast itself is enough.
                if (intent?.action == ACTION_DOUBLE_CLICK) onShortcut()
            }
        }
        val filter = IntentFilter(ACTION_DOUBLE_CLICK)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // Exported on purpose: the broadcast originates in the OPlus IPeManager process.
                context.registerReceiver(callback, filter, DOUBLE_CLICK_PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(callback, filter, DOUBLE_CLICK_PERMISSION, null)
            }
            receiver = callback
        } catch (_: SecurityException) {
            // Firmware without the vendor receiver permission simply has no double-tap source.
        }
    }

    override fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    companion object {
        const val ACTION_DOUBLE_CLICK = "com.oplus.ipemanager.action.PENCIL_DOUBLE_CLICK"
        const val DOUBLE_CLICK_PERMISSION = "com.oplus.ipemanager.permission.receiver.DOUBLE_CLICK"
    }
}

/**
 * Fans vendor stylus shortcuts into a single callback and de-duplicates firmware that may report
 * one physical gesture through more than one path. Registration follows the active editor.
 *
 * Callbacks arrive on the main thread, matching `registerReceiver`'s default scheduler.
 */
class StylusShortcutManager(context: Context) {
    private val adapters: List<StylusShortcutAdapter> = listOf(OplusStylusShortcutAdapter(context.applicationContext))
    private var listener: (() -> Unit)? = null
    private var registered = false
    private var lastEvent = 0L

    fun start(listener: () -> Unit) {
        this.listener = listener
        if (registered) return
        registered = true
        adapters.forEach { it.start(::dispatch) }
    }

    fun stop() {
        listener = null
        if (!registered) return
        registered = false
        adapters.forEach { it.stop() }
    }

    /** One physical gesture must produce one tool change even across two input paths. */
    private fun dispatch() {
        val now = SystemClock.uptimeMillis()
        if (now - lastEvent < DEBOUNCE_MS) return
        lastEvent = now
        listener?.invoke()
    }

    private companion object {
        const val DEBOUNCE_MS = 150L
    }
}
