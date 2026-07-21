package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    private val sepse = SearchQuery.Note(
        name = "Sepse",
        relativePath = "Clinica/Sepse.md",
        content = "Sepse é disfunção orgânica. O lactato sobe. Sepse mata.",
        tags = listOf("sistema/infecto", "tipo-protocolo"),
    )
    private val avc = SearchQuery.Note(
        name = "AVC",
        relativePath = "Neuro/AVC.md",
        content = "Trombólise até 4,5 horas.",
        tags = listOf("sistema/neuro"),
    )

    private fun matches(q: String, note: SearchQuery.Note) =
        SearchQuery.matches(note, SearchQuery.parse(q))

    @Test
    fun `plain text matches name or body`() {
        assertTrue(matches("lactato", sepse))
        assertTrue(matches("sepse", sepse))
        assertFalse(matches("lactato", avc))
    }

    @Test
    fun `search is case insensitive`() {
        assertTrue(matches("LACTATO", sepse))
    }

    @Test
    fun `several terms all have to match`() {
        assertTrue(matches("sepse lactato", sepse))
        assertFalse(matches("sepse trombolise", sepse))
    }

    @Test
    fun `a minus sign excludes`() {
        assertFalse(matches("sepse -lactato", sepse))
        assertTrue(matches("sepse -trombolise", sepse))
    }

    @Test
    fun `tag matches exactly`() {
        assertTrue(matches("tag:tipo-protocolo", sepse))
        assertFalse(matches("tag:tipo-protocolo", avc))
    }

    @Test
    fun `a parent tag finds its children`() {
        assertTrue(matches("tag:sistema", sepse))
        assertTrue(matches("tag:sistema", avc))
        assertTrue(matches("tag:sistema/infecto", sepse))
        assertFalse(matches("tag:sistema/infecto", avc))
    }

    @Test
    fun `path narrows by folder`() {
        assertTrue(matches("path:clinica", sepse))
        assertFalse(matches("path:clinica", avc))
    }

    @Test
    fun `file matches only the name`() {
        assertTrue(matches("file:avc", avc))
        // "lactato" is in the body of Sepse, not in its name
        assertFalse(matches("file:lactato", sepse))
    }

    @Test
    fun `a quoted phrase is one term`() {
        assertTrue(matches("\"disfunção orgânica\"", sepse))
        assertFalse(matches("\"orgânica disfunção\"", sepse))
    }

    @Test
    fun `an empty query matches nothing`() {
        assertFalse(matches("", sepse))
        assertFalse(matches("   ", sepse))
    }

    @Test
    fun `an unknown field is treated as text, not dropped`() {
        // Silently ignoring it would return the whole vault for a typo
        val parsed = SearchQuery.parse("autor:joao")
        assertEquals(1, parsed.terms.size)
        assertEquals("autor:joao", parsed.terms[0].text)
    }

    @Test
    fun `an exact title outranks a body full of mentions`() {
        val about = SearchQuery.Note("Sepse", "a.md", "curto", emptyList())
        val long = SearchQuery.Note("Outra", "b.md", "sepse ".repeat(200), emptyList())
        val q = SearchQuery.parse("sepse")
        assertTrue(
            "titulo=${SearchQuery.score(about, q)} corpo=${SearchQuery.score(long, q)}",
            SearchQuery.score(about, q) > SearchQuery.score(long, q),
        )
    }

    @Test
    fun `more mentions still score higher than fewer`() {
        val few = SearchQuery.Note("A", "a.md", "sepse", emptyList())
        val many = SearchQuery.Note("B", "b.md", "sepse sepse sepse sepse", emptyList())
        val q = SearchQuery.parse("sepse")
        assertTrue(SearchQuery.score(many, q) > SearchQuery.score(few, q))
    }
}
