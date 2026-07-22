package com.openobsidian.android.data

/**
 * `.odt` (LibreOffice / OpenOffice) → Markdown.
 *
 * Um `.odt` é ZIP + XML, como o `.docx`. Quem abre o ZIP é o
 * [OdtConverter]; aqui fica só a parte pura, que recebe o `content.xml` como
 * texto e devolve Markdown.
 *
 * **Por que um parser escrito à mão e não o `XmlPullParser`:** o do Android é
 * um stub que lança em teste unitário — a mesma armadilha do `org.json`. Sem
 * JDK nesta máquina, teste que não roda no CI é teste que não existe, e um
 * conversor de formato alheio é exatamente onde os casos esquecidos moram. O
 * desktop faz igual, pelo mesmo motivo.
 */
object Odt {

    // ── Árvore mínima ─────────────────────────────────────────────────────

    sealed interface Node
    data class Text(val text: String) : Node
    data class Element(
        val tag: String,
        val attrs: Map<String, String>,
        val children: MutableList<Node> = mutableListOf(),
    ) : Node

    private val TAG_RE = Regex(
        """<(/)?([\w:.-]+)((?:[^>"']|"[^"]*"|'[^']*')*?)(/)?>|<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<!\[CDATA\[([\s\S]*?)]]>|<![\s\S]*?>"""
    )
    private val ATTR_RE = Regex("""([\w:.-]+)\s*=\s*"([^"]*)"""")

    private val ENTITIES = mapOf(
        "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&nbsp;" to " ",
    )

    private fun decodeEntities(s: String): String {
        var out = s
        for ((k, v) in ENTITIES) out = out.replace(k, v)
        // Numéricas, e o `&amp;` por último para não ressuscitar entidade nenhuma
        out = Regex("&#(\\d+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        return out.replace("&amp;", "&")
    }

    private fun parseAttrs(raw: String): Map<String, String> =
        ATTR_RE.findAll(raw).associate { it.groupValues[1] to decodeEntities(it.groupValues[2]) }

    /** XML → árvore. Tolera fechamento solto em vez de desistir do arquivo. */
    fun parseXml(xml: String): Element {
        val root = Element("#root", emptyMap())
        val stack = mutableListOf(root)
        var cursor = 0

        for (m in TAG_RE.findAll(xml)) {
            val between = xml.substring(cursor, m.range.first)
            if (between.isNotEmpty()) stack.last().children += Text(decodeEntities(between))
            cursor = m.range.last + 1

            val cdata = m.groups[5]?.value
            if (cdata != null) { stack.last().children += Text(cdata); continue }

            val tag = m.groups[2]?.value ?: continue // comentário, declaração, doctype

            if (m.groups[1] != null) {
                // Fechamento: desempilha até ele; fechamento órfão é ignorado
                for (i in stack.indices.reversed()) {
                    if (i > 0 && stack[i].tag == tag) {
                        while (stack.size > i) stack.removeAt(stack.size - 1)
                        break
                    }
                }
                continue
            }

            val el = Element(tag, parseAttrs(m.groups[3]?.value.orEmpty()))
            stack.last().children += el
            if (m.groups[4] == null) stack += el
        }

        val tail = xml.substring(cursor)
        if (tail.isNotEmpty()) stack.last().children += Text(decodeEntities(tail))
        return root
    }

    private fun Element.find(tag: String): Element? {
        for (c in children) {
            if (c !is Element) continue
            if (c.tag == tag) return c
            c.find(tag)?.let { return it }
        }
        return null
    }

    // ── Estilos ───────────────────────────────────────────────────────────

    data class TextStyle(val bold: Boolean = false, val italic: Boolean = false)

    /** `style:name` → formatação, lida das definições do próprio documento. */
    fun collectTextStyles(root: Element): Map<String, TextStyle> {
        val out = mutableMapOf<String, TextStyle>()
        fun walk(node: Element) {
            for (c in node.children) {
                if (c !is Element) continue
                if (c.tag == "style:style" && c.attrs["style:family"] == "text") {
                    val props = c.find("style:text-properties")
                    val name = c.attrs["style:name"]
                    if (props != null && name != null) {
                        out[name] = TextStyle(
                            bold = props.attrs["fo:font-weight"] == "bold",
                            italic = props.attrs["fo:font-style"] == "italic",
                        )
                    }
                }
                walk(c)
            }
        }
        walk(root)
        return out
    }

    /** Estilos de lista cujo primeiro nível é numerado. */
    fun collectOrderedLists(root: Element): Set<String> {
        val out = mutableSetOf<String>()
        fun walk(node: Element) {
            for (c in node.children) {
                if (c !is Element) continue
                if (c.tag == "text:list-style") {
                    val first = c.children.filterIsInstance<Element>().firstOrNull()
                    val name = c.attrs["style:name"]
                    if (first?.tag == "text:list-level-style-number" && name != null) out += name
                }
                walk(c)
            }
        }
        walk(root)
        return out
    }

    // ── Renderização ──────────────────────────────────────────────────────

    private val SKIPPED = setOf(
        "text:sequence-decls", "text:soft-page-break", "office:forms",
        "text:tracked-changes", "office:annotation", "draw:frame",
    )

    private fun renderInline(nodes: List<Node>, styles: Map<String, TextStyle>): String =
        nodes.joinToString("") { node ->
            when {
                node is Text -> node.text
                node is Element && node.tag == "text:s" -> " "
                node is Element && node.tag == "text:tab" -> " "
                node is Element && node.tag == "text:line-break" -> "  \n"
                node is Element && node.tag == "text:a" -> {
                    val href = node.attrs["xlink:href"].orEmpty()
                    val label = renderInline(node.children, styles)
                    if (href.isBlank()) label else "[$label]($href)"
                }
                node is Element && node.tag == "text:span" -> {
                    val st = styles[node.attrs["text:style-name"]]
                    val inner = renderInline(node.children, styles)
                    when {
                        inner.isBlank() -> inner
                        st == null -> inner
                        st.bold && st.italic -> "***$inner***"
                        st.bold -> "**$inner**"
                        st.italic -> "*$inner*"
                        else -> inner
                    }
                }
                node is Element && node.tag in SKIPPED -> ""
                node is Element -> renderInline(node.children, styles)
                else -> ""
            }
        }

    private fun renderBlocks(
        nodes: List<Node>,
        styles: Map<String, TextStyle>,
        ordered: Set<String>,
        depth: Int = 0,
        listOrdered: Boolean = false,
        counter: IntArray = intArrayOf(0),
    ): String {
        val sb = StringBuilder()
        for (node in nodes) {
            if (node !is Element || node.tag in SKIPPED) continue
            when (node.tag) {
                "text:h" -> {
                    val level = node.attrs["text:outline-level"]?.toIntOrNull()?.coerceIn(1, 6) ?: 1
                    val text = renderInline(node.children, styles).trim()
                    if (text.isNotEmpty()) sb.append("${"#".repeat(level)} $text\n\n")
                }
                "text:p" -> {
                    val text = renderInline(node.children, styles).trim()
                    if (text.isNotEmpty()) sb.append("$text\n\n")
                }
                "text:list" -> {
                    val isOrdered = node.attrs["text:style-name"] in ordered
                    sb.append(
                        renderBlocks(node.children, styles, ordered, depth, isOrdered, intArrayOf(0))
                    )
                    if (depth == 0) sb.append("\n")
                }
                "text:list-item" -> {
                    val indent = "  ".repeat(depth)
                    counter[0]++
                    val marker = if (listOrdered) "${counter[0]}. " else "- "
                    // O primeiro parágrafo vira o texto do item; listas dentro
                    // do item descem um nível
                    val paras = node.children.filterIsInstance<Element>()
                    val first = paras.firstOrNull { it.tag == "text:p" || it.tag == "text:h" }
                    val text = first?.let { renderInline(it.children, styles).trim() }.orEmpty()
                    if (text.isNotEmpty()) sb.append("$indent$marker$text\n")
                    for (child in paras.filter { it.tag == "text:list" }) {
                        sb.append(
                            renderBlocks(
                                listOf(child), styles, ordered, depth + 1, listOrdered, intArrayOf(0)
                            )
                        )
                    }
                }
                "table:table" -> sb.append(renderTable(node, styles))
                else -> sb.append(renderBlocks(node.children, styles, ordered, depth, listOrdered, counter))
            }
        }
        return sb.toString()
    }

    private fun renderTable(table: Element, styles: Map<String, TextStyle>): String {
        val rows = mutableListOf<List<String>>()
        fun collectRows(node: Element) {
            for (c in node.children) {
                if (c !is Element) continue
                if (c.tag == "table:table-row") {
                    val cells = c.children.filterIsInstance<Element>()
                        .filter { it.tag == "table:table-cell" }
                        .map { cell ->
                            cell.children.filterIsInstance<Element>()
                                .joinToString(" ") { renderInline(it.children, styles).trim() }
                                .trim()
                                // `|` dentro da célula partiria a tabela em duas colunas
                                .replace("|", "\\|")
                        }
                    if (cells.isNotEmpty()) rows += cells
                } else {
                    collectRows(c)
                }
            }
        }
        collectRows(table)
        if (rows.isEmpty()) return ""

        val width = rows.maxOf { it.size }
        val sb = StringBuilder()
        rows.forEachIndexed { i, row ->
            val padded = row + List(width - row.size) { "" }
            sb.append("| ${padded.joinToString(" | ")} |\n")
            // Markdown exige a linha separadora logo após o cabeçalho; sem ela
            // a tabela inteira renderiza como texto solto
            if (i == 0) sb.append("|${" --- |".repeat(width)}\n")
        }
        sb.append("\n")
        return sb.toString()
    }

    /** `content.xml` → Markdown. */
    fun toMarkdown(contentXml: String): String {
        val root = parseXml(contentXml)
        val styles = collectTextStyles(root)
        val ordered = collectOrderedLists(root)
        val body = root.find("office:text") ?: root.find("office:body") ?: return ""
        return renderBlocks(body.children, styles, ordered)
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
