package com.folio.notes.ink.pen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.folio.notes.app.AppPrefs
import com.folio.notes.model.ShapePickerTools
import com.folio.notes.model.Tool
import com.oplus.ocs.base.common.Feature
import java.util.UUID

/**
 * Continuous in-pen writing feedback for the OnePlus / OPPO Pencil Pro, through the vendor's own
 * pen service rather than raw BLE (BLE can set the texture but cannot switch writing feedback on:
 * that goes through the touch controller, which only the pen service drives).
 *
 * The pen service is handed out by ColorOS's open capability service (OCS) as capability
 * `IPE_PENCIL_CLIENT`. OCS only grants it to apps OPPO has licensed, checked by the package name the
 * caller *states*. Folio has no licence of its own, so it asks under the name of each licensed note
 * app (HiPaint, StarNote) in turn. That app must be installed: OCS reads its manifest's AUTH_CODE
 * and signing certificate. The direct service-broker request instead checks the real caller's UID,
 * so it cannot use these identities. If every name is refused, or on any other brand, feedback is
 * a silent no-op. A granted binder still needs device verification against the pen manager.
 *
 * Wire details come from the OCS SDK 1.0.11 shipped in StarNote: the auth listener receives a
 * `CapabilityInfo` (feature list, version, optional `AuthResult`, binder), and the binder speaks
 * `com.oplus.ipemanager.sdk.ISdkAidlInterface`. Every pen call is one-way, so a stalled service can
 * never block the input thread.
 */
class PenWritingFeedback(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var pen: IBinder? = null
    private var bound = false
    private var identities: ArrayDeque<String> = ArrayDeque()
    private var identity: String? = null
    private var running = false
    private var vibrating = false
    private var texture = -1

    /** Last outcome, shown in Settings: which identity was accepted, or why none was. */
    var status by mutableStateOf<FeedbackStatus>(FeedbackStatus.Idle)
        private set

    val isReady: Boolean get() = pen?.isBinderAlive == true

    fun start() {
        if (running || !isSupported()) return
        running = true
        identities = ArrayDeque(LICENSED_APPS)
        next()
    }

    fun stop() {
        if (!running) return
        end()
        running = false
        handler.removeCallbacksAndMessages(null)
        release()
        identities.clear()
        status = FeedbackStatus.Idle
    }

    /** Pen tip down with a writing tool; [tool] is its `TEXTURE_*`, used unless a fixed [WritingFeel] is chosen. */
    fun begin(tool: Int) {
        val binder = pen ?: return
        val type = WritingFeel.pick(context.getSharedPreferences("preferences", 0), tool).texture ?: tool
        if (type != texture) { texture = type; oneWay(binder, SET_TEXTURE) { writeInt(type) } }
        if (vibrating) return
        vibrating = true
        oneWay(binder, START_FEEDBACK) {}
    }

    /** One function pulse in the pen, e.g. to confirm a double tap; false if the pen service is not connected. */
    fun pulse(): Boolean {
        val binder = pen?.takeIf { it.isBinderAlive } ?: return false
        oneWay(binder, PULSE) { writeInt(0) }
        return true
    }

    /** Pen lifted, cancelled, or the tool stopped writing. Safe to call repeatedly. */
    fun end() {
        if (!vibrating) return
        vibrating = false
        pen?.let { oneWay(it, STOP_FEEDBACK) {} }
    }

    private fun next() {
        release()
        val name = identities.removeFirstOrNull()
        if (name == null || !running) { if (running) status = FeedbackStatus.Refused; return }
        identity = name
        status = FeedbackStatus.Connecting
        bound = bindAs(name, OCS_ACTION, OCS_SERVICE) || bindAs(name, OCS_FALLBACK_ACTION, OCS_FALLBACK_SERVICE)
        Log.i(TAG, "$name bind ${if (bound) "accepted" else "refused"}")
        if (!bound) { status = FeedbackStatus.Unavailable; identities.clear() }
        else handler.postDelayed(authTimeout, AUTH_TIMEOUT_MS)
    }

    private fun bindAs(name: String, action: String, component: ComponentName): Boolean = runCatching {
        val intent = Intent(action).apply {
            this.component = component
            type = "internal_service_$CAPABILITY"
            // Extras do not distinguish Android bindings; a fresh category makes OCS run onBind again.
            addCategory("${context.packageName}.penFeedback.${UUID.randomUUID()}")
            putExtra("bind_type", 1)
            putExtra("internal_third_packagename", name)
            putExtra("internal_capability_client", CAPABILITY)
            putExtra("internal_third_pid", Process.myPid())
            putExtra("internal_active_write_permits", true)
            putExtra("internal_base_version", SDK_VERSION)
            putExtra("internal_wait_service", true)
            putExtra("internal_bundle", Bundle().apply { putBinder("internal_binder", authListener) })
        }
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }.getOrDefault(false)

    private fun release() {
        handler.removeCallbacks(authTimeout)
        pen?.let { binder ->
            runCatching { binder.unlinkToDeath(death, 0) }
            oneWay(binder, UNSET_CALLBACK) { writeStrongBinder(penCallback) }
        }
        pen = null; vibrating = false; texture = -1
        if (bound) { runCatching { context.unbindService(connection) }; bound = false }
    }

    private fun accepted(binder: IBinder) {
        handler.removeCallbacks(authTimeout)
        if (!running) return
        runCatching { binder.linkToDeath(death, 0) }
        pen = binder
        status = FeedbackStatus.Connected
        Log.i(TAG, "connected as $identity")
        oneWay(binder, SET_CALLBACK) { writeStrongBinder(penCallback) }
    }

    private fun refused(reason: String) {
        handler.removeCallbacks(authTimeout)
        Log.i(TAG, "$identity refused: $reason")
        if (running) next()
    }

    private inline fun oneWay(binder: IBinder, code: Int, args: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(PEN_INTERFACE)
            data.args()
            binder.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "pen call $code failed", e)
        } finally {
            data.recycle()
        }
    }

    private val connection = object : ServiceConnection {
        // Authentication comes through the listener in the bind intent, not this broker binder.
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val descriptor = runCatching { service?.interfaceDescriptor }.getOrNull()
            Log.i(TAG, "connected to ${name?.flattenToShortString()}: $descriptor")
        }
        override fun onServiceDisconnected(name: ComponentName?) { handler.post { pen = null; vibrating = false } }
    }

    private val death = IBinder.DeathRecipient {
        handler.post {
            pen = null; vibrating = false
            if (running) handler.postDelayed({ if (running && pen == null) { identities = ArrayDeque(listOfNotNull(identity)); next() } }, RECONNECT_MS)
        }
    }

    private val authTimeout = Runnable { refused("no answer") }

    private val authListener = object : Binder() {
        init { attachInterface(null, AUTH_LISTENER) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(AUTH_LISTENER); return true }
            if (code != 1 && code != 2) return super.onTransact(code, data, reply, flags)
            val outcome = runCatching {
                data.enforceInterface(AUTH_LISTENER)
                if (code == 2) return@runCatching null to "error ${data.readInt()}"
                if (data.readInt() == 0) return@runCatching null to "empty answer"
                readCapability(data)
            }.getOrElse { null to "unreadable answer: ${it.message}" }
            reply?.writeNoException()
            handler.post { outcome.first?.let(::accepted) ?: refused(outcome.second) }
            return true
        }
    }

    /** `CapabilityInfo`: feature list, version, `AuthResult` (by class name, or null), pen binder. */
    @Suppress("DEPRECATION")
    private fun readCapability(data: Parcel): Pair<IBinder?, String> {
        data.readArrayList(Feature::class.java.classLoader)
        val version = data.readInt()
        var code = "none"
        if (data.readString() != null) {
            data.readString() // package
            code = "${data.readInt()}/${data.readInt()}/${data.readInt()}"
            data.createByteArray()
        }
        val binder = data.readStrongBinder()
        return binder to "version $version, auth $code"
    }

    private val penCallback = object : Binder() {
        init { attachInterface(null, PEN_CALLBACK) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(PEN_CALLBACK); return true }
            // Connection, switch and demo-mode notices; nothing in Folio depends on them.
            reply?.writeNoException()
            return true
        }
    }

    companion object {
        private const val TAG = "PenWritingFeedback"
        private const val CAPABILITY = "IPE_PENCIL_CLIENT"
        private const val SDK_VERSION = "1.0.11"
        private const val AUTH_LISTENER = "com.coloros.ocs.base.IAuthenticationListener"
        private const val PEN_INTERFACE = "com.oplus.ipemanager.sdk.ISdkAidlInterface"
        private const val PEN_CALLBACK = "com.oplus.ipemanager.sdk.ISDKAidlCallback"
        private const val OCS_ACTION = "com.coloros.opencapabilityservice"
        private val OCS_SERVICE = ComponentName("com.coloros.ocs.opencapabilityservice",
            "com.coloros.ocs.opencapabilityservice.service.ColorOcsService")
        private const val OCS_FALLBACK_ACTION = "com.oplus.ocs.openauthenticate"
        private val OCS_FALLBACK_SERVICE = ComponentName("com.oplus.ocs", "com.oplus.ocs.service.OpenAuthenticateService")

        // ISdkAidlInterface transactions.
        private const val SET_CALLBACK = 1
        private const val SET_TEXTURE = 4
        private const val PULSE = 5
        private const val UNSET_CALLBACK = 10
        private const val START_FEEDBACK = 11
        private const val STOP_FEEDBACK = 12

        const val TEXTURE_PENCIL = 0
        const val TEXTURE_ERASER = 1
        const val TEXTURE_BALLPEN = 2
        const val TEXTURE_PEN = 3
        const val TEXTURE_MARKER = 4

        /** OCS reads the stated app's installed licence; the pen manager may impose further checks. */
        private val LICENSED_APPS = listOf(
            "net.huanci.hsjpro", // HiPaint; confirmed by ReveriePaint
            "com.onyx.galaxy.global.note", // StarNote (global); ships an OCS AUTH_CODE, untried as a stand-in
        )

        private const val AUTH_TIMEOUT_MS = 5_000L
        private const val RECONNECT_MS = 3_000L

        fun isSupported(): Boolean {
            val maker = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
            return listOf("oneplus", "oppo", "oplus", "realme").any { it in maker }
        }

        /** Texture for a tool, or null when the tool does not write (lasso, hand, text, laser…). */
        fun textureFor(tool: Tool, eraserTip: Boolean): Int? = when {
            eraserTip || tool == Tool.ERASER -> TEXTURE_ERASER
            tool == Tool.HIGHLIGHTER -> TEXTURE_MARKER
            tool == Tool.PEN || tool in ShapePickerTools -> TEXTURE_BALLPEN
            else -> null
        }
    }
}

/** What Settings shows about the pen service connection. */
sealed interface FeedbackStatus {
    data object Idle : FeedbackStatus
    data object Connecting : FeedbackStatus
    data object Connected : FeedbackStatus
    data object Refused : FeedbackStatus
    data object Unavailable : FeedbackStatus
}

/** Each tool group's texture: its natural feel, or its own fixed choice. */
enum class WritingFeel(val label: String, val description: String, val texture: Int?) {
    AUTO("Match the tool", "The tool's natural feel: ballpoint for pen and shapes, marker for the highlighter, rubber for the eraser.", null),
    PENCIL("Pencil", "A grainy, papery drag.", PenWritingFeedback.TEXTURE_PENCIL),
    BALLPEN("Ballpoint", "A light, smooth glide.", PenWritingFeedback.TEXTURE_BALLPEN),
    PEN("Pen", "A smooth, even stroke.", PenWritingFeedback.TEXTURE_PEN),
    MARKER("Marker", "A soft, broad, damped stroke.", PenWritingFeedback.TEXTURE_MARKER);

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.name == raw } ?: AUTO

        /** Preference holding the feel for the tool behind a `TEXTURE_*`: pen and shapes, highlighter, eraser. */
        fun key(tool: Int) = when (tool) {
            PenWritingFeedback.TEXTURE_ERASER -> AppPrefs.WRITING_FEEL_ERASER
            PenWritingFeedback.TEXTURE_MARKER -> AppPrefs.WRITING_FEEL_HIGHLIGHTER
            else -> AppPrefs.WRITING_FEEL
        }

        /** An unset tool uses its natural texture, independently of the pen's saved choice. */
        fun pick(p: android.content.SharedPreferences, tool: Int) =
            of(p.getString(key(tool), null))
    }
}
