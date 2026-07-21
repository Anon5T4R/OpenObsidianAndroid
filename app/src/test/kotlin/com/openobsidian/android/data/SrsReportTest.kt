package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SrsReportTest {

    private val today = LocalDate.of(2026, 7, 21)

    private fun card(
        file: String = "n.md",
        reps: Int = 0,
        lapses: Int = 0,
        due: String = "2026-07-21",
        ease: Double = 2.5,
        suspended: Boolean = false,
    ) = Srs.Card(file = file, q = "q", ease = ease, interval = 0.0, reps = reps, due = due, lapses = lapses, suspended = suspended)

    @Test
    fun `an empty schedule reports zeros, not a division by zero`() {
        val r = SrsReport.of(emptyMap(), today)
        assertEquals(0, r.total)
        assertEquals(0.0, r.retention, 1e-9)
        assertEquals(0.0, r.averageEase, 1e-9)
    }

    @Test
    fun `retention counts only what was actually reviewed`() {
        val cards = mapOf(
            "a" to card(reps = 3, lapses = 0),
            "b" to card(reps = 2, lapses = 1),
            "c" to card(reps = 0),   // nunca revisado: fora da conta
        )
        val r = SrsReport.of(cards, today)
        assertEquals(0.5, r.retention, 1e-9)
        assertEquals(1, r.fresh)
        assertEquals(1, r.learned)
    }

    @Test
    fun `overdue cards all land on the first forecast day`() {
        val cards = mapOf(
            "a" to card(due = "2026-07-01"),
            "b" to card(due = "2026-07-20"),
            "c" to card(due = "2026-07-23"),
        )
        val r = SrsReport.of(cards, today)
        assertEquals("2026-07-21", r.forecast[0].first)
        assertEquals(2, r.forecast[0].second)
        assertEquals(1, r.forecast[2].second)
    }

    @Test
    fun `the forecast covers the days asked for`() {
        assertEquals(14, SrsReport.of(emptyMap(), today).forecast.size)
        assertEquals(7, SrsReport.of(emptyMap(), today, forecastDays = 7).forecast.size)
    }

    @Test
    fun `a suspended card is in neither the due count nor the forecast`() {
        val cards = mapOf("a" to card(suspended = true))
        val r = SrsReport.of(cards, today)
        assertEquals(1, r.total)
        assertEquals(0, r.due)
        assertEquals(1, r.suspended)
        assertEquals(0, r.forecast[0].second)
    }

    @Test
    fun `the notes with the most cards come first`() {
        val cards = mapOf(
            "1" to card(file = "Sepse.md"), "2" to card(file = "Sepse.md"),
            "3" to card(file = "Sepse.md"), "4" to card(file = "IAM.md"),
        )
        val top = SrsReport.of(cards, today).topNotes
        assertEquals("Sepse.md" to 3, top[0])
        assertEquals("IAM.md" to 1, top[1])
    }

    @Test
    fun `average ease is the average, not the last one`() {
        val cards = mapOf("a" to card(ease = 2.0), "b" to card(ease = 3.0))
        assertEquals(2.5, SrsReport.of(cards, today).averageEase, 1e-9)
    }

    @Test
    fun `topNotes is capped so one screen can hold it`() {
        val cards = (1..20).associate { "$it" to card(file = "nota$it.md") }
        assertTrue(SrsReport.of(cards, today).topNotes.size <= 5)
    }
}
