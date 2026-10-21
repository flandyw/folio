package com.folio.notes.vcaa

import com.folio.notes.exam.ExamType
import com.folio.notes.model.Notebook
import org.junit.Assert.*
import org.junit.Test

class VcaaIndexTest {
    private fun paper(subject: String, year: Int?, provider: String = "VCAA", reports: Boolean = false) =
        VcaaExam(subject, "$subject exam", "https://www.vcaa.vic.edu.au/$subject-$year-$provider.pdf",
            year, provider, ExamType.EXAM, if (reports) listOf("Report" to "https://www.vcaa.vic.edu.au/report.pdf") else emptyList())

    private val biology = paper("Biology", 2024, reports = true)
    private val chemistry = paper("Chemistry", 2023, "VCAA NHT", reports = true)
    private val older = paper("Biology", 2020)
    private val undated = paper("Biology", null)
    private val index = VcaaIndex(listOf(older, undated, chemistry, biology))

    @Test fun combinesFiltersAndHandlesWhitespace() {
        assertEquals(listOf(chemistry), index.search("CHEMISTRY\n\t2023", null, null, null,
            provider = "VCAA NHT", reportsOnly = true))
        assertTrue(index.search("", "Biology", 2024, null, provider = "VCAA NHT").isEmpty())
        assertEquals(listOf(biology), index.search("", "Biology", null, null, reportsOnly = true))
    }

    @Test fun sortsChronologicallyWithUndatedPapersLast() {
        assertEquals(listOf(biology, chemistry, older, undated), index.search("", null, null, null))
        assertEquals(listOf(older, chemistry, biology, undated), index.search("", null, null, null, sort = VcaaSort.OLDEST))
        assertEquals(listOf(biology, older, undated, chemistry), index.search("", null, null, null, sort = VcaaSort.SUBJECT))
    }

    @Test fun ownershipUsesExamMetadataInsteadOfNotebookTitle() {
        val notebook = Notebook(title = "My renamed paper", exam = biology.tags, pages = emptyList())
        val owned = index.owned(listOf(notebook))
        assertEquals(listOf(biology), index.search("", null, null, null,
            availability = VcaaAvailability.IMPORTED, owned = owned))
        assertEquals(listOf(chemistry, older, undated), index.search("", null, null, null,
            availability = VcaaAvailability.NOT_IMPORTED, owned = owned))
        assertTrue(index.search("", null, null, null, availability = VcaaAvailability.IMPORTED).isEmpty())
    }

    @Test fun retainsEveryDistinctMatchingReport() {
        val parsed = VcaaLibrary.parse("""{"studies":[{"studyName":"Biology","resources":[
            {"kind":"exam","label":"2024 exam","year":2024,"url":"exam.pdf"},
            {"kind":"report","label":"Report","year":2024,"url":"report.pdf"},
            {"kind":"report","label":"Assessment guide","year":2024,"url":"guide.pdf"},
            {"kind":"report","label":"Supplement","year":2024,"url":"extra.pdf"},
            {"kind":"report","label":"Duplicate","year":2024,"url":"report.pdf"},
            {"kind":"report","label":"Old report","year":2023,"url":"old.pdf"}
        ]}]}""")
        assertEquals(listOf("guide.pdf", "report.pdf", "extra.pdf"), parsed.single().reports.map { it.second })
    }
}
