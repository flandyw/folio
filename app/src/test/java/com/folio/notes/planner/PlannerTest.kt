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

    @Test fun importSaveClosesOnlyAfterADurableSave() = kotlinx.coroutines.runBlocking {
        var calls = 0
        // The callback's null is success, not "nothing imported".
        assertNull(Planner.importChecklist(" Methods ", reply) { subject, items -> calls++; assertEquals("Methods", subject); assertEquals(2, items.size); null })
        assertEquals("Could not save the checklist. Try again.", Planner.importChecklist("Methods", reply) { _, _ -> calls++; "Could not save the checklist. Try again." })
        assertEquals("No items found in that reply.", Planner.importChecklist("Methods", "{\"groups\":[]}") { _, _ -> calls++; null })
        assertEquals("Paste the chatbot's JSON reply.", Planner.importChecklist("Methods", "no json here") { _, _ -> calls++; null })
        assertNotNull(Planner.importChecklist("Methods", "{\"groups\": [}") { _, _ -> calls++; null })
        assertEquals("Choose a subject.", Planner.importChecklist(" ", reply) { _, _ -> calls++; null })
        assertEquals(2, calls)
        // Cancellation from the save is not turned into a form message.
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { Planner.importChecklist("Methods", reply) { _, _ -> throw kotlinx.coroutines.CancellationException("left") } }
        }
        Unit
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

    private fun form(source: JSONObject? = null, score: String = "", max: String = "", weighting: String = "", minutes: String = "60") =
        Planner.sacFromForm(source, "Methods", "School", "SAC 2.0", "3", "2026-08-01", minutes, score, max, weighting, now = "2026-07-01T00:00:00.000Z")

    @Test fun sacFormRejectsBadNumbersWithoutThrowing() {
        for (bad in listOf("abc", "NaN", "Infinity", "-Infinity", "1e999")) {
            assertEquals(bad, "Marks and weighting must be numbers.", form(score = bad, max = "10").second)
            assertEquals(bad, "Marks and weighting must be numbers.", form(score = "5", max = bad).second)
            assertEquals(bad, "Marks and weighting must be numbers.", form(weighting = bad).second)
            assertEquals(bad, "Duration must be greater than zero.", form(minutes = bad).second)
        }
        assertEquals("Enter both the mark and maximum, or leave both blank.", form(score = "", max = "10").second)
        assertEquals("Enter both the mark and maximum, or leave both blank.", form(score = "7", max = " ").second)
    }

    @Test fun sacFormSavesUpcomingAndCompletedMarks() {
        val (upcoming, none) = form(weighting = "25")
        assertNull(none)
        assertFalse(Planner.isCompleted(upcoming!!))
        assertFalse(upcoming.has("completedAt"))
        assertEquals("SAC 2.0", upcoming.getString("title"))
        assertEquals(25.0, upcoming.getDouble("weighting"), 0.0)
        val (done, error) = form(upcoming, score = " 18 ", max = "20")
        assertNull(error)
        assertEquals(90.0, Planner.percentage(done!!)!!, 1e-9)
        assertEquals("2026-08-01", done.getString("completedAt"))
        assertEquals(upcoming.getString("id"), done.getString("id"))
        // Clearing both marks makes it upcoming again.
        assertFalse(form(done).first!!.has("completedAt"))
    }

    @Test fun sacFormKeepsUnknownFieldsAndLeavesTheSourceAlone() {
        val source = Planner.newSac("Methods", "School", "SAC 1", 3, "2026-08-01", 60.0, null, null, null, "2026-06-01T00:00:00.000Z")
            .put("focalOnly", JSONObject().put("x", 1))
        val before = source.toString()
        val saved = form(source, score = "5", max = "10").first!!
        assertEquals(before, source.toString())
        assertEquals(1, saved.getJSONObject("focalOnly").getInt("x"))
        assertEquals("2026-06-01T00:00:00.000Z", saved.getString("createdAt"))
        assertEquals("2026-07-01T00:00:00.000Z", saved.getString("updatedAt"))
        assertEquals(source.getString("id"), saved.getString("id"))
        assertEquals(before, source.toString())
        assertNull(form(source, score = "x", max = "10").first)
        assertEquals(before, source.toString())
    }

    @Test fun sacFormTextKeepsTitlesLiteralAndTidiesNumbers() {
        val sac = JSONObject().put("title", "SAC 2.0").put("provider", "Room 1.0").put("unit", 3).put("durationMinutes", 60.0)
            .put("score", 17.5).put("maxScore", 20.0)
        assertEquals("SAC 2.0", Planner.sacFormText(sac, "title"))
        assertEquals("Room 1.0", Planner.sacFormText(sac, "provider"))
        assertEquals("3", Planner.sacFormText(sac, "unit"))
        assertEquals("60", Planner.sacFormText(sac, "durationMinutes"))
        assertEquals("17.5", Planner.sacFormText(sac, "score"))
        assertEquals("20", Planner.sacFormText(sac, "maxScore"))
        assertEquals("", Planner.sacFormText(sac, "weighting"))
        assertEquals("", Planner.sacFormText(null, "title"))
    }
}
