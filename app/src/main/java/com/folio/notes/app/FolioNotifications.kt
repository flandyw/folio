package com.folio.notes.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.folio.notes.R

/** Folio's only notifications: background work that finished while the app was out of sight. */
internal object FolioNotifications {
    private const val CLASSIFICATION = "classification"

    fun classificationReady(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CLASSIFICATION, "Document details", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "When ChatGPT has finished checking an imported PDF" })
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val open = PendingIntent.getActivity(context, id, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(CLASSIFICATION, id, Notification.Builder(context, CLASSIFICATION)
            .setSmallIcon(R.drawable.ic_folio_mono).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
    }
}
