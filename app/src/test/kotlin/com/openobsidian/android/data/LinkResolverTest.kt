package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkResolverTest {

    private fun ref(relativePath: String) = LinkResolver.Ref(
        name = relativePath.substringAfterLast('/').removeSuffix(".md"),
        relativePath = relativePath,
        key = relativePath,
    )

    private val vault = listOf(
        ref("Sepse.md"),
        ref("Cardio/IAM.md"),
        ref("Cardio/Protocolos/Sepse.md"),
        ref("Neuro/AVC.md"),
    )

    @Test
    fun `splits the anchor off the target`() {
        assertEquals(LinkResolver.Target("Sepse", "Conduta"), LinkResolver.parseTarget("Sepse#Conduta"))
        assertEquals(LinkResolver.Target("Sepse", null), LinkResolver.parseTarget("Sepse"))
        assertEquals(LinkResolver.Target("Sepse", "^abc"), LinkResolver.parseTarget("Sepse#^abc"))
    }

    @Test
    fun `an anchor-only link has no note name`() {
        assertEquals(LinkResolver.Target("", "Seção"), LinkResolver.parseTarget("#Seção"))
    }

    @Test
    fun `resolves by file name`() {
        assertEquals("Neuro/AVC.md", LinkResolver.resolve(vault, "AVC")?.relativePath)
    }

    @Test
    fun `resolves by folder path`() {
        assertEquals("Cardio/IAM.md", LinkResolver.resolve(vault, "Cardio/IAM")?.relativePath)
    }

    @Test
    fun `ignores case and a md suffix`() {
        assertEquals("Neuro/AVC.md", LinkResolver.resolve(vault, "avc.md")?.relativePath)
    }

    @Test
    fun `a duplicate name prefers the note closest to the linker`() {
        val from = "Cardio/Protocolos/Choque.md"
        assertEquals("Cardio/Protocolos/Sepse.md", LinkResolver.resolve(vault, "Sepse", from)?.relativePath)
    }

    @Test
    fun `without a linking note a duplicate still resolves to something`() {
        assertTrue(LinkResolver.resolve(vault, "Sepse") != null)
    }

    @Test
    fun `an unknown target resolves to nothing`() {
        assertNull(LinkResolver.resolve(vault, "Fantasma"))
    }

    @Test
    fun `an empty target resolves to nothing`() {
        assertNull(LinkResolver.resolve(vault, "   "))
    }

    @Test
    fun `an alias resolves when no file matches`() {
        val aliases = mapOf("infarto" to "Cardio/IAM.md")
        assertEquals("Cardio/IAM.md", LinkResolver.resolve(vault, "Infarto", null, aliases)?.relativePath)
    }

    @Test
    fun `a real file name beats an alias`() {
        // Someone aliased "AVC" onto another note; the actual file still wins
        val aliases = mapOf("avc" to "Cardio/IAM.md")
        assertEquals("Neuro/AVC.md", LinkResolver.resolve(vault, "AVC", null, aliases)?.relativePath)
    }

    @Test
    fun `the first declaration of an alias wins`() {
        val fm = linkedMapOf(
            "A.md" to Frontmatter.parse("---\naliases:\n  - IAM\n---\ncorpo"),
            "B.md" to Frontmatter.parse("---\naliases:\n  - IAM\n---\ncorpo"),
        )
        assertEquals("A.md", LinkResolver.buildAliasIndex(fm)["iam"])
    }

    @Test
    fun `links inside code are not links`() {
        val md = "```\n[[Sepse]]\n```\n[[AVC]]"
        assertEquals(listOf("AVC"), LinkResolver.linksIn(md))
    }

    @Test
    fun `the alias of a link is not the target`() {
        assertEquals(listOf("Sepse"), LinkResolver.linksIn("[[Sepse|o quadro]]"))
    }

    @Test
    fun `the anchor is stripped from the target`() {
        assertEquals(listOf("Sepse"), LinkResolver.linksIn("[[Sepse#Conduta]]"))
    }

    @Test
    fun `an anchor-only link is not counted as a link to another note`() {
        assertTrue(LinkResolver.linksIn("[[#Seção]]").isEmpty())
    }

    @Test
    fun `broken links name the notes that point at them`() {
        val contents = mapOf(
            "Sepse.md" to "veja [[Fantasma]] e [[AVC]]",
            "Neuro/AVC.md" to "tambem [[Fantasma]]",
        )
        val broken = LinkResolver.brokenLinks(vault, contents)
        assertEquals(1, broken.size)
        assertEquals("Fantasma", broken[0].target)
        assertEquals(listOf("Sepse", "AVC"), broken[0].sources)
    }

    @Test
    fun `a link that resolves is not broken`() {
        val contents = mapOf("Sepse.md" to "[[AVC]]")
        assertTrue(LinkResolver.brokenLinks(vault, contents).isEmpty())
    }

    @Test
    fun `an orphan is a note nothing points at`() {
        val contents = mapOf("Sepse.md" to "[[AVC]]")
        val orphans = LinkResolver.orphanNotes(vault, contents).map { it.relativePath }
        // AVC is linked; everything else is not
        assertTrue(orphans.contains("Cardio/IAM.md"))
        assertTrue(!orphans.contains("Neuro/AVC.md"))
    }

    @Test
    fun `duplicate names are reported together`() {
        val dups = LinkResolver.duplicateNames(vault)
        assertEquals(1, dups.size)
        assertEquals("sepse", dups[0].first)
        assertEquals(2, dups[0].second.size)
    }

    @Test
    fun `a name that appears once is not a duplicate`() {
        assertTrue(LinkResolver.duplicateNames(listOf(ref("Unica.md"))).isEmpty())
    }
}
