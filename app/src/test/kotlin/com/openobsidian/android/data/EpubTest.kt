package com.openobsidian.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubTest {

    private val container = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private val opf = """
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata><dc:title>Semiologia Médica</dc:title></metadata>
          <manifest>
            <item id="cap1" href="text/cap1.xhtml" media-type="application/xhtml+xml"/>
            <item id="cap2" href="text/cap2.xhtml" media-type="application/xhtml+xml"/>
            <item id="css"  href="style.css" media-type="text/css"/>
          </manifest>
          <spine>
            <itemref idref="cap1"/>
            <itemref idref="cap2"/>
          </spine>
        </package>
    """.trimIndent()

    @Test
    fun `finds the manifest through the container`() {
        assertEquals("OEBPS/content.opf", Epub.opfPathFrom(container))
    }

    @Test
    fun `a container without a rootfile yields nothing instead of guessing`() {
        assertNull(Epub.opfPathFrom("<container></container>"))
    }

    @Test
    fun `the spine gives the reading order, not the manifest order`() {
        val chapters = Epub.chaptersFrom(opf)
        assertEquals(listOf("text/cap1.xhtml", "text/cap2.xhtml"), chapters.map { it.href })
    }

    @Test
    fun `files outside the spine are not chapters`() {
        // style.css is in the manifest but is not something to read
        assertTrue(Epub.chaptersFrom(opf).none { it.href.endsWith(".css") })
    }

    @Test
    fun `a spine pointing at a missing id skips that entry`() {
        val broken = opf.replace("""<itemref idref="cap2"/>""", """<itemref idref="fantasma"/>""")
        // Better one chapter short than no book at all
        assertEquals(1, Epub.chaptersFrom(broken).size)
    }

    @Test
    fun `reads the book title`() {
        assertEquals("Semiologia Médica", Epub.titleFrom(opf))
    }

    @Test
    fun `a book with no title says so instead of inventing one`() {
        assertNull(Epub.titleFrom("<package></package>"))
    }

    @Test
    fun `chapter titles come from an epub 2 toc`() {
        val ncx = """
            <navMap>
              <navPoint><navLabel><text>Anamnese</text></navLabel><content src="text/cap1.xhtml"/></navPoint>
              <navPoint><navLabel><text>Exame físico</text></navLabel><content src="text/cap2.xhtml#topo"/></navPoint>
            </navMap>
        """.trimIndent()
        val titles = Epub.titlesFromToc(ncx)
        assertEquals("Anamnese", titles["text/cap1.xhtml"])
        // The #anchor is not part of the file name
        assertEquals("Exame físico", titles["text/cap2.xhtml"])
    }

    @Test
    fun `chapter titles come from an epub 3 nav too`() {
        val nav = """<nav><ol><li><a href="text/cap1.xhtml">Anamnese</a></li></ol></nav>"""
        assertEquals("Anamnese", Epub.titlesFromToc(nav)["text/cap1.xhtml"])
    }

    @Test
    fun `without a toc the file name is made readable`() {
        val chapters = Epub.chaptersFrom(opf)
        assertEquals("cap1", chapters[0].title)
    }

    @Test
    fun `a toc title wins over the file name`() {
        val chapters = Epub.chaptersFrom(opf, mapOf("text/cap1.xhtml" to "Anamnese"))
        assertEquals("Anamnese", chapters[0].title)
    }

    @Test
    fun `hrefs resolve against the folder of the manifest`() {
        // Paths are relative to the OPF, not to the root of the zip — getting
        // this wrong is why a book renders as a wall of missing chapters
        assertEquals("OEBPS/text/cap1.xhtml", Epub.resolve("OEBPS/content.opf", "text/cap1.xhtml"))
    }

    @Test
    fun `a manifest at the root resolves plainly`() {
        assertEquals("text/cap1.xhtml", Epub.resolve("content.opf", "text/cap1.xhtml"))
    }

    @Test
    fun `dot dot climbs out of the folder`() {
        assertEquals("OEBPS/img/x.png", Epub.resolve("OEBPS/text/content.opf", "../img/x.png"))
    }
}
