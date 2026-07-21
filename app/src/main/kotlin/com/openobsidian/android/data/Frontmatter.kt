package com.openobsidian.android.data

/**
 * The YAML header of a note, and the tags of a vault.
 *
 * Until now a leading `---` block was rendered as visible text in the preview,
 * and tags did not exist at all on Android. Both landed on the desktop in
 * v0.9.0; this is the same behaviour, minus a YAML library — a note header is
 * `key: value` and simple lists, and pulling in snakeyaml for that would add
 * weight to the APK for no gain.
 *
 * Pure on purpose — unit-testable on the JVM.
 */
object Frontmatter {

    data class Parsed(
        /** Fields in the order they were written, or empty when there is no header. */
        val fields: List<Pair<String, List<String>>>,
        /** The note without its header. */
        val body: String,
    ) {
        fun valuesOf(key: String): List<String> =
            fields.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second ?: emptyList()

        val tags: List<String> get() = valuesOf("tags")
        val aliases: List<String> get() = valuesOf("aliases") + valuesOf("alias")
    }

    private val FENCE = Regex("^---[ \\t]*\\r?\\n")

    /**
     * Splits a note into its header fields and its body.
     * A header only counts when the note *opens* with `---`, which is what
     * Obsidian does — a `---` further down is a horizontal rule.
     */
    fun parse(markdown: String): Parsed {
        if (!FENCE.containsMatchIn(markdown)) return Parsed(emptyList(), markdown)

        val lines = markdown.lines()
        val closing = lines.drop(1).indexOfFirst { it.trimEnd() == "---" }
        // No closing fence: it is not a header, do not eat the whole note
        if (closing < 0) return Parsed(emptyList(), markdown)

        val headerLines = lines.subList(1, closing + 1)
        val body = lines.drop(closing + 2).joinToString("\n")

        val fields = mutableListOf<Pair<String, List<String>>>()
        var currentKey: String? = null
        val currentList = mutableListOf<String>()

        fun flush() {
            currentKey?.let { fields += it to currentList.toList() }
            currentKey = null
            currentList.clear()
        }

        for (raw in headerLines) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val listItem = Regex("^\\s*-\\s+(.*)$").find(line)
            if (listItem != null && currentKey != null) {
                currentList += unquote(listItem.groupValues[1])
                continue
            }
            val pair = Regex("^([^:]+):\\s*(.*)$").find(line) ?: continue
            flush()
            currentKey = pair.groupValues[1].trim()
            val value = pair.groupValues[2].trim()
            when {
                value.isEmpty() -> Unit // values come on the following `- ` lines
                value.startsWith("[") && value.endsWith("]") ->
                    currentList += value.removeSurrounding("[", "]")
                        .split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
                else -> currentList += unquote(value)
            }
        }
        flush()
        return Parsed(fields, body)
    }

    private fun unquote(s: String): String =
        s.trim().removeSurrounding("\"").removeSurrounding("'").trim()

    // ── Tags ──────────────────────────────────────────────────────────────

    /**
     * `#tag` occurrences. Accents and `#nested/tags` survive; a purely numeric
     * tag and a hex colour do not, because `#1` and `#ff0000` are prose and CSS,
     * not tags. Mirrors the desktop rule exactly — what renders as a tag and
     * what gets indexed as one have to be the same thing.
     */
    private val TAG_RE = Regex("(^|[\\s(>])#([\\p{L}\\p{N}_/-]+)")
    private val NUMERIC_RE = Regex("^\\d+$")
    private val HEX_RE = Regex("^(?=.*\\d)[0-9a-fA-F]{3}$|^(?=.*\\d)[0-9a-fA-F]{6}$")
    private val MD_CODE_RE = Regex("(```[\\s\\S]*?```|~~~[\\s\\S]*?~~~|`[^`\\n]*`)")

    /** Inline `#tags` plus the `tags:` of the header, all lowercase and deduped. */
    fun extractTags(markdown: String): List<String> {
        val parsed = parse(markdown)
        val found = LinkedHashSet<String>()

        // Code is skipped: a `#include` in a snippet is not a tag
        var last = 0
        val plain = StringBuilder()
        for (m in MD_CODE_RE.findAll(parsed.body)) {
            plain.append(parsed.body.substring(last, m.range.first)).append('\n')
            last = m.range.last + 1
        }
        plain.append(parsed.body.substring(last))

        for (m in TAG_RE.findAll(plain)) {
            val tag = m.groupValues[2].trimEnd('/', '-').lowercase()
            if (tag.isEmpty() || NUMERIC_RE.matches(tag) || HEX_RE.matches(tag)) continue
            found += tag
        }
        for (t in parsed.tags) {
            val tag = t.removePrefix("#").trimEnd('/', '-').lowercase()
            if (tag.isNotEmpty()) found += tag
        }
        return found.toList()
    }

    /** `#sistema/cardio` also counts as `#sistema`, so a parent finds its children. */
    fun expandHierarchy(tags: List<String>): List<String> {
        val all = LinkedHashSet<String>()
        for (tag in tags) {
            val parts = tag.split('/')
            for (i in 1..parts.size) all += parts.take(i).joinToString("/")
        }
        return all.toList()
    }
}
