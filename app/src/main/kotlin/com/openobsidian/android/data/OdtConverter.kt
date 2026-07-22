package com.openobsidian.android.data

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Abre um `.odt` e devolve Markdown.
 *
 * Mesma forma do [DocxConverter]: o arquivo é um ZIP, o conteúdo mora num XML
 * dentro dele. A conversão em si está em [Odt], separada para poder ser
 * testada sem emulador — aqui fica só a leitura do ZIP, que não dá para
 * exercitar em teste unitário.
 */
object OdtConverter {

    fun convert(context: Context, uri: Uri): String {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return ""

        var contentXml: ByteArray? = null
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "content.xml") {
                        contentXml = zis.readBytes()
                        break
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        val xml = contentXml ?: return ""
        return Odt.toMarkdown(String(xml, Charsets.UTF_8))
    }
}
