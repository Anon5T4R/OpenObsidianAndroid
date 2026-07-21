package com.openobsidian.android.data

/**
 * Rewriting `[[wikilinks]]` when a note is renamed.
 *
 * Renaming used to be a bare `DocumentsContract.renameDocument`: the file got
 * its new name and every link pointing at it broke, with nothing said. In a
 * vault of a few hundred notes that is invisible until you click a link months
 * later. Ported from the desktop app, where the same fix landed in v0.9.0.
 *
 * Pure on purpose — no Android imports, so it is unit-testable on the JVM.
 */
object LinkRewrite {

    /**
     * Fenced blocks and inline code. A `[[Nota]]` inside ``` is documentation,
     * not a link, and rewriting it would silently corrupt an example.
     */
    private val CODE_RE = Regex("(```[\\s\\S]*?```|~~~[\\s\\S]*?~~~|`[^`\\n]*`)")

    /** group 1: target · group 2: `#anchor` · group 3: `|alias` (both optional) */
    private val LINK_RE = Regex("\\[\\[([^\\]|#]+)(#[^\\]|]*)?(\\|[^\\]]*)?]]")

    private val MD_SUFFIX = Regex("\\.md$", RegexOption.IGNORE_CASE)

    /** `[[Pasta/Nota]]` targets the note `Nota` — only the last segment counts. */
    private fun targetMatches(target: String, name: String): Boolean {
        val clean = MD_SUFFIX.replace(target.trim(), "")
        val last = clean.substringAfterLast('/')
        return last.equals(name, ignoreCase = true)
    }

    /** Swaps the last path segment for [newName], keeping any folder prefix. */
    private fun replaceLastSegment(target: String, newName: String): String {
        val trimmed = MD_SUFFIX.replace(target, "")
        val parts = trimmed.split('/').toMutableList()
        parts[parts.lastIndex] = newName
        return parts.joinToString("/")
    }

    /** Applies [fn] to every stretch of text that is not code. */
    private fun mapOutsideCode(content: String, fn: (String) -> String): String {
        val out = StringBuilder()
        var last = 0
        for (m in CODE_RE.findAll(content)) {
            out.append(fn(content.substring(last, m.range.first)))
            out.append(m.value)
            last = m.range.last + 1
        }
        out.append(fn(content.substring(last)))
        return out.toString()
    }

    data class Result(val content: String, val count: Int)

    /** Rewrites every `[[oldName]]` — alias, anchor and folder variants included. */
    fun rewriteLinks(content: String, oldName: String, newName: String): Result {
        var count = 0
        val out = mapOutsideCode(content) { chunk ->
            LINK_RE.replace(chunk) { m ->
                val target = m.groupValues[1]
                val anchor = m.groupValues[2]
                val alias = m.groupValues[3]
                if (!targetMatches(target, oldName)) {
                    m.value
                } else {
                    count++
                    // Keep the spacing the author wrote inside the brackets
                    val lead = target.takeWhile { it.isWhitespace() }
                    val trail = target.takeLastWhile { it.isWhitespace() }
                    val body = replaceLastSegment(target.trim(), newName)
                    "[[$lead$body$trail$anchor$alias]]"
                }
            }
        }
        return Result(out, count)
    }

    /** How many links in [content] point at the note [name]. */
    fun countRefs(content: String, name: String): Int =
        rewriteLinks(content, name, name).count
}
