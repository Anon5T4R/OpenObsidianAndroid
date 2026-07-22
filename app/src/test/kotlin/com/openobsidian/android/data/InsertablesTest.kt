package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class InsertablesTest {

    // ── The catalogue itself ─────────────────────────────────────────────────

    @Test
    fun `every slash command belongs to exactly one item`() {
        // Two items answering /card would make the picker a coin toss
        val seen = mutableMapOf<String, String>()
        for (item in Insertables.ALL) {
            for (s in item.slash) {
                assertFalse("/$s is on both ${seen[s]} and ${item.id}", seen.containsKey(s))
                seen[s] = item.id
            }
        }
    }

    @Test
    fun `ids are unique`() {
        assertEquals(Insertables.ALL.size, Insertables.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun `commands are written without the slash, since the code adds it`() {
        for (item in Insertables.ALL) {
            for (s in item.slash) assertFalse(item.id, s.startsWith("/"))
        }
    }

    @Test
    fun `every category has something in it`() {
        for (c in Insertables.CATEGORY_ORDER) {
            assertTrue("$c is empty", Insertables.ALL.any { it.category == c })
        }
    }

    @Test
    fun `every item inserts something, or opens the gallery`() {
        for (item in Insertables.ALL) {
            if (item.isImageImport) continue
            assertTrue(item.id, Insertables.resolve(item).text.isNotEmpty())
        }
    }

    @Test
    fun `the caret marker never survives into the document`() {
        // It is U+0001. One leaking through would be invisible in the note and
        // would travel into the vault, the index and the sync.
        for (item in Insertables.ALL) {
            assertFalse(item.id, Insertables.resolve(item).text.contains(Insertables.CARET))
        }
    }

    @Test
    fun `the caret always lands inside the snippet`() {
        for (item in Insertables.ALL) {
            val (text, cursor) = Insertables.resolve(item)
            assertTrue("${item.id} lands at $cursor", cursor in 0..text.length)
        }
    }

    @Test
    fun `the caret never lands in the middle of a word`() {
        // The desktop counts this offset by hand and got it wrong three times,
        // twice destructively — /card opened at "Questi|on", so the first thing
        // typed shredded the snippet. Here the position comes from a marker and
        // cannot be miscounted, but the invariant is asserted anyway: it is what
        // would catch a snippet whose marker was pasted into the wrong place.
        val word = Regex("\\w")
        val broken = Insertables.ALL.mapNotNull { item ->
            val (text, cursor) = Insertables.resolve(item)
            if (cursor > 0 && cursor < text.length &&
                word.matches(text[cursor - 1].toString()) && word.matches(text[cursor].toString())
            ) "${item.id} -> ...${text.substring(maxOf(0, cursor - 8), cursor)}|${text.substring(cursor, minOf(text.length, cursor + 8))}..."
            else null
        }
        assertEquals(emptyList<String>(), broken)
    }

    // ── The features nothing in the UI used to mention ───────────────────────

    @Test
    fun `offers flashcards, callouts, mermaid, maths, embeds and queries`() {
        val ids = Insertables.ALL.map { it.id }.toSet()
        for (id in listOf(
            "cardQa", "cardCloze", "mnemonic",
            "calloutWarning", "mermaidFlow", "mathBlock",
            "embedNote", "queryTag", "indexNote",
        )) {
            assertTrue("$id is missing from the catalogue", id in ids)
        }
    }

    @Test
    fun `writes a card the extractor really reads`() {
        // Asserted against Cards.extractCards, not against the shape of the
        // string: the menu must not be able to offer a flashcard the engine
        // then ignores.
        val item = Insertables.ALL.first { it.id == "cardQa" }
        val cards = Cards.extractCards("Note.md", Insertables.resolve(item).text)
        assertEquals(1, cards.size)
    }

    @Test
    fun `writes a cloze card that yields one gap`() {
        val item = Insertables.ALL.first { it.id == "cardCloze" }
        val cards = Cards.extractCards("Note.md", Insertables.resolve(item).text)
        assertEquals(1, cards.size)
    }

    @Test
    fun `writes a reviewable mnemonic, which needs the question mark`() {
        // Without `?` a mnemonic is decoration and never enters review. A menu
        // entry that produced the decorative form would look like it worked.
        val item = Insertables.ALL.first { it.id == "mnemonic" }
        assertTrue(Insertables.resolve(item).text.startsWith("> [!mnemonic]?"))
        assertEquals(1, Cards.extractCards("Note.md", Insertables.resolve(item).text).size)
    }

    @Test
    fun `writes query blocks the runner really parses`() {
        for (id in listOf("queryTag", "queryField", "indexNote")) {
            val item = Insertables.ALL.first { it.id == id }
            val text = Insertables.resolve(item).text
            val block = text.substringAfter("```query\n").substringBefore("```")
            val spec = NoteQuery.parse(block)
            // The hint line inside the scaffold starts with `#`, which the parser
            // treats as a comment — teaching the syntax where it is needed, free
            assertFalse("$id: hint line is being read as a filter", spec.unknown.any { it.startsWith("# fields") })
        }
    }

    @Test
    fun `the index scaffold opens on its first tag slot`() {
        val item = Insertables.ALL.first { it.id == "indexNote" }
        val (text, cursor) = Insertables.resolve(item)
        assertTrue(text.substring(0, cursor).endsWith("tag: "))
        assertTrue(text.substring(cursor).startsWith("\nordenar:"))
    }

    // ── resolve ──────────────────────────────────────────────────────────────

    @Test
    fun `date and time are computed when inserted, not when the app started`() {
        // A phone keeps the process alive for days; a constant read at launch
        // inserts Monday's date on Friday.
        val today = Insertables.ALL.first { it.id == "today" }
        assertEquals("2026-07-21", Insertables.resolve(today, LocalDateTime.of(2026, 7, 21, 9, 5)).text)
        assertEquals("2027-01-05", Insertables.resolve(today, LocalDateTime.of(2027, 1, 5, 9, 5)).text)

        val now = Insertables.ALL.first { it.id == "now" }
        assertEquals("09:05", Insertables.resolve(now, LocalDateTime.of(2026, 7, 21, 9, 5)).text)
    }

    @Test
    fun `puts the caret inside the bold markers`() {
        val (text, cursor) = Insertables.resolve(Insertables.ALL.first { it.id == "bold" })
        assertEquals("**text", text.substring(0, cursor))
    }

    @Test
    fun `puts the caret inside the empty wikilink`() {
        val (text, cursor) = Insertables.resolve(Insertables.ALL.first { it.id == "wikilink" })
        assertEquals("[[", text.substring(0, cursor))
    }

    // ── search ───────────────────────────────────────────────────────────────

    @Test
    fun `an empty query returns the whole catalogue`() {
        assertEquals(Insertables.ALL.size, Insertables.search("").size)
    }

    @Test
    fun `an exact command wins over one that merely starts with it`() {
        assertEquals("tag", Insertables.search("tag").first().id)
    }

    @Test
    fun `finds a card through its alias`() {
        assertEquals("cardQa", Insertables.search("flashcard").first().id)
    }

    @Test
    fun `tolerates the slash the user just typed`() {
        assertEquals("cardQa", Insertables.search("/card").first().id)
    }

    @Test
    fun `returns nothing for gibberish, rather than everything`() {
        assertEquals(emptyList<Insertables.Item>(), Insertables.search("zzzznothing"))
    }
}
