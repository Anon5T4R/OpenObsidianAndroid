package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The scheduling maths. A bug here is invisible for weeks and then quietly
 * ruins months of study, which is why it is tested away from any UI.
 */
class SrsTest {

    private val today = LocalDate.of(2026, 7, 21)

    @Test
    fun `a new card is due today`() {
        val c = Srs.newCard("nota.md", "Pergunta?", today)
        assertEquals("2026-07-21", c.due)
        assertEquals(0, c.reps)
        assertTrue(Srs.isDue(c, today))
    }

    @Test
    fun `first good answer schedules one day out`() {
        val c = Srs.grade(Srs.newCard("n.md", "q", today), Srs.Grade.GOOD, today)
        assertEquals(1, c.reps)
        assertEquals("2026-07-22", c.due)
    }

    @Test
    fun `first easy answer skips further ahead`() {
        val c = Srs.grade(Srs.newCard("n.md", "q", today), Srs.Grade.EASY, today)
        assertEquals("2026-07-25", c.due)
    }

    @Test
    fun `second good answer is six days`() {
        var c = Srs.grade(Srs.newCard("n.md", "q", today), Srs.Grade.GOOD, today)
        c = Srs.grade(c, Srs.Grade.GOOD, today)
        assertEquals("2026-07-27", c.due)
    }

    @Test
    fun `again resets the streak and costs ease`() {
        var c = Srs.grade(Srs.newCard("n.md", "q", today), Srs.Grade.GOOD, today)
        c = Srs.grade(c, Srs.Grade.AGAIN, today)
        assertEquals(0, c.reps)
        assertEquals(1, c.lapses)
        assertEquals(2.3, c.ease, 1e-9)
        // Comes back in the same session rather than tomorrow
        assertEquals("2026-07-21", c.due)
    }

    @Test
    fun `ease never falls below the floor`() {
        var c = Srs.newCard("n.md", "q", today)
        repeat(20) { c = Srs.grade(c, Srs.Grade.AGAIN, today) }
        assertTrue("ease was ${c.ease}", c.ease >= 1.3)
    }

    @Test
    fun `grading returns a new object and leaves the old one alone`() {
        val a = Srs.newCard("n.md", "q", today)
        val b = Srs.grade(a, Srs.Grade.EASY, today)
        assertEquals(0, a.reps)
        assertEquals(1, b.reps)
    }

    @Test
    fun `a suspended card is never due`() {
        val c = Srs.newCard("n.md", "q", today).copy(suspended = true)
        assertFalse(Srs.isDue(c, today))
    }

    @Test
    fun `due cards drain oldest first`() {
        val cards = mapOf(
            "b" to Srs.newCard("n.md", "b", today).copy(due = "2026-07-20"),
            "a" to Srs.newCard("n.md", "a", today).copy(due = "2026-07-19"),
            "c" to Srs.newCard("n.md", "c", today).copy(due = "2026-08-01"),
        )
        assertEquals(listOf("a", "b"), Srs.dueCards(cards, today).map { it.first })
    }

    @Test
    fun `sync keeps the schedule when only the answer changed`() {
        val id = Cards.cardId("n.md", "Pergunta?")
        var cards = Srs.syncFile(emptyMap(), "n.md", listOf(id to "Pergunta?"), today)
        cards = cards.mapValues { Srs.grade(it.value, Srs.Grade.EASY, today) }
        val before = cards.getValue(id).due
        // Same question, so the same id: the schedule survives
        val after = Srs.syncFile(cards, "n.md", listOf(id to "Pergunta?"), today)
        assertEquals(before, after.getValue(id).due)
    }

    @Test
    fun `sync drops a card removed from the note`() {
        val cards = Srs.syncFile(emptyMap(), "n.md", listOf("x" to "q"), today)
        assertEquals(1, cards.size)
        assertEquals(0, Srs.syncFile(cards, "n.md", emptyList(), today).size)
    }

    @Test
    fun `sync does not touch cards of other notes`() {
        val cards = Srs.syncFile(emptyMap(), "outra.md", listOf("x" to "q"), today)
        assertEquals(1, Srs.syncFile(cards, "n.md", emptyList(), today).size)
    }

    @Test
    fun `state survives a round trip through json`() {
        val cards = mapOf("id1" to Srs.newCard("n.md", "q", today).copy(reps = 3, ease = 2.15))
        val back = Srs.fromJson(Srs.toJson(cards))
        assertEquals(cards.getValue("id1").reps, back.getValue("id1").reps)
        assertEquals(cards.getValue("id1").ease, back.getValue("id1").ease, 1e-9)
        assertEquals(cards.getValue("id1").due, back.getValue("id1").due)
    }

    @Test
    fun `broken json gives an empty schedule instead of a crash`() {
        assertEquals(0, Srs.fromJson("{ not json").size)
        assertEquals(0, Srs.fromJson("").size)
    }
}
