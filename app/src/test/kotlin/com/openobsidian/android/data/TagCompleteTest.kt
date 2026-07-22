package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagCompleteTest {

    private val counts = mapOf(
        "sis-cardio" to 4,
        "sis-pneumo" to 2,
        "sx-dispneia" to 3,
        "tipo-ficha" to 1,
        "cardiopatia" to 5,
        "sistema/cardio" to 1,
    )

    // ── rank ─────────────────────────────────────────────────────────────────

    @Test
    fun `a prefix match beats a mere substring match`() {
        // cardiopatia has more notes, but sis-cardio is what "sis" starts
        val r = TagComplete.rank(counts, "sis").map { it.tag }
        assertEquals("sis-cardio", r[0])
        assertEquals("sis-pneumo", r[1])
    }

    @Test
    fun `the most used wins inside a group`() {
        val r = TagComplete.rank(counts, "s").map { it.tag }
        assertEquals(listOf("sis-cardio", "sx-dispneia", "sis-pneumo"), r.take(3))
    }

    @Test
    fun `still finds a tag by its middle`() {
        assertTrue(TagComplete.rank(counts, "cardio").map { it.tag }.contains("sistema/cardio"))
    }

    @Test
    fun `an empty query offers everything, most used first`() {
        val r = TagComplete.rank(counts, "")
        assertEquals(counts.size, r.size)
        assertEquals("cardiopatia", r[0].tag)
    }

    @Test
    fun `carries the note count, which is what makes the order legible`() {
        assertEquals(4, TagComplete.rank(counts, "sis-cardio")[0].count)
    }

    @Test
    fun `ignores case`() {
        assertTrue(TagComplete.rank(counts, "SIS-Cardio").map { it.tag }.contains("sis-cardio"))
    }

    @Test
    fun `offers nested tags whole`() {
        assertEquals(listOf("sistema/cardio"), TagComplete.rank(counts, "sistema/").map { it.tag })
    }

    @Test
    fun `returns nothing for a miss, rather than everything`() {
        assertEquals(emptyList<TagComplete.Option>(), TagComplete.rank(counts, "zzzz"))
    }

    @Test
    fun `honours the limit so a big vault cannot flood the sheet`() {
        assertEquals(2, TagComplete.rank(counts, "", limit = 2).size)
    }

    @Test
    fun `breaks ties alphabetically, so the order never shuffles`() {
        val tied = mapOf("beta" to 1, "alpha" to 1)
        assertEquals(listOf("alpha", "beta"), TagComplete.rank(tied, "").map { it.tag })
    }

    // ── countTags ────────────────────────────────────────────────────────────

    @Test
    fun `counts notes per tag, not occurrences`() {
        val byPath = mapOf(
            "a.md" to listOf("x", "x", "y"),
            "b.md" to listOf("x"),
        )
        assertEquals(mapOf("x" to 2, "y" to 1), TagComplete.countTags(byPath))
    }

    @Test
    fun `an empty vault yields no tags rather than throwing`() {
        assertEquals(emptyMap<String, Int>(), TagComplete.countTags(emptyMap()))
    }

    // ── matchAtCursor: when the menu may open ────────────────────────────────

    private fun q(line: String) = TagComplete.matchAtCursor(line)?.query

    @Test
    fun `opens on a tag being typed`() {
        assertEquals("sis", q("#sis"))
        assertEquals("sis-car", q("texto solto #sis-car"))
    }

    @Test
    fun `stays shut while a heading is being written`() {
        // The one case that would make this feature hated: a menu popping up on
        // every `# Título` in a vault of 470 notes.
        assertNull(q("# "))
        assertNull(q("# Sepse e Choque"))
        assertNull(q("## "))
        assertNull(q("### Diagnóstico"))
    }

    @Test
    fun `stays shut on a bare hash, which is a heading about to happen`() {
        assertNull(q("#"))
        assertNull(q("##"))
    }

    @Test
    fun `opens inside a query block, which is the point of all this`() {
        assertEquals("sis-car", q("tag: #sis-car"))
    }

    @Test
    fun `opens inside a callout`() {
        assertEquals("sis", q("> #sis"))
    }

    @Test
    fun `stays shut mid-word, so a URL fragment is not a tag`() {
        assertNull(q("http://x.com/p#secao"))
        assertNull(q("nota#ancora"))
    }

    @Test
    fun `accepts accents and nesting, like the indexer does`() {
        assertEquals("pré-natal", q("#pré-natal"))
        assertEquals("sistema/cardio", q("#sistema/cardio"))
    }

    @Test
    fun `reports where the hash is, so the replacement covers it`() {
        assertEquals(TagComplete.Match(4, "sis"), TagComplete.matchAtCursor("abc #sis"))
    }

    @Test
    fun `only looks at the tag touching the cursor`() {
        assertEquals("sis-car", q("#tipo-patologia #sis-car"))
    }
}
