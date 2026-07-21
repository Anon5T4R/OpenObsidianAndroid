package com.openobsidian.android.data

/**
 * Resolving a `[[wikilink]]` target to a note in the vault.
 *
 * Until now a link only worked when the target matched a file name exactly:
 * `[[Nota#Seção]]` and `[[Pasta/Nota]]` did nothing at all when tapped, and an
 * alias declared in the frontmatter was invisible. Ported from the desktop,
 * where the same resolver landed in v0.9.0.
 *
 * Works on [Ref], not on `Node.File`, so it carries no Android types: `Uri`
 * does not exist in a JVM unit test, and logic this delicate has to be testable
 * without an emulator.
 */
object LinkResolver {

    /** The little a note needs to be resolvable. `key` is opaque — the caller's handle. */
    data class Ref(val name: String, val relativePath: String, val key: String)

    /** A target split into the note and the `#anchor` part. */
    data class Target(val name: String, val anchor: String?)

    /** Splits `Nota#Seção` / `Pasta/Nota#^bloco` into its parts. */
    fun parseTarget(raw: String): Target {
        val i = raw.indexOf('#')
        if (i < 0) return Target(raw.trim(), null)
        val anchor = raw.substring(i + 1).trim()
        return Target(raw.substring(0, i).trim(), anchor.ifEmpty { null })
    }

    private val MD_SUFFIX = Regex("\\.md$", RegexOption.IGNORE_CASE)

    private fun norm(s: String): String =
        MD_SUFFIX.replace(s.replace('\\', '/'), "").lowercase()

    /** How many leading path segments two notes share — "closest note wins". */
    private fun sharedDepth(a: String, b: String): Int {
        val pa = norm(a).split('/').dropLast(1)
        val pb = norm(b).split('/').dropLast(1)
        var i = 0
        while (i < pa.size && i < pb.size && pa[i] == pb[i]) i++
        return i
    }

    /**
     * The note a wikilink points at.
     *
     * A target containing `/` is matched against the relative path, otherwise
     * against the file name. When several notes share a name, the one closest
     * to the linking note wins — otherwise the match would depend on the order
     * the tree happened to be walked, which no reader can predict.
     *
     * A real file name always beats an alias.
     */
    fun resolve(
        notes: List<Ref>,
        target: String,
        fromRelativePath: String? = null,
        aliases: Map<String, String> = emptyMap(),
    ): Ref? {
        val wanted = norm(target)
        if (wanted.isEmpty()) return null

        val matches = if (wanted.contains('/')) {
            notes.filter { norm(it.relativePath) == wanted || norm(it.relativePath).endsWith("/$wanted") }
        } else {
            notes.filter { norm(it.name) == wanted }
        }

        if (matches.isEmpty()) {
            val path = aliases[wanted] ?: return null
            return notes.firstOrNull { it.relativePath == path }
        }
        if (matches.size == 1 || fromRelativePath == null) return matches.first()

        var best = matches.first()
        var bestDepth = -1
        for (m in matches) {
            val d = sharedDepth(m.relativePath, fromRelativePath)
            if (d > bestDepth) { best = m; bestDepth = d }
        }
        return best
    }

    /**
     * alias (lowercase) → relative path of the note declaring it.
     *
     * In medicine almost everything has three names (IAM / infarto / síndrome
     * coronariana). Without aliases either the file name becomes a list of
     * synonyms, or the note is unreachable by the other names. The first
     * declaration wins, so a duplicated alias stays predictable.
     */
    fun buildAliasIndex(frontmatterByPath: Map<String, Frontmatter.Parsed>): Map<String, String> {
        val index = LinkedHashMap<String, String>()
        for ((path, parsed) in frontmatterByPath) {
            for (alias in parsed.aliases) {
                val key = alias.trim().lowercase()
                if (key.isNotEmpty() && !index.containsKey(key)) index[key] = path
            }
        }
        return index
    }

    private val LINK_RE = Regex("\\[\\[([^\\]|\\n]+?)(?:\\|[^\\]\\n]+?)?]]")
    private val CODE_RE = Regex("(```[\\s\\S]*?```|~~~[\\s\\S]*?~~~|`[^`\\n]*`)")

    /** Every `[[target]]` in a note, code excluded and anchors stripped. */
    fun linksIn(content: String): List<String> {
        var last = 0
        val plain = StringBuilder()
        for (m in CODE_RE.findAll(content)) {
            plain.append(content, last, m.range.first)
            last = m.range.last + 1
        }
        plain.append(content, last, content.length)

        val out = mutableListOf<String>()
        for (m in LINK_RE.findAll(plain)) {
            val name = parseTarget(m.groupValues[1]).name
            // `[[#Seção]]` points inside the note it lives in, not at another one
            if (name.isNotEmpty()) out += name
        }
        return out
    }

    data class Broken(val target: String, val sources: List<String>)

    /** Links pointing at notes that do not exist, and who points at them. */
    fun brokenLinks(
        notes: List<Ref>,
        contentsByKey: Map<String, String>,
        aliases: Map<String, String> = emptyMap(),
    ): List<Broken> {
        val bySource = LinkedHashMap<String, MutableList<String>>()
        for (note in notes) {
            val text = contentsByKey[note.key] ?: continue
            for (target in linksIn(text)) {
                if (resolve(notes, target, note.relativePath, aliases) != null) continue
                bySource.getOrPut(target) { mutableListOf() }.add(note.name)
            }
        }
        return bySource.map { (target, sources) -> Broken(target, sources.distinct()) }
            .sortedBy { it.target.lowercase() }
    }

    /** Notes nothing links to — the ones that fall out of the vault's fabric. */
    fun orphanNotes(notes: List<Ref>, contentsByKey: Map<String, String>): List<Ref> {
        val linked = HashSet<String>()
        for (note in notes) {
            val text = contentsByKey[note.key] ?: continue
            for (target in linksIn(text)) {
                resolve(notes, target, note.relativePath)?.let { linked += it.key }
            }
        }
        return notes.filter { it.key !in linked }.sortedBy { it.name.lowercase() }
    }

    /** Notes sharing a name — the reason a `[[link]]` can be ambiguous. */
    fun duplicateNames(notes: List<Ref>): List<Pair<String, List<Ref>>> =
        notes.groupBy { norm(it.name) }
            .filter { it.value.size > 1 }
            .map { it.key to it.value }
            .sortedBy { it.first }
}
