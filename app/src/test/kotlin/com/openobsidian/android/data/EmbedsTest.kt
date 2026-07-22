package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedsTest {

    private val vault = mapOf(
        "Sepse" to "---\ntags: [uti]\n---\n\n# Sepse\n\nDefinição curta.\n\n## Tratamento\n\nAntibiótico na primeira hora.\n\n## Prognóstico\n\nDepende do tempo.",
        "Curto" to "Uma linha só.",
        "Vazia" to "",
        "A" to "Começo de A\n\n![[B]]",
        "B" to "Começo de B\n\n![[A]]",
    )
    private val resolve: (String) -> String? = { vault[it] }

    // ── extractSection ────────────────────────────────────────────────────

    @Test
    fun `pega a secao ate o proximo titulo de mesmo nivel`() {
        val s = Embeds.extractSection(vault["Sepse"]!!, "Tratamento")
        assertTrue(s.contains("Antibiótico na primeira hora."))
        assertTrue("a seção seguinte vazou", !s.contains("Depende do tempo."))
    }

    @Test
    fun `secao inexistente devolve vazio`() {
        assertEquals("", Embeds.extractSection(vault["Sepse"]!!, "Não Existe"))
    }

    @Test
    fun `casa o titulo ignorando caixa`() {
        assertTrue(Embeds.extractSection(vault["Sepse"]!!, "tratamento").isNotEmpty())
    }

    @Test
    fun `titulo de nivel mais fundo nao encerra a secao`() {
        val md = "## Pai\n\ntexto\n\n### Filho\n\nmais texto\n\n## Tio\n\nfora"
        val s = Embeds.extractSection(md, "Pai")
        assertTrue(s.contains("mais texto"))
        assertTrue(!s.contains("fora"))
    }

    // ── expand ────────────────────────────────────────────────────────────

    @Test
    fun `embute a nota inteira como citacao`() {
        val out = Embeds.expand("Antes\n\n![[Curto]]\n\nDepois", resolve)
        assertTrue(out.contains("> Uma linha só."))
        assertTrue("faltou dizer de onde veio", out.contains("> **[[Curto]]**"))
        assertTrue(out.contains("Antes"))
        assertTrue(out.contains("Depois"))
    }

    @Test
    fun `embute so a secao pedida`() {
        val out = Embeds.expand("![[Sepse#Tratamento]]", resolve)
        assertTrue(out.contains("Antibiótico na primeira hora."))
        assertTrue("trouxe a nota toda em vez da seção", !out.contains("Depende do tempo."))
    }

    @Test
    fun `tira o frontmatter da nota embutida`() {
        // No meio do documento o `---` viraria título visível
        val out = Embeds.expand("![[Sepse]]", resolve)
        assertTrue("o frontmatter vazou para a tela", !out.contains("tags: [uti]"))
    }

    @Test
    fun `alvo inexistente fica literal em vez de sumir`() {
        val out = Embeds.expand("![[Nao Existe]]", resolve)
        assertEquals("![[Nao Existe]]", out)
    }

    @Test
    fun `nota vazia fica literal`() {
        assertEquals("![[Vazia]]", Embeds.expand("![[Vazia]]", resolve))
    }

    @Test
    fun `ciclo entre duas notas nao trava`() {
        // A embute B, B embute A. Sem a guarda isto recursa até estourar.
        val out = Embeds.expand("![[A]]", resolve)
        assertTrue(out.contains("Começo de A"))
        assertTrue(out.contains("Começo de B"))
    }

    @Test
    fun `linha vazia dentro do conteudo nao parte a citacao`() {
        val out = Embeds.expand("![[Sepse]]", resolve)
        // Toda linha do miolo tem de começar com `>`, senão a citação termina
        // no primeiro parágrafo e o resto vaza como texto solto
        val corpo = out.trim().split("\n").filter { it.isNotBlank() }
        assertTrue("linha do embed sem `>`", corpo.all { it.startsWith(">") })
    }

    @Test
    fun `imagem continua intocada aqui`() {
        // O passe de imagem roda antes; o que sobra sem nota atrás fica literal
        assertEquals("![[foto.png]]", Embeds.expand("![[foto.png]]", resolve))
    }

    @Test
    fun `texto sem embed passa intacto`() {
        val md = "# Título\n\n[[link normal]] e ![alt](img.png)"
        assertEquals(md, Embeds.expand(md, resolve))
    }
}
