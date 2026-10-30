package com.folio.notes.planner

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PlannerTest {
    private val reply = "Sure!\n```json\n{\"groups\":[{\"group\":\"Unit 3\",\"items\":[{\"name\":\"Product rule\",\"type\":\"technique\",\"check\":\"Differentiate\"},{\"name\":\"product RULE\"},{\"name\":\"Chain rule\",\"type\":\"bogus\"}]}]}\n```"

    @Test fun importKeepsTypedItemsAndDropsDuplicates() {
        val items = Planner.parseImport(reply)
        assertEquals(listOf("Product rule", "Chain rule"), items.map { it.name })
        assertNull(items[1].type)
    }

    @Test fun reimportKeepsRatingsAndIds() {
        val (first, added) = Planner.merge(Planner.learning(null), "Methods", Planner.parseImport(reply))
        assertEquals(2, added)
        val id = Planner.items(first)[0].getString("id")
        val rated = Planner.setRating(first, id, "green")
        val (again, addedAgain) = Planner.merge(rated, "Methods", Planner.parseImport(reply))
        assertEquals(0, addedAgain)
        assertEquals("green", Planner.items(again).first { it.getString("id") == id }.getString("rating"))
        assertEquals(2, Planner.items(again).size)
    }

    @Test fun clearingARatingReturnsTheItemToUnrated() {
        val first = Planner.merge(Planner.learning(null), "Methods", Planner.parseImport(reply)).first
        val id = Planner.items(first)[0].getString("id")
        val cleared = Planner.items(Planner.clearRating(Planner.setRating(first, id, "red"), id))[0]
        assertFalse(cleared.has("rating") || cleared.has("ratedAt"))
    }

    @Test fun planWeekSkipsItemsThatAlreadyHaveATask() {
        val (l, _) = Planner.merge(Planner.learning(null), "Methods", Planner.parseImport(reply))
        val red = Planner.setRating(l, Planner.items(l)[0].getString("id"), "red")
        val monday = LocalDate.of(2026, 10, 19)
        val (planned, count) = Planner.planWeek(red, "Methods", monday)
        assertEquals(1, count)
        assertEquals(0, Planner.planWeek(planned, "Methods", monday).second)
    }

    @Test fun sacValidationMatchesFocal() {
        val ok = Planner.newSac("Methods", "School", "SAC 1", 3, "2026-10-01", 60.0, 18.0, 20.0, 25.0)
        assertNull(Planner.validateSac(ok))
        assertNotNull(Planner.validateSac(JSONObject(ok.toString()).put("score", 21.0)))
        assertNotNull(Planner.validateSac(JSONObject(ok.toString()).apply { remove("maxScore") }))
        assertNotNull(Planner.validateSac(JSONObject(ok.toString()).put("unit", 5)))
    }

    @Test fun sacAverageIsWeighted() {
        val a = Planner.newSac("M", "S", "A", 3, "2026-01-01", 60.0, 10.0, 10.0, 75.0)
        val b = Planner.newSac("M", "S", "B", 3, "2026-02-01", 60.0, 0.0, 10.0, 25.0)
        assertEquals(75.0, Planner.stats(listOf(a, b)).average!!, 1e-9)
        assertEquals(-100.0, Planner.stats(listOf(a, b)).trend!!, 1e-9)
    }

    @Test fun goalValidation() {
        val today = LocalDate.of(2026, 10, 22)
        assertNull(Planner.validateGoal("atar", "", 90.0, "2026-12-01", today))
        assertNotNull(Planner.validateGoal("study-score", "Methods", 51.0, "2026-12-01", today))
        assertNotNull(Planner.validateGoal("exam-percentage", "", 80.0, "2026-12-01", today))
        assertNotNull(Planner.validateGoal("exam-percentage", "Methods", 80.0, "2026-10-01", today))
    }
}
