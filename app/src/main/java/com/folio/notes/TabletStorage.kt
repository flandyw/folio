package com.folio.notes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

internal data class TabletVolume(val uri: Uri, val name: String)

/** Shared storage only: Android's all-files grant never includes other apps' private data. */
internal class TabletStorage(private val context: Context) {
    fun granted(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else legacyPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun settingsIntent() = if (Build.VERSION.SDK_INT >= 30)
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun volumes(): List<TabletVolume> {
        val primary = Environment.getExternalStorageDirectory()
        val volumes = if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { volume ->
                volume.directory?.takeIf { volume.state == Environment.MEDIA_MOUNTED || volume.state == Environment.MEDIA_MOUNTED_READ_ONLY }
                    ?.let { TabletVolume(Uri.fromFile(it), if (volume.isPrimary) "Internal storage" else volume.getDescription(context)) }
            }
        } else context.getExternalFilesDirs(null).filterNotNull().map { directory ->
            val root = File(directory.absolutePath.substringBefore("/Android/data/"))
            TabletVolume(Uri.fromFile(root), if (root == primary) "Internal storage" else "SD card · ${root.name}")
        }
        return (listOf(TabletVolume(Uri.fromFile(primary), "Internal storage")) + volumes).distinctBy { it.uri }
    }

    fun file(uri: Uri): File {
        check(granted()) { "Enable all files access to browse tablet storage" }
        require(uri.scheme == "file") { "Invalid tablet location" }
        val file = File(requireNotNull(uri.path)).canonicalFile
        check(SharedStorageRules.contains(volumes().map { File(requireNotNull(it.uri.path)).canonicalFile }, file)) {
            "This path is outside tablet shared storage"
        }
        return file
    }

    companion object {
        val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}
