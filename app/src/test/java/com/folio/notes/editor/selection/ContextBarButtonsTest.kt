package com.folio.notes.editor.selection

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextBarButtonsTest {
    private val actions = listOf("copy", "duplicate", "style", "DELETE")
    private fun fit(slots: Int, hasList: Boolean = true) = contextBarButtons(actions, slots, hasList) { it == "DELETE" }

    @Test fun wideBarShowsEverythingBesideMore() = assertEquals(actions, fit(5))
    @Test fun barWithoutListNeedsNoMoreButton() = assertEquals(actions, fit(4, hasList = false))
    @Test fun narrowBarDropsFromTheEndButKeepsDeleteInPlace() = assertEquals(listOf("copy", "duplicate", "DELETE"), fit(4))
    @Test fun deleteIsTheLastToLeave() = assertEquals(listOf("DELETE"), fit(2))
    @Test fun onlyMoreFitsInATinyBar() = assertEquals(emptyList<String>(), fit(1))
    @Test fun nothingFitsWithoutCrashing() = assertEquals(emptyList<String>(), fit(0))
}
