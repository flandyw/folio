package com.folio.notes.mistakes.sync

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
        val key = f.baseFile.path
        synchronized(memo) { memo[key] }?.let { return@withContext it }
        val cache = if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) MistakeCache()
        else f.openRead().bufferedReader().use { MistakeCacheCodec.decode(it.readText()) }
        synchronized(memo) { memo[key] = cache }
        cache
    }
    override suspend fun save(userId: String, cache: MistakeCache) = withContext(Dispatchers.IO) {
        val f = file(userId)
        f.baseFile.parentFile!!.mkdirs()
        val stream = f.startWrite()
        try {
            stream.bufferedWriter().apply { write(MistakeCacheCodec.encode(cache)); flush() }
            f.finishWrite(stream)
            synchronized(memo) { memo[f.baseFile.path] = cache }
        }
        catch (e: Exception) { f.failWrite(stream); synchronized(memo) { memo.remove(f.baseFile.path) }; throw e }
    }

    private companion object {
        /**
         * The last cache read or written per file, so each review action stops re-reading and
         * re-parsing the whole JSON. Every write goes through [save], so the copy never goes stale.
         */
        val memo = HashMap<String, MistakeCache>()
    }
}
