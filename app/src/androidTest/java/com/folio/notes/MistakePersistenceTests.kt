package com.folio.notes

import androidx.test.platform.app.InstrumentationRegistry
import com.folio.notes.mistakes.*
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Device checks for the actual Android Keystore, AtomicFile and notebook storage. */
class MistakePersistenceTests {
    @Test fun encryptedSessionSurvivesRepositoryRecreationAndSignOutRemovesIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedExamTrackSession(context)
        // Run only against an isolated instrumentation install, never a student's signed-in app.
        assertNull("Use a fresh test install without an ExamTrack session", store.loadSession())
        val session = UserSession("instrumentation-access", "instrumentation-refresh", expiresIn = 3600,
            tokenType = "bearer", user = UserInfo(aud = "authenticated", id = "test-user"))
        try {
            store.saveSession(session)
            val raw = File(context.noBackupFilesDir, "examtrack-session").readBytes().decodeToString()
            assertFalse(raw.contains(session.accessToken)); assertFalse(raw.contains(session.refreshToken))
            assertEquals(session, EncryptedExamTrackSession(context).loadSession())
        } finally { store.deleteSession() }
        assertNull(EncryptedExamTrackSession(context).loadSession())
    }
    @Test fun actualSplitStorageRetainsTwoIndependentHandwrittenAttempts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = NoteRepository(context)
        val id = UUID.randomUUID().toString()
        val p1 = NotePage(strokes = listOf(Stroke(Tool.PEN, 0xff000000.toInt(), 3f, listOf(InkPoint(10f, 10f), InkPoint(20f, 20f)))))
        val p2 = NotePage(strokes = listOf(Stroke(Tool.PEN, 0xff000000.toInt(), 3f, listOf(InkPoint(30f, 30f), InkPoint(40f, 40f)))))
        val attempts = listOf(p1, p2).map { LocalMistakeReviewAttempt("test-user", "mistake", UUID.randomUUID().toString(), id, it.id) }
        val note = Notebook(id = id, title = "Test mistake practice", pages = listOf(p1, p2), mistakePractice = true, mistakeReviews = attempts)
        try {
            repository.saveAll(note)
            val index = repository.load().first.single { it.id == id }
            val loaded = repository.loadPages(index)
            assertEquals(attempts, loaded.mistakeReviews)
            assertEquals(p1.strokes, loaded.pages[0].strokes)
            assertEquals(p2.strokes, loaded.pages[1].strokes)
            assertNotEquals(loaded.pages[0].id, loaded.pages[1].id)
        } finally { File(context.filesDir, "notebooks/$id").deleteRecursively() }
    }
}
