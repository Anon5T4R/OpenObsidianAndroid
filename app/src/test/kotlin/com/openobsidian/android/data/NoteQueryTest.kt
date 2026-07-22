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

    // ── sort: criado ─────────────────────────────────────────────────────────

    private fun nota(name: String, criado: String? = null) = NoteQuery.Note(
        name = name,
        relativePath = "$name.md",
        mtime = 0L,
        tags = listOf("x"),
        fields = if (criado == null) emptyMap() else mapOf("criado" to listOf(criado)),
    )

    private val porCriado get() = NoteQuery.parse("tag: x\nsort: criado")

    @Test
    fun `criado is accepted, like the desktop accepts it`() {
        // It used to fall through to unknown here while the desktop honoured
        // it, so the same index warned on one platform and worked on the other
        assertEquals(emptyList<String>(), porCriado.unknown)
        assertEquals(NoteQuery.SortKey.CREATED, porCriado.sortBy)
    }

    @Test
    fun `ISO dates sort chronologically, which is why ISO is the supported form`() {
        val r = NoteQuery.run(listOf(nota("Jul", "2026-07-21"), nota("Jan", "2026-01-05")), porCriado)
        assertEquals(listOf("Jan", "Jul"), r.map { it.name })
    }

    @Test
    fun `says nothing when every note carries an ISO date`() {
        val notes = listOf(nota("A", "2026-01-05"), nota("B", "2026-07-21"))
        assertEquals(emptyList<NoteQuery.SortIssue>(), NoteQuery.sortIssues(notes, porCriado))
    }

    @Test
    fun `reports that nobody declares the field`() {
        // The silent one: all values tie, a stable sort returns scan order, and
        // the list looks ordered
        val issues = NoteQuery.sortIssues(listOf(nota("A"), nota("B")), porCriado)
        assertEquals(listOf(NoteQuery.SortIssue.CreatedMissing(2, 2)), issues)
    }

    @Test
    fun `reports a partly filled set, where the gaps clump at one end`() {
        val issues = NoteQuery.sortIssues(listOf(nota("A", "2026-01-05"), nota("B")), porCriado)
        assertTrue(issues.contains(NoteQuery.SortIssue.CreatedMissing(1, 2)))
    }

    @Test
    fun `reports a date that is not ISO, quoting the offender`() {
        val issues = NoteQuery.sortIssues(listOf(nota("A", "21/07/2025"), nota("B", "2026-01-05")), porCriado)
        assertTrue(issues.contains(NoteQuery.SortIssue.CreatedNotIso("21/07/2025")))
    }

    @Test
    fun `does not try to guess what 03 01 2026 means`() {
        // 3 January or 1 March depending on where you live. Reported, never fixed.
        val issues = NoteQuery.sortIssues(listOf(nota("A", "03/01/2026"), nota("B", "2026-05-01")), porCriado)
        assertTrue(issues.any { it is NoteQuery.SortIssue.CreatedNotIso })
    }

    @Test
    fun `stays quiet for every other sort key`() {
        for (key in listOf("titulo", "modificado", "caminho")) {
            val spec = NoteQuery.parse("tag: x\nsort: $key")
            assertEquals(key, emptyList<NoteQuery.SortIssue>(), NoteQuery.sortIssues(listOf(nota("A"), nota("B")), spec))
        }
    }

    @Test
    fun `stays quiet when nothing matched, since there is nothing to mis-order`() {
        assertEquals(emptyList<NoteQuery.SortIssue>(), NoteQuery.sortIssues(emptyList(), porCriado))
    }
}