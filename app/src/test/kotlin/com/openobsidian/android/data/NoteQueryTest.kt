package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteQueryTest {

    private fun note(
        name: String,
        path: String = "$name.md",
        mtime: Long = 0,
        tags: List<String> = emptyList(),
        fields: Map<String, List<String>> = emptyMap(),
    ) = NoteQuery.Note(name, path, mtime, tags, fields)

    private val vault = listOf(
        note("Sepse", "Clinica/Sepse.md", 300, listOf("sis-infecto", "tipo-protocolo"), mapOf("tipo" to listOf("protocolo"))),
        note("IAM", "Cardio/IAM.md", 200, listOf("sis-cardio", "tipo-patologia"), mapOf("tipo" to listOf("patologia"), "cartoes" to listOf("sim"))),
        note("AVC", "Neuro/AVC.md", 100, listOf("sis-neuro"), emptyMap()),
    )

    @Test
    fun `filters by tag`() {
        val r = NoteQuery.run(vault, NoteQuery.parse("tag: sis-cardio"))
        assertEquals(listOf("IAM"), r.map { it.name })
    }

    @Test
    fun `two tags are an AND`() {
        val spec = NoteQuery.parse("tag: tipo-protocolo\ntag: sis-infecto")
        assertEquals(listOf("Sepse"), NoteQuery.run(vault, spec).map { it.name })
        val none = NoteQuery.parse("tag: tipo-protocolo\ntag: sis-cardio")
        assertTrue(NoteQuery.run(vault, none).isEmpty())
    }

    @Test
    fun `a parent tag finds its children`() {
        val v = listOf(note("X", tags = listOf("sistema/cardio")))
        assertEquals(1, NoteQuery.run(v, NoteQuery.parse("tag: sistema")).size)
    }

    @Test
    fun `filters by folder`() {
        assertEquals(listOf("IAM"), NoteQuery.run(vault, NoteQuery.parse("path: cardio")).map { it.name })
    }

    @Test
    fun `filters by a frontmatter field`() {
        assertEquals(listOf("IAM"), NoteQuery.run(vault, NoteQuery.parse("tipo: patologia")).map { it.name })
    }

    @Test
    fun `has only requires the key to exist`() {
        assertEquals(listOf("IAM"), NoteQuery.run(vault, NoteQuery.parse("has: cartoes")).map { it.name })
    }

    @Test
    fun `sorts by modification, newest first when desc`() {
        val spec = NoteQuery.parse("path: /\nsort: modificado desc")
        // path "/" matches every relative path, so this is really "everything, sorted"
        val r = NoteQuery.run(vault, spec)
        assertEquals(listOf("Sepse", "IAM", "AVC"), r.map { it.name })
    }

    @Test
    fun `limit cuts the list`() {
        val spec = NoteQuery.parse("path: /\nlimit: 2")
        assertEquals(2, NoteQuery.run(vault, spec).size)
    }

    @Test
    fun `a spec with no filter returns nothing`() {
        // Returning the whole vault would look exactly like a working query
        assertTrue(NoteQuery.run(vault, NoteQuery.parse("sort: titulo")).isEmpty())
        assertTrue(NoteQuery.run(vault, NoteQuery.parse("")).isEmpty())
    }

    @Test
    fun `a line that cannot be read is reported, not ignored`() {
        val spec = NoteQuery.parse("tag: cardio\nisto nao e uma linha valida")
        assertEquals(listOf("isto nao e uma linha valida"), spec.unknown)
        // and the rest of the query still works
        assertEquals(listOf("cardio"), spec.tags)
    }

    @Test
    fun `an unknown sort key is reported`() {
        assertTrue(NoteQuery.parse("sort: cor").unknown.isNotEmpty())
    }

    @Test
    fun `a limit that is not a number is reported`() {
        assertTrue(NoteQuery.parse("limit: muitos").unknown.isNotEmpty())
    }

    @Test
    fun `portuguese keys work the same`() {
        val spec = NoteQuery.parse("pasta: cardio\nordenar: titulo\nlimite: 5")
        assertEquals("cardio", spec.path)
        assertEquals(NoteQuery.SortKey.TITLE, spec.sortBy)
        assertEquals(5, spec.limit)
        assertTrue(spec.unknown.isEmpty())
    }

    @Test
    fun `a comment line is skipped silently`() {
        assertTrue(NoteQuery.parse("# só um comentário\ntag: x").unknown.isEmpty())
    }

    @Test
    fun `a tag written with a hash still matches`() {
        assertEquals(listOf("sis-cardio"), NoteQuery.parse("tag: #sis-cardio").tags)
    }

    @Test
    fun `a field that does not exist matches nothing`() {
        assertFalse(NoteQuery.matches(vault[2], NoteQuery.parse("tipo: patologia")))
    }
}
