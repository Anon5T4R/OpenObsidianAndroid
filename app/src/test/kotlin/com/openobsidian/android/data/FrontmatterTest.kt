package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontmatterTest {

    @Test
    fun `splits the header off the body`() {
        val md = "---\ntipo: patologia\n---\n\n# Sepse\n\ntexto"
        val p = Frontmatter.parse(md)
        assertEquals(listOf("patologia"), p.valuesOf("tipo"))
        assertTrue(p.body.startsWith("\n# Sepse"))
        // The header must not survive into the body, or it renders as text
        assertTrue(!p.body.contains("tipo:"))
    }

    @Test
    fun `reads a list written with dashes`() {
        val md = "---\naliases:\n  - IAM\n  - Infarto\n---\ncorpo"
        assertEquals(listOf("IAM", "Infarto"), Frontmatter.parse(md).aliases)
    }

    @Test
    fun `reads a list written inline`() {
        val md = "---\ntags: [cardio, urgencia]\n---\ncorpo"
        assertEquals(listOf("cardio", "urgencia"), Frontmatter.parse(md).tags)
    }

    @Test
    fun `strips quotes`() {
        val md = "---\ntitulo: \"Com aspas\"\n---\nx"
        assertEquals(listOf("Com aspas"), Frontmatter.parse(md).valuesOf("titulo"))
    }

    @Test
    fun `a note without a header is returned whole`() {
        val md = "# Sem header\n\ntexto"
        val p = Frontmatter.parse(md)
        assertEquals(md, p.body)
        assertEquals(0, p.fields.size)
    }

    @Test
    fun `a dashed line further down is a rule, not a header`() {
        val md = "# Titulo\n\n---\n\ntexto"
        assertEquals(md, Frontmatter.parse(md).body)
    }

    @Test
    fun `an unclosed header does not swallow the note`() {
        val md = "---\ntipo: x\n\n# ainda e a nota"
        // Better to render the stray --- than to lose the note's whole body
        assertEquals(md, Frontmatter.parse(md).body)
    }

    @Test
    fun `finds inline tags with accents and nesting`() {
        val tags = Frontmatter.extractTags("veja #pré-natal e #sistema/cardio aqui")
        assertTrue(tags.contains("pré-natal"))
        assertTrue(tags.contains("sistema/cardio"))
    }

    @Test
    fun `header tags join the inline ones`() {
        val md = "---\ntags:\n  - doheader\n---\ncorpo com #inline"
        val tags = Frontmatter.extractTags(md)
        assertTrue(tags.contains("doheader"))
        assertTrue(tags.contains("inline"))
    }

    @Test
    fun `a hex colour is not a tag`() {
        assertEquals(0, Frontmatter.extractTags("cor #ff0000 aqui").size)
    }

    @Test
    fun `a bare number is not a tag`() {
        assertEquals(0, Frontmatter.extractTags("item #1 da lista").size)
    }

    @Test
    fun `tags inside code are left alone`() {
        assertEquals(0, Frontmatter.extractTags("```\n#include <stdio.h>\n```").size)
    }

    @Test
    fun `a parent tag finds its children`() {
        val expanded = Frontmatter.expandHierarchy(listOf("sistema/cardio/arritmia"))
        assertEquals(listOf("sistema", "sistema/cardio", "sistema/cardio/arritmia"), expanded)
    }
}
