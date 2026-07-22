package com.openobsidian.android.data

/**
 * Transclusão — `![[Nota]]` e `![[Nota#Seção]]`.
 *
 * Sem isso, conteúdo usado em dez notas é copiado dez vezes e as cópias vão
 * divergindo. Trabalho de string puro, para poder ser testado sem emulador.
 *
 * **Diferença deliberada em relação ao desktop:** lá o conteúdo embutido é
 * envolvido em `<div class="embed">`, porque o remark reprocessa o miolo como
 * Markdown. O Markwon não faz isso — HTML injetado ficaria com o Markdown de
 * dentro cru na tela. Aqui o conteúdo vira **citação** (`> `), que é Markdown
 * de verdade, o Markwon renderiza nativamente e ainda dá o limite visual que o
 * `div` dava. E continua legível em qualquer outro editor.
 */
object Embeds {

    private val EMBED = Regex("""!\[\[([^\]|\n]+)(?:\|([^\]\n]+))?]]""")

    /** Três níveis bastam para composição real e evitam pilha profunda no celular. */
    private const val MAX_DEPTH = 3

    /**
     * O trecho de [md] sob o título cujo texto é [heading], até o próximo
     * título de nível igual ou superior. Vazio quando não existe.
     */
    fun extractSection(md: String, heading: String): String {
        val lines = md.split("\n")
        val wanted = heading.trim().lowercase()
        val headingRe = Regex("""^(#{1,6})\s+(.*)$""")

        var start = -1
        var level = 0
        for (i in lines.indices) {
            val m = headingRe.find(lines[i]) ?: continue
            if (m.groupValues[2].trim().lowercase() == wanted) {
                start = i
                level = m.groupValues[1].length
                break
            }
        }
        if (start < 0) return ""

        val out = mutableListOf(lines[start])
        for (i in start + 1 until lines.size) {
            val m = headingRe.find(lines[i])
            // Título de nível igual ou mais alto encerra a seção
            if (m != null && m.groupValues[1].length <= level) break
            out += lines[i]
        }
        return out.joinToString("\n").trim()
    }

    /**
     * Troca cada `![[…]]` pelo conteúdo apontado, como citação.
     *
     * [resolve] devolve o Markdown da nota, ou `null` quando não há nota atrás
     * do alvo — nesse caso o `![[…]]` fica literal na tela, em vez de sumir. Um
     * embed que não resolve tem de ser visível: sumir calado esconderia que
     * falta uma nota.
     *
     * [seen] quebra ciclo (A embute B que embute A) e [MAX_DEPTH] limita o
     * aninhamento. Sem os dois, duas notas se referindo uma à outra travariam
     * a renderização.
     */
    fun expand(
        md: String,
        resolve: (String) -> String?,
        depth: Int = 0,
        seen: Set<String> = emptySet(),
    ): String {
        if (depth >= MAX_DEPTH) return md

        return EMBED.replace(md) { m ->
            val raw = m.groupValues[1]
            val key = raw.trim().lowercase()
            if (key in seen) return@replace m.value

            val target = LinkResolver.parseTarget(raw)
            val note = resolve(target.name) ?: return@replace m.value

            // A nota embutida não está mais no topo do documento, então o
            // frontmatter dela viraria título visível — sai aqui.
            val source = Frontmatter.parse(note).body
            val anchor = target.anchor
            val body = if (anchor != null && !anchor.startsWith("^")) {
                extractSection(source, anchor)
            } else {
                source
            }
            if (body.isBlank()) return@replace m.value

            val inner = expand(body, resolve, depth + 1, seen + key)
            quote(target.name, inner)
        }
    }

    /** Bloco de citação com a origem no topo, para saber de onde o texto veio. */
    private fun quote(source: String, body: String): String {
        val quoted = body.trim().split("\n").joinToString("\n") { line ->
            // Linha vazia vira `>` sozinho: sem isso a citação quebra em duas
            if (line.isBlank()) ">" else "> $line"
        }
        return "\n\n> **[[$source]]**\n>\n$quoted\n\n"
    }
}
