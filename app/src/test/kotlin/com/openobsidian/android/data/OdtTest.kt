package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OdtTest {

    private fun doc(styles: String = "", body: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <office:document-content>
          <office:automatic-styles>$styles</office:automatic-styles>
          <office:body><office:text>$body</office:text></office:body>
        </office:document-content>
    """.trimIndent()

    // ── parseXml ──────────────────────────────────────────────────────────

    @Test
    fun `le atributos e texto`() {
        val root = Odt.parseXml("""<a x="1">oi</a>""")
        val el = root.children.filterIsInstance<Odt.Element>().first()
        assertEquals("a", el.tag)
        assertEquals("1", el.attrs["x"])
        assertEquals("oi", (el.children.first() as Odt.Text).text)
    }

    @Test
    fun `tag que se fecha sozinha nao engole o resto`() {
        val root = Odt.parseXml("""<p><br/>depois</p>""")
        val p = root.children.filterIsInstance<Odt.Element>().first()
        assertEquals(2, p.children.size)
    }

    @Test
    fun `fechamento orfao nao derruba o documento`() {
        // Arquivo alheio, gerado por outra ferramenta: melhor ler torto que desistir
        val root = Odt.parseXml("""<a>um</b>dois</a>""")
        assertTrue(root.children.isNotEmpty())
    }

    @Test
    fun `decodifica entidades, e o amp por ultimo`() {
        // `&amp;lt;` tem de sobrar como `&lt;` literal, não virar `<`
        val root = Odt.parseXml("<p>a &lt; b &amp; c &amp;lt; d</p>")
        val text = (root.children.filterIsInstance<Odt.Element>().first().children.first() as Odt.Text).text
        assertEquals("a < b & c &lt; d", text)
    }

    @Test
    fun `comentario e declaracao sao ignorados`() {
        val root = Odt.parseXml("""<?xml version="1.0"?><!-- nota --><p>x</p>""")
        assertEquals(1, root.children.filterIsInstance<Odt.Element>().size)
    }

    // ── toMarkdown ────────────────────────────────────────────────────────

    @Test
    fun `titulo vira cerquilha do nivel certo`() {
        val md = Odt.toMarkdown(doc(body = """<text:h text:outline-level="2">Diagnóstico</text:h>"""))
        assertEquals("## Diagnóstico", md)
    }

    @Test
    fun `paragrafos ficam separados por linha em branco`() {
        val md = Odt.toMarkdown(doc(body = "<text:p>um</text:p><text:p>dois</text:p>"))
        assertEquals("um\n\ndois", md)
    }

    @Test
    fun `paragrafo vazio nao vira linha solta`() {
        val md = Odt.toMarkdown(doc(body = "<text:p>um</text:p><text:p/><text:p>dois</text:p>"))
        assertEquals("um\n\ndois", md)
    }

    @Test
    fun `negrito e italico saem do estilo declarado no documento`() {
        val styles = """
            <style:style style:name="T1" style:family="text">
              <style:text-properties fo:font-weight="bold"/>
            </style:style>
            <style:style style:name="T2" style:family="text">
              <style:text-properties fo:font-style="italic"/>
            </style:style>
        """.trimIndent()
        val md = Odt.toMarkdown(
            doc(styles, """<text:p>a <text:span text:style-name="T1">forte</text:span> e <text:span text:style-name="T2">torto</text:span></text:p>""")
        )
        assertEquals("a **forte** e *torto*", md)
    }

    @Test
    fun `link vira markdown`() {
        val md = Odt.toMarkdown(
            doc(body = """<text:p>ver <text:a xlink:href="https://x.com">aqui</text:a></text:p>""")
        )
        assertEquals("ver [aqui](https://x.com)", md)
    }

    @Test
    fun `lista com marcador`() {
        val md = Odt.toMarkdown(
            doc(body = "<text:list><text:list-item><text:p>um</text:p></text:list-item><text:list-item><text:p>dois</text:p></text:list-item></text:list>")
        )
        assertEquals("- um\n- dois", md)
    }

    @Test
    fun `lista numerada quando o estilo diz que e numerada`() {
        val styles = """<text:list-style style:name="L1"><text:list-level-style-number/></text:list-style>"""
        val md = Odt.toMarkdown(
            doc(styles, """<text:list text:style-name="L1"><text:list-item><text:p>um</text:p></text:list-item><text:list-item><text:p>dois</text:p></text:list-item></text:list>""")
        )
        assertEquals("1. um\n2. dois", md)
    }

    @Test
    fun `lista aninhada indenta`() {
        val md = Odt.toMarkdown(
            doc(body = "<text:list><text:list-item><text:p>pai</text:p><text:list><text:list-item><text:p>filho</text:p></text:list-item></text:list></text:list-item></text:list>")
        )
        assertTrue(md.contains("- pai"))
        assertTrue("faltou indentar o filho", md.contains("  - filho"))
    }

    @Test
    fun `tabela sai com a linha separadora`() {
        val md = Odt.toMarkdown(
            doc(body = "<table:table><table:table-row><table:table-cell><text:p>A</text:p></table:table-cell><table:table-cell><text:p>B</text:p></table:table-cell></table:table-row><table:table-row><table:table-cell><text:p>1</text:p></table:table-cell><table:table-cell><text:p>2</text:p></table:table-cell></table:table-row></table:table>")
        )
        // Sem a separadora, a tabela inteira renderiza como texto solto
        assertTrue(md.contains("| A | B |"))
        assertTrue(md.contains("| --- | --- |"))
        assertTrue(md.contains("| 1 | 2 |"))
    }

    @Test
    fun `barra dentro da celula nao parte a coluna`() {
        val md = Odt.toMarkdown(
            doc(body = "<table:table><table:table-row><table:table-cell><text:p>a|b</text:p></table:table-cell></table:table-row></table:table>")
        )
        assertTrue(md.contains("a\\|b"))
    }

    @Test
    fun `xml sem corpo devolve vazio em vez de estourar`() {
        assertEquals("", Odt.toMarkdown("<office:document-content/>"))
        assertEquals("", Odt.toMarkdown(""))
    }

    @Test
    fun `quebra de linha manual vira quebra de markdown`() {
        val md = Odt.toMarkdown(doc(body = "<text:p>um<text:line-break/>dois</text:p>"))
        assertTrue(md.contains("um  \ndois"))
    }
}
