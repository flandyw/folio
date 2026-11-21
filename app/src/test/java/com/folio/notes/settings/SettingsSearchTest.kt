package com.folio.notes.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsSearchTest {
    @Test fun unicodeSpacesSeparateWordsAcrossTitleKeywordsAndPage() {
        listOf("sticky text", "sticky text", "STICKY text", " sticky  \ttext ").forEach {
            assertTrue(it, SettingsIndex.search(it).any { hit -> hit.title == "Sticky note text size" })
        }
        // "font" is only a keyword and "editor" only the page name.
        assertTrue(SettingsIndex.search("sticky font editor").any { it.title == "Sticky note text size" })
        assertTrue(SettingsIndex.search("sticky chemistry").isEmpty())
        assertTrue(SettingsIndex.search("   ").isEmpty())
    }
}
