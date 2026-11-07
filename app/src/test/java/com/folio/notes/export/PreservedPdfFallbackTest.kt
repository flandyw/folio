package com.folio.notes.export

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class PreservedPdfFallbackTest {
    @Test fun failedOverlayReplacesOnlyThePartlyImportedPage() = runBlocking {
        PDDocument().use { source ->
            source.addPage(PDPage(PDRectangle(300f, 400f)))
            PDDocument().use { out ->
                val previous = PDPage(PDRectangle(100f, 200f))
                out.addPage(previous)
                PreservedPdfExporter.writePageWithFallback(out, preserve = {
                    out.importPage(source.getPage(0))
                    throw IOException("Overlay failed after import")
                }, flatten = {
                    assertEquals(1, out.numberOfPages)
                    assertSame(previous.cosObject, out.getPage(0).cosObject)
                    out.addPage(PDPage(PDRectangle(500f, 600f)))
                })
                val bytes = ByteArrayOutputStream().also(out::save).toByteArray()
                PDDocument.load(bytes).use { reopened ->
                    assertEquals(2, reopened.numberOfPages)
                    assertEquals(100f, reopened.getPage(0).mediaBox.width, 0f)
                    assertEquals(500f, reopened.getPage(1).mediaBox.width, 0f)
                }
            }
        }
    }

    @Test fun successfulPreservationDoesNotRunFallback() = runBlocking {
        PDDocument().use { source ->
            source.addPage(PDPage())
            PDDocument().use { out ->
                PreservedPdfExporter.writePageWithFallback(out,
                    preserve = { out.importPage(source.getPage(0)); Unit },
                    flatten = { fail("A preserved page needs no fallback") })
                assertEquals(1, out.numberOfPages)
            }
        }
    }

    @Test fun failureBeforeImportKeepsEarlierPages() = runBlocking {
        PDDocument().use { out ->
            out.addPage(PDPage())
            PreservedPdfExporter.writePageWithFallback(out,
                preserve = { throw IOException("Cannot read source page") },
                flatten = { out.addPage(PDPage()) })
            assertEquals(2, out.numberOfPages)
        }
    }

    @Test fun cancellationDiscardsPartialImportWithoutRunningFallback() = runBlocking {
        PDDocument().use { source ->
            source.addPage(PDPage())
            PDDocument().use { out ->
                out.addPage(PDPage())
                val export = launch {
                    PreservedPdfExporter.writePageWithFallback(out, preserve = {
                        out.importPage(source.getPage(0))
                        awaitCancellation()
                    }, flatten = { fail("Cancellation must stop the export") })
                }
                yield()
                assertEquals(2, out.numberOfPages)
                export.cancelAndJoin()
                assertEquals(1, out.numberOfPages)
            }
        }
    }

    @Test fun fallbackFailurePropagatesInsteadOfHidingMissingPages() = runBlocking {
        PDDocument().use { out ->
            val failure = IOException("Source cannot be rendered")
            try {
                PreservedPdfExporter.writePageWithFallback(out,
                    preserve = { throw IOException("Cannot preserve source") },
                    flatten = { throw failure })
                fail("An unreadable page must fail export")
            } catch (e: IOException) {
                assertSame(failure, e)
            }
            assertEquals(0, out.numberOfPages)
        }
    }
}
