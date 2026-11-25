package com.folio.notes.library.files

import com.folio.notes.storage.NotebookTags
import org.junit.Assert.*
import org.junit.Test

class NotebookTagDraftTest {
    @Test fun turningOffATagTypedInAnotherCaseRemovesItOnSave() {
        val tags = listOf(listOf("Math"))
        // Typed and committed "math" is the same tag as the existing "Math" chip.
        var added = NotebookTags.normalize(listOf("math"), Int.MAX_VALUE)
        var removed = emptyList<String>()
        assertTrue(tagChipActive("Math", 1, added, removed))
        toggleTagDraft("Math", 1, 1, added, removed).let { (a, r) -> added = a; removed = r }
        assertFalse(tagChipActive("Math", 1, added, removed))
        assertEquals(emptyList<String>(), added)
        assertEquals(emptyList<String>(), NotebookTags.edit(tags[0], added, removed))
        assertTrue(tagDraftChanges(tags, added, removed))
        // And back on again: the tag returns and nothing is left to save.
        toggleTagDraft("Math", 1, 1, added, removed).let { (a, r) -> added = a; removed = r }
        assertTrue(tagChipActive("Math", 1, added, removed))
        assertEquals(emptyList<String>(), removed)
        assertFalse(tagDraftChanges(tags, added, removed))
    }

    @Test fun mixedTagsStayUntouchedUntilTappedAndIgnoreCase() {
        val tags = listOf(listOf("Math"), listOf("English"))
        assertTrue(tagChipMixed("Math", 1, 2, emptyList(), emptyList()))
        assertFalse(tagChipMixed("Math", 1, 2, listOf("math"), emptyList()))
        assertFalse(tagChipMixed("Math", 1, 2, emptyList(), listOf("MATH")))
        // Mixed → on every notebook → off every notebook → on again.
        var draft = toggleTagDraft("Math", 1, 2, emptyList(), emptyList())
        assertEquals(listOf("Math") to emptyList<String>(), draft)
        draft = toggleTagDraft("Math", 1, 2, draft.first, draft.second)
        assertEquals(emptyList<String>() to listOf("Math"), draft)
        assertFalse(tagChipActive("Math", 1, draft.first, draft.second))
        assertEquals(listOf(listOf<String>(), listOf("English")), tags.map { NotebookTags.edit(it, draft.first, draft.second) })
        draft = toggleTagDraft("Math", 1, 2, draft.first, draft.second)
        assertEquals(listOf("Math") to emptyList<String>(), draft)
        // A tag no notebook has yet is only ever added or dropped from the draft.
        assertEquals(listOf("Art") to emptyList<String>(), toggleTagDraft("Art", 0, 2, emptyList(), emptyList()))
        assertEquals(emptyList<String>() to emptyList<String>(), toggleTagDraft("Art", 0, 2, listOf("art"), emptyList()))
    }

    @Test fun onlyRealChangesNeedConfirmingBeforeClosing() {
        val tags = listOf(listOf("Math"), listOf("English"))
        assertFalse(tagDraftChanges(tags, emptyList(), emptyList()))
        // Re-adding what every notebook already has, in any case, saves nothing.
        assertFalse(tagDraftChanges(listOf(listOf("Math")), listOf("math"), emptyList()))
        // Removing a tag no notebook has saves nothing either.
        assertFalse(tagDraftChanges(tags, emptyList(), listOf("Art")))
        // Uncommitted typing counts once it is part of what Save writes.
        assertTrue(tagDraftChanges(tags, NotebookTags.normalize(listOf("Art", "  "), Int.MAX_VALUE), emptyList()))
        assertTrue(tagDraftChanges(tags, emptyList(), listOf("english")))
    }
}
