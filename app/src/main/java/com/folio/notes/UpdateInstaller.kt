package com.folio.notes

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/**
 * Installs a downloaded, checksum-verified APK through a [PackageInstaller] session. On Android 12+
 * the session asks for no user action, which is allowed for an app updating itself; the system may
 * still ask, and [UpdateInstallReceiver] hands that confirmation to the user.
 */
internal fun commitUpdateSession(context: Context, apk: File) {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
    }
    val sessionId = installer.createSession(params)
    try {
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            // Mutable so the system can attach the status extras; the intent is explicit and unexported.
            val callback = PendingIntent.getBroadcast(context, sessionId,
                Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(callback.intentSender)
        }
    } catch (error: Throwable) {
        runCatching { installer.abandonSession(sessionId) }
        throw error
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val updates = (context.applicationContext as FolioApplication).updates
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // The process is replaced on success; nothing to report.
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                runCatching { context.startActivity(confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { updates.message("Android could not open the install confirmation.") }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> updates.message("Update cancelled.")
            else -> updates.message(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ?.let { "Update failed: $it" } ?: "The update could not be installed.")
        }
    }
}
