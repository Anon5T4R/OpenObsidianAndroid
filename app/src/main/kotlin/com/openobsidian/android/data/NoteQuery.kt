package com.openobsidian.android.data

/**
 * ```query blocks: an index derived from the notes instead of typed by hand.
 *
 * ````markdown
 * ```query
 * tag: sis-cardio
 * tipo: patologia      ← any frontmatter field
 * sort: modificado desc
 * limit: 20
 * ```
 * ````
 *
 * Ported from the desktop (v0.10.0). Two rules carried over deliberately:
 * a line that cannot be read is reported above the results rather than
 * ignored, and a spec with no filter returns nothing instead of the whole
 * vault — the failure mode of a silent query is a list that looks right.
 */
object NoteQuery {

    enum class SortKey { TITLE, MODIFIED, PATH, CREATED }

    data class Spec(
        val tags: List<String> = emptyList(),
        val path: String? = null,
        /** Frontmatter keys that only have to exist */
        val has: List<String> = emptyList(),
        /** Frontmatter field → required value */
        val fields: Map<String, String> = emptyMap(),
        val sortBy: SortKey? = null,
        val sortDesc: Boolean = false,
        val limit: Int? = null,
        /** Lines that could not be understood, shown to the author */
        val unknown: List<String> = emptyList(),
    ) {
        /** No filter at all: returning everything would look like a working query. */
        val hasNoFilter: Boolean
            get() = tags.isEmpty() && path == null && has.isEmpty() && fields.isEmpty()
    }

    private val RESERVED = setOf(
        "tag", "tags", "path", "pasta", "has", "tem",
        "sort", "ordenar", "limit", "limite",
    )

    fun parse(source: String): Spec {
        val tags = mutableListOf<String>()
        val has = mutableListOf<String>()
        val fields = LinkedHashMap<String, String>()
        val unknown = mutableListOf<String>()
        var path: String? = null
        var sortBy: SortKey? = null
        var sortDesc = false
        var limit: Int? = null

        for (raw in source.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val i = line.indexOf(':')
            if (i <= 0) { unknown += line; continue }
            val key = line.substring(0, i).trim().lowercase()
            val value = line.substring(i + 1).trim()
            if (value.isEmpty()) { unknown += line; continue }

            when (key) {
                "tag", "tags" -> tags += value.removePrefix("#").lowercase()
                "path", "pasta" -> path = value.lowercase()
                "has", "tem" -> has += value.lowercase()
                "sort", "ordenar" -> {
                    val parts = value.lowercase().split(Regex("\\s+"))
                    sortDesc = parts.any { it == "desc" || it == "descendente" }
                    sortBy = when (parts.first()) {
                        "titulo", "título", "title", "nome", "name" -> SortKey.TITLE
                        "modificado", "modified", "mtime" -> SortKey.MODIFIED
                        "caminho", "path" -> SortKey.PATH
                        "criado", "created" -> SortKey.CREATED
                        else -> null
                    }
                    if (sortBy == null) unknown += line
                }
                "limit", "limite" -> {
                    limit = value.toIntOrNull()
                    if (limit == null) unknown += line
                }
                else -> if (key in RESERVED) unknown += line else fields[key] = value.lowercase()
            }
        }
        return Spec(tags, path, has, fields, sortBy, sortDesc, limit, unknown)
    }

    /** A note as a query sees it. */
    data class Note(
        val name: String,
        val relativePath: String,
        val mtime: Long,
        val tags: List<String>,
        val fields: Map<String, List<String>>,
    )

    fun matches(note: Note, spec: Spec): Boolean {
        if (spec.hasNoFilter) return false
        // Several `tag:` lines are an AND, which is the cut that is tedious by hand
        for (t in spec.tags) {
            if (note.tags.none { it == t || it.startsWith("$t/") }) return false
        }
        spec.path?.let { if (!note.relativePath.lowercase().contains(it)) return false }
        for (k in spec.has) {
            if (note.fields.keys.none { it.equals(k, ignoreCase = true) }) return false
        }
        for ((k, v) in spec.fields) {
            val values = note.fields.entries
                .firstOrNull { it.key.equals(k, ignoreCase = true) }?.value ?: return false
            if (values.none { it.lowercase() == v }) return false
        }
        return true
    }

    fun run(notes: List<Note>, spec: Spec): List<Note> {
        if (spec.hasNoFilter) return emptyList()
        var out = notes.filter { matches(it, spec) }
        out = when (spec.sortBy) {
            SortKey.TITLE -> out.sortedBy { it.name.lowercase() }
            SortKey.MODIFIED -> out.sortedBy { it.mtime }
            SortKey.PATH -> out.sortedBy { it.relativePath.lowercase() }
            // Compared as text, which is why only ISO is supported: see sortIssues
            SortKey.CREATED -> out.sortedBy { createdValue(it) }
            null -> out.sortedBy { it.name.lowercase() }
        }
        if (spec.sortDesc) out = out.reversed()
        spec.limit?.let { if (it >= 0) out = out.take(it) }
        return out
    }

    // ── Sorting by creation date ─────────────────────────────────────────────
    //
    // Until now `sort: criado` was simply rejected here while the desktop
    // accepted it, so the same index worked on one and warned on the other. Now
    // both accept it, and both say when the order cannot mean anything.
    //
    // There is no creation date on disk that survives a sync or a copy — and on
    // Android there is not even a stable inode behind the SAF — so it can only
    // come from a frontmatter field, and it arrives as text. Two ways that goes
    // wrong, and both used to be silent:
    //
    //   - No note declares it. Every value ties, a stable sort hands back the
    //     scan order, and the list looks sorted.
    //   - The value is not ISO, so `03/01/2026` sorts before `21/07/2025`: by
    //     day, then month, and the year never gets a say.
    //
    // Deliberately not fixed by normalising DD/MM/YYYY: that date is 3 January
    // to half the world and 1 March to the other half. Guessing would trade a
    // visible error for an invisible one.

    private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}")

    sealed interface SortIssue {
        data class CreatedMissing(val missing: Int, val total: Int) : SortIssue
        data class CreatedNotIso(val sample: String) : SortIssue
    }

    /** The creation date a note declares, or empty. */
    fun createdValue(note: Note): String =
        (note.fields["created"] ?: note.fields["criado"])?.firstOrNull()?.trim().orEmpty()

    /** What stops the result being ordered the way the block asked. */
    fun sortIssues(notes: List<Note>, spec: Spec): List<SortIssue> {
        if (spec.sortBy != SortKey.CREATED || notes.isEmpty()) return emptyList()

        val values = notes.map { createdValue(it) }
        val missing = values.count { it.isEmpty() }
        if (missing == values.size) {
            return listOf(SortIssue.CreatedMissing(missing, values.size))
        }

        val issues = mutableListOf<SortIssue>()
        // Partly missing still matters: those notes clump at one end and the
        // list looks ordered
        if (missing > 0) issues += SortIssue.CreatedMissing(missing, values.size)
        values.firstOrNull { it.isNotEmpty() && !ISO_DATE.containsMatchIn(it) }
            ?.let { issues += SortIssue.CreatedNotIso(it) }
        return issues
    }
}
