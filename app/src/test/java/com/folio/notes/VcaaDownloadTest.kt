package com.folio.notes

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class VcaaDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private val paper = "https://www.vcaa.vic.edu.au/paper.pdf"
    private class Response(url: URL, val status: Int = 200, val body: String = "%PDF-1.7\n", val location: String? = null,
        val length: Long = -1) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getContentLengthLong() = length
    }

    @Test fun importsPdfBytesAndDisconnects() = runBlocking {
        val target = temp.newFile()
        val response = Response(URL(paper))
        downloadVcaaPdf(paper, target) { response }
        assertEquals(response.body, target.readText())
        assertTrue(response.disconnected)
        assertFalse(response.instanceFollowRedirects)
    }

    @Test fun followsRelativeRedirectsOnVcaa() = runBlocking {
        val target = temp.newFile()
        val opened = mutableListOf<Response>()
        downloadVcaaPdf(paper, target) { url ->
            Response(url, status = if (opened.isEmpty()) 302 else 200, location = "/new.pdf").also { opened += it }
        }
        assertEquals(listOf(paper, "https://www.vcaa.vic.edu.au/new.pdf"), opened.map { it.url.toString() })
        assertTrue(opened.all { it.disconnected })
        assertTrue(target.readText().startsWith("%PDF-"))
    }

    @Test fun rejectsUntrustedOriginsAndRedirectsBeforeConnecting() {
        for (address in listOf("http://www.vcaa.vic.edu.au/file.pdf", "https://vcaa.vic.edu.au.example.com/file.pdf")) {
            var opened = false
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                downloadVcaaPdf(address, temp.newFile()) { opened = true; Response(it) }
            } }
            assertFalse(opened)
        }
        val target = temp.newFile()
        var calls = 0
        val redirect = Response(URL(paper), 302, location = "https://example.com/file.pdf")
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            downloadVcaaPdf(paper, target) { calls++; redirect }
        } }
        assertEquals(1, calls)
        assertTrue(redirect.disconnected)
        assertFalse(target.exists())
    }

    @Test fun removesFailedOrNonPdfDownloads() {
        for (response in listOf(Response(URL(paper), 404), Response(URL(paper), body = "<html>Unavailable</html>"),
            Response(URL(paper), body = "%PDF"), Response(URL(paper), length = 101L * 1024 * 1024))) {
            val target = temp.newFile()
            assertThrows(IllegalStateException::class.java) { runBlocking { downloadVcaaPdf(paper, target) { response } } }
            assertFalse(target.exists())
            assertTrue(response.disconnected)
        }
    }

    @Test fun boundsRedirectLoops() {
        val target = temp.newFile()
        val opened = mutableListOf<Response>()
        assertThrows(IllegalStateException::class.java) { runBlocking {
            downloadVcaaPdf(paper, target) { Response(it, 302, location = paper).also { response -> opened += response } }
        } }
        assertEquals(6, opened.size)
        assertTrue(opened.all { it.disconnected })
        assertFalse(target.exists())
    }
}
