package com.openobsidian.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// Modelo + parser
// ─────────────────────────────────────────────────────────────────────────────

data class TocHeading(
    val level: Int,
    /** Texto cru do heading, como está no markdown (sem os #). */
    val text: String,
    /** Offset do início da linha no conteúdo fonte (para o cursor do editor). */
    val offset: Int,
)

private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")

/** Extrai os headings do markdown, ignorando linhas dentro de code fences. */
fun parseHeadings(content: String): List<TocHeading> {
    val out = mutableListOf<TocHeading>()
    var offset  = 0
    var inFence = false
    for (line in content.split("\n")) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
        } else if (!inFence) {
            HEADING_REGEX.find(line)?.let { m ->
                out += TocHeading(
                    level  = m.groupValues[1].length,
                    text   = m.groupValues[2].trim(),
                    offset = offset,
                )
            }
        }
        offset += line.length + 1
    }
    return out
}

/**
 * Remove a marcação inline de um heading para casar com o texto renderizado
 * pelo Markwon no preview (negrito, itálico, código, highlight, wikilinks…).
 */
fun plainHeadingText(raw: String): String {
    var s = raw
    s = s.replace(Regex("!\\[\\[[^\\]\n]*]]"), "")                                    // imagens embed
    s = s.replace(Regex("\\[\\[([^\\]|\n]+?)\\|([^\\]\n]+?)]]"), "$2")                // [[alvo|texto]]
    s = s.replace(Regex("\\[\\[([^\\]\n]+?)]]"), "$1")                                // [[alvo]]
    s = s.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")                          // [texto](url)
    s = s.replace(Regex("(\\*\\*|__|\\*|_|~~|==|`)"), "")                             // ênfases
    return s.trim()
}

// ─────────────────────────────────────────────────────────────────────────────
// Ponte de scroll com o preview (registrada pelo MarkdownPreview)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Handle simples para pedir ao [MarkdownPreview] que role até um heading.
 * O preview registra a implementação quando o AndroidView atualiza.
 */
class PreviewScrollConnection {
    internal var handler: ((plainText: String, occurrence: Int) -> Unit)? = null

    /** Rola até a [occurrence]-ésima linha renderizada igual a [plainText]. */
    fun scrollToHeading(plainText: String, occurrence: Int) {
        handler?.invoke(plainText, occurrence)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Conteúdo do bottom sheet
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TocSheetContent(
    headings: List<TocHeading>,
    onHeadingClick: (index: Int, heading: TocHeading) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 32.dp)) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.FormatListBulleted, null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Outline (${headings.size})",
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HorizontalDivider()
        if (headings.isEmpty()) {
            Text(
                "This note has no headings.",
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(headings) { index, h ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onHeadingClick(index, h) }
                            .padding(
                                start  = (16 + (h.level - 1) * 16).dp,
                                end    = 16.dp,
                                top    = 10.dp,
                                bottom = 10.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            plainHeadingText(h.text),
                            style      = if (h.level == 1)
                                MaterialTheme.typography.bodyLarge
                            else
                                MaterialTheme.typography.bodyMedium,
                            fontWeight = if (h.level <= 2) FontWeight.Medium else FontWeight.Normal,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
