package com.folio.notes.vcaa

import com.folio.notes.exam.ExamType
import org.junit.Assert.*
import org.junit.Test

class VcaaFilterShortcutsTest {
    private val methods = VcaaFilterShortcut("Methods – paper \"2\"", VcaaFilterSet("Mathematical Methods", 2024, ExamType.EXAM_2))
    private val recent = VcaaFilterShortcut("2025", VcaaFilterSet(null, 2025, null))

    @Test fun roundTripsFullAndPartialShortcutsInOrder() {
        val saved = listOf(methods, recent, VcaaFilterShortcut("Biology", VcaaFilterSet("Biology", null, null)),
            VcaaFilterShortcut("Paper 1", VcaaFilterSet(null, null, ExamType.EXAM_1)))
        assertEquals(saved, VcaaFilterShortcuts.decode(VcaaFilterShortcuts.encode(saved)))
        assertEquals(listOf(recent), VcaaFilterShortcuts.decode(VcaaFilterShortcuts.encode(listOf(recent))))
    }

    @Test fun defaultsToNoShortcutsWhenNothingIsStoredOrTheJsonIsCorrupt() {
        assertEquals(emptyList<VcaaFilterShortcut>(), VcaaFilterShortcuts.decode(null))
        assertEquals(emptyList<VcaaFilterShortcut>(), VcaaFilterShortcuts.decode("not JSON"))
        assertEquals(emptyList<VcaaFilterShortcut>(), VcaaFilterShortcuts.decode("[]"))
    }

    @Test fun skipsBadOrDuplicateRowsWithoutWideningTheirFilters() {
        val raw = """[
            {"name":"Typo","type":"FUTURE_PAPER","year":2024},
            {"name":"Not a paper","type":"NOTES"},
            {"name":"Bad year","year":"2025","study":"Chemistry"},
            {"name":" ","year":2025},
            {"name":"No filters"},
            {"name":"2025","year":2025,"study":null},
            {"name":" 2025 ","year":2024}
        ]"""
        assertEquals(listOf(recent), VcaaFilterShortcuts.decode(raw))
    }

    @Test fun rejectsBlankLongAndCaseInsensitiveDuplicateNames() {
        val saved = listOf(methods, recent)
        assertNotNull(VcaaFilterShortcuts.nameError("   ", saved))
        assertNotNull(VcaaFilterShortcuts.nameError("a".repeat(VcaaFilterShortcuts.MAX_NAME_LENGTH + 1), saved))
        assertNotNull(VcaaFilterShortcuts.nameError("  METHODS – PAPER \"2\"  ", saved))
        assertNull(VcaaFilterShortcuts.nameError("  New  ", saved))
    }

    @Test fun restoredFiltersUseTheSameCatalogueSearch() {
        fun paper(study: String, year: Int, type: ExamType) =
            VcaaExam(study, "Official paper", "$study/$year/${type.name}.pdf", year, "VCAA", type, emptyList())
        val matching = paper("Mathematical Methods", 2024, ExamType.EXAM_2)
        val index = VcaaIndex(listOf(matching, paper("Mathematical Methods", 2024, ExamType.EXAM_1),
            paper("Mathematical Methods", 2025, ExamType.EXAM_2), paper("Specialist Mathematics", 2024, ExamType.EXAM_2)))
        val filters = VcaaFilterShortcuts.decode(VcaaFilterShortcuts.encode(listOf(methods))).single().filters
        assertEquals(listOf(matching), index.search("", filters.study, filters.year, filters.type))
    }
}
