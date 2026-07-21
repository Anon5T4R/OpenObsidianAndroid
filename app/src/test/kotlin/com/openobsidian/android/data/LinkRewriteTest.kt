package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkRewriteTest {

    @Test
    fun `rewrites a plain link`() {
        val r = LinkRewrite.rewriteLinks("veja [[Sepse]] aqui", "Sepse", "Sepse e Choque")
        assertEquals("veja [[Sepse e Choque]] aqui", r.content)
        assertEquals(1, r.count)
    }

    @Test
    fun `keeps the alias`() {
        val r = LinkRewrite.rewriteLinks("[[Sepse|o quadro]]", "Sepse", "Choque")
        assertEquals("[[Choque|o quadro]]", r.content)
    }

    @Test
    fun `keeps the anchor`() {
        val r = LinkRewrite.rewriteLinks("[[Sepse#Conduta]]", "Sepse", "Choque")
        assertEquals("[[Choque#Conduta]]", r.content)
    }

    @Test
    fun `keeps anchor and alias together`() {
        val r = LinkRewrite.rewriteLinks("[[Sepse#Conduta|ver]]", "Sepse", "Choque")
        assertEquals("[[Choque#Conduta|ver]]", r.content)
    }

    @Test
    fun `keeps the folder prefix`() {
        val r = LinkRewrite.rewriteLinks("[[Clinica/Sepse]]", "Sepse", "Choque")
        assertEquals("[[Clinica/Choque]]", r.content)
    }

    @Test
    fun `never touches a link inside a fenced block`() {
        val md = "```\n[[Sepse]]\n```\n[[Sepse]]"
        val r = LinkRewrite.rewriteLinks(md, "Sepse", "Choque")
        // The one in the fence is documentation about links, not a link
        assertEquals("```\n[[Sepse]]\n```\n[[Choque]]", r.content)
        assertEquals(1, r.count)
    }

    @Test
    fun `never touches inline code`() {
        val r = LinkRewrite.rewriteLinks("use `[[Sepse]]` assim", "Sepse", "Choque")
        assertEquals("use `[[Sepse]]` assim", r.content)
        assertEquals(0, r.count)
    }

    @Test
    fun `leaves other notes alone`() {
        val r = LinkRewrite.rewriteLinks("[[Sepse Neonatal]] e [[Sepse]]", "Sepse", "Choque")
        assertEquals("[[Sepse Neonatal]] e [[Choque]]", r.content)
        assertEquals(1, r.count)
    }

    @Test
    fun `matches regardless of case and of a md suffix`() {
        val r = LinkRewrite.rewriteLinks("[[sepse.md]]", "Sepse", "Choque")
        assertEquals("[[Choque]]", r.content)
    }

    @Test
    fun `counts without changing anything`() {
        val md = "[[Sepse]] e [[Sepse|x]] e [[Outra]]"
        assertEquals(2, LinkRewrite.countRefs(md, "Sepse"))
    }

    @Test
    fun `a note with no links is returned untouched`() {
        val md = "# Titulo\n\ntexto comum"
        val r = LinkRewrite.rewriteLinks(md, "Sepse", "Choque")
        assertEquals(md, r.content)
        assertEquals(0, r.count)
    }
}
