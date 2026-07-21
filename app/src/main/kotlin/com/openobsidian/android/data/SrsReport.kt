package com.openobsidian.android.data

import java.time.LocalDate

/**
 * What the schedule looks like from above: how much is retained, what is coming.
 *
 * Ported from the desktop (v0.10.0). Counting cards is the easy part; the
 * numbers below exist because "how am I doing" and "how bad is next week" are
 * the two questions a review backlog actually raises.
 */
object SrsReport {

    data class Report(
        val total: Int,
        val due: Int,
        val suspended: Int,
        /** Never reviewed */
        val fresh: Int,
        /** Reviewed at least once and never failed since */
        val learned: Int,
        /** Share of reviewed cards that never lapsed, 0..1 */
        val retention: Double,
        val averageEase: Double,
        /** How many fall due on each of the next days */
        val forecast: List<Pair<String, Int>>,
        /** Notes holding the most cards */
        val topNotes: List<Pair<String, Int>>,
    )

    fun of(
        cards: Map<String, Srs.Card>,
        today: LocalDate = LocalDate.now(),
        forecastDays: Int = 14,
    ): Report {
        val all = cards.values.toList()
        val reviewed = all.filter { it.reps > 0 }

        val forecast = (0 until forecastDays).map { offset ->
            val day = today.plusDays(offset.toLong()).toString()
            // Everything overdue lands on the first day: that is where it is
            val count = if (offset == 0) all.count { !it.suspended && it.due <= day }
            else all.count { !it.suspended && it.due == day }
            day to count
        }

        val topNotes = all.groupingBy { it.file }.eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(5)

        return Report(
            total = all.size,
            due = all.count { Srs.isDue(it, today) },
            suspended = all.count { it.suspended },
            fresh = all.count { it.reps == 0 },
            learned = reviewed.count { it.lapses == 0 },
            // A vault with nothing reviewed has no retention to report; 0 is
            // the honest answer, not a division by zero dressed as 100%
            retention = if (reviewed.isEmpty()) 0.0
            else reviewed.count { it.lapses == 0 }.toDouble() / reviewed.size,
            averageEase = if (all.isEmpty()) 0.0 else all.sumOf { it.ease } / all.size,
            forecast = forecast,
            topNotes = topNotes,
        )
    }
}
