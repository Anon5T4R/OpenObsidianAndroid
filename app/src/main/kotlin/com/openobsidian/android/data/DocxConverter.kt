package com.openobsidian.android.data

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Converts a .docx file to Markdown text.
 *
 * Strategy:
 *   1. Treat DOCX as a ZIP, extract `word/document.xml`.
 *   2. Walk the XML with XmlPullParser (no extra deps needed).
 *   3. Translate Word styles → Markdown syntax.
 *
 * Supported:
 *   - Heading 1-6 (Word "Heading1" … "Heading6" styles)
 *   - Bold (`w:b`), Italic (`w:i`), Strikethrough (`w:strike`)
 *   - Bulleted / numbered lists (rendered as `- `)
 *   - Inline code / code block (style names containing "code")
 *   - Plain paragraphs with blank-line separation
 */
object DocxConverter {

    fun convert(context: Context, uri: Uri): String {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return ""

        var docXml: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    docXml = zis.readBytes()
                    break
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        return docXml?.let { parseDocument(it) } ?: ""
    }

    // ── XML parsing ────────────────────────────────────────────────────────

    private fun parseDocument(xmlBytes: ByteArray): String {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val xpp = factory.newPullParser()
        xpp.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

        val sb = StringBuilder()

        // Paragraph state
        var inPara = false
        var headingLevel = 0
        var isList = false
        var listIndent = 0
        var isCode = false

        // Run state
        var inRun = false
        var inText = false
        var runBold = false
        var runItalic = false
        var runStrike = false
        val runText = StringBuilder()

        // Accumulated runs (already formatted as Markdown inline)
        val paraRuns = mutableListOf<String>()

        var event = xpp.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = xpp.name ?: ""

            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "w:p" -> {
                        inPara = true
                        headingLevel = 0; isList = false; listIndent = 0; isCode = false
                        paraRuns.clear()
                    }
                    "w:pStyle" -> {
                        val v = xpp.wAttr("val") ?: ""
                        headingLevel = when {
                            v.equals("Heading1", true) || v == "1" -> 1
                            v.equals("Heading2", true) || v == "2" -> 2
                            v.equals("Heading3", true) || v == "3" -> 3
                            v.equals("Heading4", true) || v == "4" -> 4
                            v.equals("Heading5", true) || v == "5" -> 5
                            v.equals("Heading6", true) || v == "6" -> 6
                            v.contains("code", ignoreCase = true)  -> { isCode = true; 0 }
                            else                                    -> 0
                        }
                    }
                    "w:numPr" -> isList = true
                    "w:ilvl"  -> listIndent = xpp.wAttr("val")?.toIntOrNull() ?: 0
                    "w:r"     -> {
                        inRun = true
                        runBold = false; runItalic = false; runStrike = false
                        runText.clear()
                    }
                    "w:b"     -> if (inRun) runBold   = true
                    "w:i"     -> if (inRun) runItalic  = true
                    "w:strike"-> if (inRun) runStrike = true
                    "w:t"     -> inText = true
                    "w:br"    -> if (inRun) runText.append("\n")
                    "w:tab"   -> if (inRun) runText.append("    ")
                }

                XmlPullParser.TEXT -> {
                    if (inText && inRun) runText.append(xpp.text)
                }

                XmlPullParser.END_TAG -> when (tag) {
                    "w:t" -> inText = false
                    "w:r" -> {
                        if (inRun && runText.isNotEmpty()) {
                            var t = runText.toString()
                            t = when {
                                runBold && runItalic -> "***$t***"
                                runBold              -> "**$t**"
                                runItalic            -> "*$t*"
                                else                 -> t
                            }
                            if (runStrike) t = "~~$t~~"
                            paraRuns += t
                        }
                        inRun = false
                    }
                    "w:p" -> {
                        if (inPara) {
                            val line = paraRuns.joinToString("").trim()
                            if (line.isBlank()) {
                                sb.append("\n")
                            } else when {
                                isCode            -> sb.append("    $line\n")
                                headingLevel > 0  -> sb.append("${"#".repeat(headingLevel)} $line\n\n")
                                isList            -> sb.append("${"  ".repeat(listIndent)}- $line\n")
                                else              -> sb.append("$line\n\n")
                            }
                        }
                        inPara = false
                    }
                }
            }
            event = xpp.next()
        }

        return sb.toString().trimEnd()
    }

    // ── Helper: get attribute value regardless of namespace prefix ─────────

    private fun XmlPullParser.wAttr(local: String): String? {
        for (i in 0 until attributeCount) {
            val name = getAttributeName(i)
            if (name == local || name == "w:$local" || name.endsWith(":$local")) {
                return getAttributeValue(i)
            }
        }
        return null
    }
}
