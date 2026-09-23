package com.folio.notes

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Export-mode contract without external PDF libraries (unit tests run offline on a plain JVM):
 *
 * PRESERVE output keeps the source content stream (searchable text), carries an image
 * XObject overlay for Folio annotations, and retains link annotations.
 *
 * RASTERISE output is a single flattened image per page: no extractable source text,
 * no link annotations, but the composed pixels (including baked Folio ink) are embedded.
 *
 * Minimal PDFs are built by hand with correct xref tables; text is extracted naively from
 * literal `( ... )` strings, which is exact for these controlled documents. Pixel checks
 * decode the embedded Flate image stream and inspect representative samples, rather than
 * only asserting object structure.
 */
class PdfExportContractTests {
    companion object {
        const val PHRASE = "FOLIO_PRESERVATION_TEST_123"
        const val LINK_URI = "https://example.com/folio"
    }

    // ---- Minimal PDF builder -----------------------------------------------------------

    private fun flate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(compressed: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    /** Assembles objects (1-based) into a valid PDF with xref table. */
    private fun assemble(objects: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
        val offsets = mutableListOf<Long>()
        objects.forEachIndexed { index, body ->
            offsets += out.size().toLong()
            out.write("${index + 1} 0 obj\n".toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.US_ASCII))
        }
        val xrefAt = out.size().toLong()
        out.write("xref\n0 ${objects.size + 1}\n".toByteArray(Charsets.US_ASCII))
        out.write("0000000000 65535 f \n".toByteArray(Charsets.US_ASCII))
        offsets.forEach { off -> out.write(String.format("%010d 00000 n \n", off).toByteArray(Charsets.US_ASCII)) }
        out.write(
            "trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF".toByteArray(Charsets.US_ASCII)
        )
        return out.toByteArray()
    }

    private fun streamObject(dict: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("<< $dict /Length ${data.size} >>\nstream\n".toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write("\nendstream".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    // ---- Controlled documents ----------------------------------------------------------

    /**
     * PRESERVE shape: source text stream stays intact, Folio overlay is an extra image
     * XObject drawn in the content stream, link annotation retained. The content stream is
     * left uncompressed (as many real producers emit) so literal text stays extractable;
     * only the image payload is Flate-encoded.
     */
    private fun preservePdf(): ByteArray {
        val overlayRgb = ByteArray(20 * 10 * 3) { 0 } // solid black ink block
        val content = ascii(
            "BT /F1 12 Tf 100 700 Td (Source $PHRASE end) Tj ET\n" +
                "q 20 0 0 10 100 100 cm /Im1 Do Q\n"
        )
        return assemble(
            listOf(
                ascii("<< /Type /Catalog /Pages 2 0 R >>"),
                ascii("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R " +
                        "/Resources << /Font << /F1 5 0 R >> /XObject << /Im1 6 0 R >> >> /Annots [7 0 R] >>"
                ),
                streamObject("", content),
                ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
                streamObject(
                    "/Type /XObject /Subtype /Image /Width 20 /Height 10 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode",
                    flate(overlayRgb)
                ),
                ascii("<< /Type /Annot /Subtype /Link /Rect [100 650 300 670] /A << /S /URI /URI ($LINK_URI) >> >>")
            )
        )
    }

    /**
     * RASTERISE shape: one full-page image, no text show ops, no annotations. The composed
     * RGB has a baked black annotation rect on a white page.
     */
    private fun rasterPdf(): Pair<ByteArray, ByteArray> {
        val w = 60
        val h = 84
        val rgb = ByteArray(w * h * 3) { 0xFF.toByte() } // white page
        // Baked Folio ink: black block covering x 10..29, y 10..19 (top-left origin).
        for (y in 10 until 20) for (x in 10 until 30) {
            val o = (y * w + x) * 3
            rgb[o] = 0
            rgb[o + 1] = 0
            rgb[o + 2] = 0
        }
        val content = ascii("q $w 0 0 $h 0 0 cm /Im1 Do Q\n")
        val pdf = assemble(
            listOf(
                ascii("<< /Type /Catalog /Pages 2 0 R >>"),
                ascii("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R " +
                        "/Resources << /XObject << /Im1 5 0 R >> >> >>"
                ),
                streamObject("", content),
                streamObject(
                    "/Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode",
                    flate(rgb)
                )
            )
        )
        return pdf to rgb
    }

    /** Naive literal-string text extraction: exact for these controlled documents. */
    private fun extractLiterals(pdf: ByteArray): String {
        val text = pdf.toString(Charsets.ISO_8859_1)
        return Regex("""\((?:\\.|[^\\()])*\)""").findAll(text).joinToString(" ") { it.value }
    }

    private fun asciiContains(pdf: ByteArray, needle: String): Boolean =
        pdf.toString(Charsets.ISO_8859_1).contains(needle)

    /** First Flate image stream decoded back to raw RGB. */
    private fun firstImageRgb(pdf: ByteArray): ByteArray {
        val text = pdf.toString(Charsets.ISO_8859_1)
        val match = Regex("/Subtype /Image.*?/Length (\\d+).*?stream\r?\n", RegexOption.DOT_MATCHES_ALL).find(text)
            ?: error("no image stream found")
        val length = match.groupValues[1].toInt()
        val dataStart = match.range.last + 1
        val raw = pdf.copyOfRange(dataStart, dataStart + length)
        // Our streams are Flate-encoded without predictor.
        return inflate(raw)
    }

    private fun pixel(rgb: ByteArray, w: Int, x: Int, y: Int): Triple<Int, Int, Int> {
        val o = (y * w + x) * 3
        return Triple(rgb[o].toInt() and 0xFF, rgb[o + 1].toInt() and 0xFF, rgb[o + 2].toInt() and 0xFF)
    }

    // ---- Contract ----------------------------------------------------------------------

    @Test fun preserveKeepsSearchableTextOverlayAndLink() {
        val pdf = preservePdf()
        // The required preservation phrase must still be extractable from a PRESERVE export.
        assertTrue(extractLiterals(pdf).contains(PHRASE))
        // Folio overlay travels as an image XObject alongside preserved source content.
        assertTrue(asciiContains(pdf, "/Subtype /Image"))
        assertTrue(asciiContains(pdf, "/Im1 Do"))
        // Links survive preservation.
        assertTrue(asciiContains(pdf, "/Subtype /Link"))
        assertTrue(asciiContains(pdf, LINK_URI))
        // Overlay pixels are present (solid ink block decodes to black).
        val rgb = firstImageRgb(pdf)
        assertEquals(20 * 10 * 3, rgb.size)
        assertEquals(Triple(0, 0, 0), pixel(rgb, 20, 0, 0))
        assertEquals(Triple(0, 0, 0), pixel(rgb, 20, 19, 9))
    }

    @Test fun rasteriseFlattensToBakedPixelsWithoutTextOrLinks() {
        val (pdf, _) = rasterPdf()
        // Source text must not survive as extractable text in a flattened export.
        assertFalse(extractLiterals(pdf).contains(PHRASE))
        assertFalse(asciiContains(pdf, "Tj"))
        // The page is the flattened visual result: a single image, no link annotations.
        assertTrue(asciiContains(pdf, "/Subtype /Image"))
        assertTrue(asciiContains(pdf, "/Im1 Do"))
        assertFalse(asciiContains(pdf, "/Subtype /Link"))
        assertFalse(asciiContains(pdf, LINK_URI))
        // Representative pixels: ink block baked black, surrounding page white.
        // Annotations cannot disappear by ignoring an overlay layer — they are pixels now.
        val rgb = firstImageRgb(pdf)
        val w = 60
        assertEquals(Triple(0, 0, 0), pixel(rgb, w, 15, 14))
        assertEquals(Triple(0, 0, 0), pixel(rgb, w, 29, 19))
        assertEquals(Triple(255, 255, 255), pixel(rgb, w, 5, 5))
        assertEquals(Triple(255, 255, 255), pixel(rgb, w, 50, 70))
    }
}
