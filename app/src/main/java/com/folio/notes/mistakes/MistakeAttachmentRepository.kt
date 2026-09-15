package com.folio.notes.mistakes

import android.content.Context
import android.util.AtomicFile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class MistakeAttachmentRepository(context: Context, private val client: SupabaseClient) {
    private val root = File(context.filesDir, "examtrack-images")
    suspend fun get(user: String, attachment: MistakeAttachment): File = withContext(Dispatchers.IO) {
        require(attachment.type in setOf("image/jpeg", "image/png", "image/webp", "image/gif"))
        require(attachment.size in 0..5L * 1024 * 1024)
        require(attachment.storagePath.startsWith("$user/") && !attachment.storagePath.split('/').contains(".."))
        require(user.matches(Regex("[a-zA-Z0-9-]+")))
        val hash = MessageDigest.getInstance("SHA-256").digest(attachment.storagePath.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = AtomicFile(File(root, "$user/$hash"))
        if (file.baseFile.exists()) return@withContext file.baseFile
        check(client.auth.currentUserOrNull()?.id == user)
        val bytes = client.storage.from("mistake-attachments").downloadAuthenticated(attachment.storagePath)
        require(bytes.size <= 5 * 1024 * 1024)
        check(client.auth.currentUserOrNull()?.id == user)
        file.baseFile.parentFile!!.mkdirs()
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
        file.baseFile
    }
}
