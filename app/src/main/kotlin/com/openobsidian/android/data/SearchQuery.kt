package com.openobsidian.android.data

/**
 * The search grammar: `tag:`, `path:`, `file:`, "exact phrase" and -exclusion.
 *
 * Search used to be a plain substring scan, which in a vault of a few hundred
 * notes returns everything and therefore nothing. Ported from the desktop
 * (v0.9.0), minus the regex toggle — typing a regex on a phone keyboard is not
 * a thing anyone does.
 *
 * Pure on purpose — unit-testable on the JVM.
 */
object SearchQuery {

    data class Term(
        val text: String,
        val field: String?,   // tag / path / file, or null for free text
        val exclude: Boolean,
        val phrase: Boolean,
    )

    data class Parsed(val terms: List<Term>) {
        val isEmpty: Boolean get() = terms.isEmpty()
    }

    // A term is either "a quoted phrase", -excluded, field:value, or a word.
    private val TOKEN_RE = Regex("""(-?)(?:(\w+):)?(?:"([^"]*)"|(\S+))""")

    fun parse(query: String): Parsed {
        val terms = mutableListOf<Term>()
        for (m in TOKEN_RE.findAll(query.trim())) {
            val exclude = m.groupValues[1] == "-"
            val field = m.groupValues[2].lowercase().ifEmpty { null }
            val quoted = m.groups[3] != null
            val text = (if (quoted) m.groupValues[3] else m.groupValues[4]).trim()
            if (text.isEmpty()) continue
            // `note:` and friends are not fields we know; treat them as text so
            // a stray colon does not silently drop the term
            val known = field in setOf("tag", "path", "file", null)
            terms += if (known) Term(text, field, exclude, quoted)
            else Term("${field}:$text", null, exclude, quoted)
        }
        return Parsed(terms)
    }

    /** Everything the matcher needs about one note. */
    data class Note(
        val name: String,
        val relativePath: String,
        val content: String,
        val tags: List<String>,
    )

    private fun matchesTerm(note: Note, term: Term): Boolean {
        val needle = term.text.lowercase()
        return when (term.field) {
            // A parent tag finds its children: tag:sistema matches sistema/cardio
            "tag" -> note.tags.any { it == needle || it.startsWith("$needle/") }
            "path" -> note.relativePath.lowercase().contains(needle)
            "file" -> note.name.lowercase().contains(needle)
            else -> note.name.lowercase().contains(needle) ||
                note.content.lowercase().contains(needle)
        }
    }

    /** Whether a note satisfies every term (exclusions included). */
    fun matches(note: Note, parsed: Parsed): Boolean {
        if (parsed.isEmpty) return false
        for (term in parsed.terms) {
            val hit = matchesTerm(note, term)
            if (term.exclude && hit) return false
            if (!term.exclude && !hit) return false
        }
        return true
    }

    /**
     * Relevance. An exact title beats everything; body hits count with
     * diminishing returns, because counting each mention linearly let a long
     * note beat a note that is actually *about* the subject.
     */
    fun score(note: Note, parsed: Parsed): Int {
        var total = 0
        for (term in parsed.terms) {
            if (term.exclude) continue
            val needle = term.text.lowercase()
            val name = note.name.lowercase()
            if (name == needle) total += 100
            else if (name.contains(needle)) total += 40

            val body = note.content.lowercase()
            var hits = 0
            var from = 0
            while (true) {
                val i = body.indexOf(needle, from)
                if (i < 0) break
                hits++
                from = i + needle.length
                if (hits > 64) break
            }
            if (hits > 0) {
                total += (Math.log(1.0 + hits) / Math.log(2.0) * 6).toInt()
            }
        }
        return total
    }
}
