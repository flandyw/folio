package com.folio.notes.mistakes

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileMistakeCacheStore(context: Context) : MistakeCacheStore {
    private val root = File(context.filesDir, "examtrack")
    private fun file(user: String): AtomicFile {
        require(user.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(root, "$user/cache.json"))
    }
    override suspend fun load(userId: String): MistakeCache = withContext(Dispatchers.IO) {
        val f = file(userId)
        if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) MistakeCache()
        else f.openRead().bufferedReader().use { MistakeCacheCodec.decode(it.readText()) }
    }
    override suspend fun save(userId: String, cache: MistakeCache) = withContext(Dispatchers.IO) {
        val f = file(userId)
        f.baseFile.parentFile!!.mkdirs()
        val stream = f.startWrite()
        try { stream.write(MistakeCacheCodec.encode(cache).toByteArray()); f.finishWrite(stream) }
        catch (e: Exception) { f.failWrite(stream); throw e }
    }
}
