package com.openobsidian.android.data

/**
 * Reading an `.epub` without a library.
 *
 * An EPUB is a ZIP with an XML manifest. Pulling in a reader library for
 * something the platform can already unzip and render (WebView) would add a
 * dependency and its own bugs; what is genuinely fiddly is finding the spine,
 * and that is exactly the part that can be tested here.
 *
 * Pure on purpose — the parsing takes XML strings, not files.
 */
object Epub {

    /** One chapter: the path inside the ZIP, and its title when the book gives one. */
    data class Chapter(val href: String, val title: String)

    /** `META-INF/container.xml` says where the real manifest lives. */
    fun opfPathFrom(containerXml: String): String? =
        Regex("""full-path\s*=\s*["']([^"']+)["']""").find(containerXml)?.groupValues?.get(1)

    /**
     * The reading order, from the OPF.
     *
     * The spine holds idrefs; the manifest maps each id to a file. A book whose
     * spine references a missing id is not rare, so those are skipped rather
     * than aborting the whole book.
     */
    fun chaptersFrom(opfXml: String, titlesByHref: Map<String, String> = emptyMap()): List<Chapter> {
        val manifest = HashMap<String, String>()
        for (m in Regex("""<item\b[^>]*>""").findAll(opfXml)) {
            val tag = m.value
            val id = Regex("""\bid\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.get(1) ?: continue
            val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""").find(tag)?.groupValues?.get(1) ?: continue
            manifest[id] = href
        }
        val out = mutableListOf<Chapter>()
        for (m in Regex("""<itemref\b[^>]*>""").findAll(opfXml)) {
            val idref = Regex("""\bidref\s*=\s*["']([^"']+)["']""").find(m.value)?.groupValues?.get(1) ?: continue
            val href = manifest[idref] ?: continue
            out += Chapter(href, titlesByHref[href] ?: prettyName(href))
        }
        return out
    }

    /** The book title, for the toolbar. */
    fun titleFrom(opfXml: String): String? =
        Regex("""<dc:title[^>]*>([^<]*)</dc:title>""").find(opfXml)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** Chapter titles from the table of contents, when there is one. */
    fun titlesFromToc(ncxOrNavXml: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        // EPUB 2 (.ncx): <navPoint><navLabel><text>T</text></navLabel><content src="h"/>
        for (m in Regex("""<navPoint\b[\s\S]*?</navPoint>""").findAll(ncxOrNavXml)) {
            val label = Regex("""<text[^>]*>([^<]*)</text>""").find(m.value)?.groupValues?.get(1)?.trim()
            val src = Regex("""<content\b[^>]*src\s*=\s*["']([^"'#]+)""").find(m.value)?.groupValues?.get(1)
            if (!label.isNullOrEmpty() && src != null) out.putIfAbsent(src, label)
        }
        // EPUB 3 (nav.xhtml): <a href="h">T</a>
        for (m in Regex("""<a\b[^>]*href\s*=\s*["']([^"'#]+)[^>]*>([^<]*)</a>""").findAll(ncxOrNavXml)) {
            val href = m.groupValues[1]
            val label = m.groupValues[2].trim()
            if (label.isNotEmpty()) out.putIfAbsent(href, label)
        }
        return out
    }

    /** A readable fallback when the book names no chapter. */
    private fun prettyName(href: String): String =
        href.substringAfterLast('/')
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifEmpty { href }

    /**
     * Resolves a href from the OPF against the folder the OPF lives in.
     * Paths in an EPUB are relative to the manifest, not to the ZIP root, and
     * getting this wrong is why a book renders as a wall of missing chapters.
     */
    fun resolve(opfPath: String, href: String): String {
        val base = opfPath.substringBeforeLast('/', "")
        if (base.isEmpty()) return href.trimStart('/')
        val parts = ArrayDeque(base.split('/'))
        for (segment in href.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }
}
