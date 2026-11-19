package com.folio.notes.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfOutlineTest {
    private val entries = listOf(
        PdfOutlineEntry("Chapter one", 0, 0),
        PdfOutlineEntry("Background", 1, 1),
        PdfOutlineEntry("Linear equations", 2, 1),
        PdfOutlineEntry("Worked example", 3, 2),
        PdfOutlineEntry("Chapter two", 4, 0),
        PdfOutlineEntry("More equations", 5, 1)
    )

    @Test fun matchesTitleSubstringsInReadingOrder() {
        assertEquals(listOf(entries[0], entries[2], entries[4], entries[5]), PdfOutline.filter(entries, "equation"))
    }

    @Test fun retainsAllAncestorsButNotSiblings() {
        assertEquals(listOf(entries[0], entries[2], entries[3]), PdfOutline.filter(entries, "example"))
    }

    @Test fun matchesCaseInsensitively() {
        assertEquals(listOf(entries[0], entries[1]), PdfOutline.filter(entries, "BACKground"))
    }

    @Test fun emptyQueryRestoresFullOutline() {
        assertEquals(entries, PdfOutline.filter(entries, ""))
    }

    @Test fun unmatchedQueryReturnsNoResults() {
        assertEquals(emptyList<PdfOutlineEntry>(), PdfOutline.filter(entries, "missing"))
    }

    @Test fun emptyOutlineReturnsNoResults() {
        assertEquals(emptyList<PdfOutlineEntry>(), PdfOutline.filter(emptyList(), "example"))
    }

    @Test fun matchingParentDoesNotIncludeUnmatchedDescendants() {
        assertEquals(listOf(entries[4]), PdfOutline.filter(entries, "two"))
    }

    @Test fun skippedDepthsStillRetainContext() {
        val sparse = listOf(entries[0], entries[3], entries[4])
        assertEquals(sparse.take(2), PdfOutline.filter(sparse, "example"))
    }
}
