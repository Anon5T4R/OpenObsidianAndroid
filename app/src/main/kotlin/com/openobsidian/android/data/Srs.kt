package com.openobsidian.android.data

import org.json.JSONObject
import java.time.LocalDate

/**
 * Spaced repetition (SM-2, the classic Anki algorithm).
 *
 * Ported from the desktop app so both sides schedule identically — the same
 * `.openobsidian/srs.json` is read by either, which is the whole point of
 * reviewing on the phone what you wrote on the computer.
 *
 * Pure maths, no Android imports beyond JSON: a bug here silently ruins months
 * of study, and it has to be testable on the JVM.
 */
object Srs {

    enum class Grade { AGAIN, HARD, GOOD, EASY }

    data class Card(
        /** Note the card lives in, relative to the vault root */
        val file: String,
        /** The question, kept so the review screen need not re-read the note */
        val q: String,
        val ease: Double = 2.5,
        /** Days until the next review */
        val interval: Double = 0.0,
        val reps: Int = 0,
        /** ISO date, YYYY-MM-DD */
        val due: String,
        val lapses: Int = 0,
        val suspended: Boolean = false,
    )

    private const val MIN_EASE = 1.3

    fun todayISO(today: LocalDate = LocalDate.now()): String = today.toString()

    fun addDays(iso: String, days: Double): String =
        LocalDate.parse(iso).plusDays(Math.round(days)).toString()

    /** New cards are due at once: writing a card means wanting to learn it. */
    fun newCard(file: String, q: String, today: LocalDate = LocalDate.now()) =
        Card(file = file, q = q, due = todayISO(today))

    /**
     * Returns a new state — never mutates, so a failed write cannot leave the
     * schedule half-updated.
     */
    fun grade(card: Card, g: Grade, today: LocalDate = LocalDate.now()): Card {
        val iso = todayISO(today)

        if (g == Grade.AGAIN) {
            return card.copy(
                reps = 0,
                interval = 0.0,
                ease = maxOf(MIN_EASE, card.ease - 0.2),
                lapses = card.lapses + 1,
                due = iso, // seen again in this same session
            )
        }

        val interval = when {
            card.reps == 0 -> if (g == Grade.EASY) 4.0 else 1.0
            card.reps == 1 -> if (g == Grade.EASY) 8.0 else 6.0
            else -> {
                val factor = when (g) {
                    Grade.HARD -> 1.2
                    Grade.EASY -> card.ease * 1.3
                    else -> card.ease
                }
                maxOf(1.0, card.interval * factor)
            }
        }

        val ease = when (g) {
            Grade.HARD -> maxOf(MIN_EASE, card.ease - 0.15)
            Grade.EASY -> card.ease + 0.15
            else -> card.ease
        }

        return card.copy(
            reps = card.reps + 1,
            interval = interval,
            ease = ease,
            due = addDays(iso, interval),
        )
    }

    fun isDue(card: Card, today: LocalDate = LocalDate.now()): Boolean =
        !card.suspended && card.due <= todayISO(today)

    /** Cards to review, oldest due date first so the backlog drains in order. */
    fun dueCards(cards: Map<String, Card>, today: LocalDate = LocalDate.now()): List<Pair<String, Card>> =
        cards.entries
            .filter { isDue(it.value, today) }
            .map { it.key to it.value }
            .sortedWith(compareBy({ it.second.due }, { it.second.q }))

    data class Stats(val total: Int, val due: Int, val suspended: Int, val fresh: Int)

    fun stats(cards: Map<String, Card>, today: LocalDate = LocalDate.now()): Stats {
        val all = cards.values
        return Stats(
            total = all.size,
            due = all.count { isDue(it, today) },
            suspended = all.count { it.suspended },
            fresh = all.count { it.reps == 0 },
        )
    }

    /**
     * Reconciles the cards found in a note with what is scheduled.
     * Editing the *answer* keeps the schedule; editing the *question* makes a
     * new card, because the id is a hash of the question.
     */
    fun syncFile(
        cards: Map<String, Card>,
        file: String,
        found: List<Pair<String, String>>, // id to question
        today: LocalDate = LocalDate.now(),
    ): Map<String, Card> {
        val out = cards.toMutableMap()
        val foundIds = found.map { it.first }.toSet()
        // Gone from the note → gone from the schedule
        out.keys.filter { id -> out[id]?.file == file && id !in foundIds }
            .forEach { out.remove(it) }
        for ((id, q) in found) {
            val existing = out[id]
            out[id] = existing?.copy(q = q, file = file) ?: newCard(file, q, today)
        }
        return out
    }

    // ── Storage ───────────────────────────────────────────────────────────
    //
    // Scheduling lives in `.openobsidian/srs.json`, never inside the notes: it
    // changes on every review and would dirty the diff of every file.

    fun toJson(cards: Map<String, Card>): String {
        val root = JSONObject()
        root.put("version", 1)
        val obj = JSONObject()
        for ((id, c) in cards) {
            obj.put(id, JSONObject().apply {
                put("file", c.file)
                put("q", c.q)
                put("ease", c.ease)
                put("interval", c.interval)
                put("reps", c.reps)
                put("due", c.due)
                put("lapses", c.lapses)
                if (c.suspended) put("suspended", true)
            })
        }
        root.put("cards", obj)
        return root.toString()
    }

    /** Unreadable or malformed state yields an empty schedule, never a crash. */
    fun fromJson(json: String): Map<String, Card> = runCatching {
        val obj = JSONObject(json).optJSONObject("cards") ?: return emptyMap()
        val out = LinkedHashMap<String, Card>()
        for (id in obj.keys()) {
            val c = obj.getJSONObject(id)
            out[id] = Card(
                file = c.optString("file"),
                q = c.optString("q"),
                ease = c.optDouble("ease", 2.5),
                interval = c.optDouble("interval", 0.0),
                reps = c.optInt("reps", 0),
                due = c.optString("due", todayISO()),
                lapses = c.optInt("lapses", 0),
                suspended = c.optBoolean("suspended", false),
            )
        }
        out
    }.getOrElse { emptyMap() }
}
