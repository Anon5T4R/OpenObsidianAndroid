package com.openobsidian.android.data

/**
 * Tags offered after `#` in the editor.
 *
 * The vault already knows every tag it has and how many notes carry it — that
 * index feeds the search and the diagnostics. It was never offered at the moment
 * it is needed: writing the tag line under a heading, or filling `tag:` inside a
 * ```query block. So tags got typed from memory, and a typo does not fail
 * loudly — it silently creates a new tag with one note in it, and the note
 * vanishes from the index that should have listed it.
 *
 * On a phone this matters more than on a desktop: the keyboard is small, the
 * autocorrect is hostile to `#sis-cardio`, and there is no muscle memory for a
 * vocabulary of a hundred and fifty tags.
 */
object TagComplete {

    data class Option(val tag: String, val count: Int)

    /** Where the tag being typed starts in the line, and what has been typed. */
    data class Match(val from: Int, val query: String)

    // At least one tag character after the `#`. This is what keeps headings
    // safe: a heading is `#` followed by a space, so it can never match, and
    // `##` cannot either because `#` is not a tag character. A menu opening on
    // every `# Título` would have made the feature worse than not having it.
    private val TAG_AT_CURSOR = Regex("#([\\p{L}\\p{N}_/-]+)$")

    /** The vault's tag index inverted into tag → how many notes carry it. */
    fun countTags(tagsByPath: Map<String, List<String>>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (tags in tagsByPath.values) {
            for (tag in tags.distinct()) counts[tag] = (counts[tag] ?: 0) + 1
        }
        return counts
    }

    /**
     * Tags matching [query], best first.
     *
     * A tag that *starts* with what was typed always beats one that merely
     * contains it, because that is what typing a prefix means. Within each group
     * the most used wins: in a vault of a hundred and fifty tags the
     * alphabetical tail is noise.
     */
    fun rank(counts: Map<String, Int>, query: String, limit: Int = 40): List<Option> {
        val q = query.trim().lowercase()

        val prefix = mutableListOf<Option>()
        val contains = mutableListOf<Option>()
        for ((tag, count) in counts) {
            val lower = tag.lowercase()
            val option = Option(tag, count)
            when {
                q.isEmpty() || lower.startsWith(q) -> prefix += option
                lower.contains(q) -> contains += option
            }
        }
        val byUse = compareByDescending<Option> { it.count }.thenBy { it.tag }
        return (prefix.sortedWith(byUse) + contains.sortedWith(byUse)).take(limit)
    }

    /**
     * The tag being typed at the end of [lineUpToCursor], if any.
     *
     * Takes the line rather than the editor state so the rules that decide
     * whether a menu appears — the part that would annoy someone writing a
     * heading — can be tested without a running editor.
     */
    fun matchAtCursor(lineUpToCursor: String): Match? {
        val m = TAG_AT_CURSOR.find(lineUpToCursor) ?: return null
        val from = m.range.first
        // A tag begins a word, same rule the indexer and the renderer share:
        // `nota#ancora` and a URL fragment are not tags.
        if (from > 0 && !startsTag(lineUpToCursor[from - 1])) return null
        return Match(from, m.groupValues[1])
    }

    private fun startsTag(before: Char): Boolean =
        before.isWhitespace() || before == '(' || before == '>'
}
