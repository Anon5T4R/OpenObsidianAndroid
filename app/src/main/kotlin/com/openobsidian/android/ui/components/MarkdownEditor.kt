package com.openobsidian.android.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Slash commands catalogue
// ─────────────────────────────────────────────────────────────────────────────

private data class SlashCmd(
    val icon: ImageVector,
    val label: String,
    val snippet: String,
    /** Characters to move cursor back from end of snippet (0 = stay at end). */
    val cursorBack: Int = 0,
    /** When true, selecting this command launches the gallery picker instead of inserting [snippet]. */
    val isImageImport: Boolean = false,
)

private val SLASH_COMMANDS = listOf(
    SlashCmd(Icons.Default.Title,                "Heading 1",       "# "),
    SlashCmd(Icons.Default.Title,                "Heading 2",       "## "),
    SlashCmd(Icons.Default.Title,                "Heading 3",       "### "),
    SlashCmd(Icons.Default.FormatBold,           "Bold",            "**bold**",            4),
    SlashCmd(Icons.Default.FormatItalic,         "Italic",          "*italic*",            7),
    SlashCmd(Icons.Default.FormatStrikethrough,  "Strikethrough",   "~~text~~",            6),
    SlashCmd(Icons.Default.Code,                 "Inline code",     "`code`",              5),
    SlashCmd(Icons.Default.DataObject,           "Code block",      "```\n\n```",          4),
    SlashCmd(Icons.Default.TableChart,           "Table",           "| Col 1 | Col 2 |\n|---|---|\n| Cell | Cell |"),
    SlashCmd(Icons.Default.FormatListBulleted,   "Bullet list",     "- "),
    SlashCmd(Icons.Default.FormatListNumbered,   "Numbered list",   "1. "),
    SlashCmd(Icons.Default.CheckBox,             "Checkbox",        "- [ ] "),
    SlashCmd(Icons.Default.Link,                 "Wikilink",        "[[link]]",            2),
    SlashCmd(Icons.Default.Image,                "Image (embed)",   "![[image.png]]",      10),
    SlashCmd(Icons.Default.AddPhotoAlternate,    "Image from gallery", "", isImageImport = true),
    SlashCmd(Icons.Default.FormatQuote,          "Quote",           "> "),
    SlashCmd(Icons.Default.HorizontalRule,       "Divider",         "---\n"),
)

// ─────────────────────────────────────────────────────────────────────────────
// Main composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen markdown editor backed by BasicTextField.
 *
 * Features:
 *   • Syntax highlighting (headings, bold, italic, strikethrough, code, wikilinks)
 *   • Selection formatting toolbar (Bold / Italic / Strike / Code / Wikilink / Quote)
 *   • Slash commands: type `/` at line start → picker appears at bottom
 */
@Composable
fun MarkdownEditor(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onImportImage: (suspend (Uri) -> String?)? = null,
) {
    val colors      = MaterialTheme.colorScheme
    val baseStyle   = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Default,
        fontSize   = 15.sp,
        lineHeight = 24.sp,
        color      = colors.onSurface,
    )
    val transform   = remember(colors) { MarkdownHighlightTransformation(colors) }
    val scrollState = rememberScrollState()

    // ── Internal TextFieldValue state ────────────────────────────────────────
    var tfv by remember { mutableStateOf(TextFieldValue(content)) }

    // Sync when content changes externally (file switch, etc.)
    LaunchedEffect(content) {
        if (tfv.text != content) {
            tfv = TextFieldValue(content)
        }
    }

    // ── Slash command state ──────────────────────────────────────────────────
    var showSlash  by remember { mutableStateOf(false) }
    var slashStart by remember { mutableStateOf(-1) }
    var slashQuery by remember { mutableStateOf("") }

    // ── Image import (gallery picker) ─────────────────────────────────────────
    val scope = rememberCoroutineScope()
    var pendingImageOffset by remember { mutableStateOf(-1) }
    val imageLauncher = if (onImportImage != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val off = pendingImageOffset
            pendingImageOffset = -1
            if (uri != null) {
                scope.launch {
                    val name = onImportImage(uri) ?: return@launch
                    val at      = off.coerceIn(0, tfv.text.length)
                    val snippet = "![[$name]]"
                    val newText = tfv.text.substring(0, at) + snippet + tfv.text.substring(at)
                    tfv = TextFieldValue(newText, selection = TextRange(at + snippet.length))
                    onContentChange(newText)
                }
            }
        }
    } else null

    // ── Selection state ──────────────────────────────────────────────────────
    val hasSelection = !tfv.selection.collapsed && tfv.selection.length > 0

    // ── Helpers ──────────────────────────────────────────────────────────────

    fun wrapSelection(before: String, after: String) {
        val sel  = tfv.selection
        val text = tfv.text
        val selected = text.substring(sel.min, sel.max)
        val newText  = text.substring(0, sel.min) + before + selected + after + text.substring(sel.max)
        val newEnd   = sel.min + before.length + selected.length + after.length
        tfv = TextFieldValue(newText, selection = TextRange(sel.min + before.length, newEnd))
        onContentChange(newText)
    }

    fun insertSnippet(cmd: SlashCmd) {
        val text   = tfv.text
        val cursor = tfv.selection.start

        if (cmd.isImageImport && imageLauncher != null) {
            // Drop the "/query" trigger text, then launch the picker.
            val newText = text.substring(0, slashStart) + text.substring(cursor)
            tfv = TextFieldValue(newText, selection = TextRange(slashStart))
            onContentChange(newText)
            pendingImageOffset = slashStart
            showSlash = false
            imageLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
            return
        }

        val newText   = text.substring(0, slashStart) + cmd.snippet + text.substring(cursor)
        val newCursor = slashStart + cmd.snippet.length - cmd.cursorBack
        tfv = TextFieldValue(newText, selection = TextRange(newCursor))
        onContentChange(newText)
        showSlash = false
    }

    fun onTfvChange(new: TextFieldValue) {
        tfv = new
        onContentChange(new.text)

        val cursor = new.selection.start
        val text   = new.text

        if (showSlash) {
            // Update or dismiss existing slash menu
            if (!new.selection.collapsed || cursor <= slashStart) {
                showSlash = false
            } else {
                val afterSlash = text.substring(slashStart + 1, minOf(cursor, text.length))
                if (afterSlash.contains('\n') || afterSlash.length > 20) {
                    showSlash = false
                } else {
                    slashQuery = afterSlash.lowercase()
                }
            }
        } else {
            // Detect new '/' at line start
            if (new.selection.collapsed && cursor > 0 && text.getOrNull(cursor - 1) == '/') {
                val lineStart = text.lastIndexOf('\n', cursor - 2).let { if (it < 0) 0 else it + 1 }
                val prefix    = text.substring(lineStart, cursor - 1).trim()
                if (prefix.isEmpty()) {
                    showSlash  = true
                    slashStart = cursor - 1
                    slashQuery = ""
                }
            }
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────
    Column(modifier = modifier) {

        // Selection toolbar (top)
        AnimatedVisibility(visible = hasSelection && !showSlash) {
            SelectionToolbar(
                onBold    = { wrapSelection("**", "**") },
                onItalic  = { wrapSelection("*",  "*")  },
                onStrike  = { wrapSelection("~~", "~~") },
                onCode    = { wrapSelection("`",  "`")  },
                onWikilink = { wrapSelection("[[", "]]") },
                onQuote   = { wrapSelection("> ", "")   },
            )
        }

        // Text field
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value          = tfv,
                onValueChange  = { onTfvChange(it) },
                modifier       = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                textStyle            = baseStyle,
                cursorBrush          = SolidColor(colors.primary),
                visualTransformation = transform,
                decorationBox        = { innerTextField ->
                    if (tfv.text.isEmpty()) {
                        Text(
                            "Start writing…",
                            style = baseStyle.copy(color = colors.onSurface.copy(alpha = 0.35f)),
                        )
                    }
                    innerTextField()
                },
            )
        }

        // Slash command picker (bottom)
        AnimatedVisibility(visible = showSlash) {
            SlashCommandPicker(
                query     = slashQuery,
                onSelect  = { cmd -> insertSnippet(cmd) },
                onDismiss = { showSlash = false },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Selection toolbar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SelectionToolbar(
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onStrike: () -> Unit,
    onCode: () -> Unit,
    onWikilink: () -> Unit,
    onQuote: () -> Unit,
) {
    Surface(
        color     = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier  = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier                = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement   = Arrangement.Start,
            verticalAlignment       = Alignment.CenterVertically,
        ) {
            FormatButton(icon = Icons.Default.FormatBold,          desc = "Bold",          onClick = onBold)
            FormatButton(icon = Icons.Default.FormatItalic,        desc = "Italic",        onClick = onItalic)
            FormatButton(icon = Icons.Default.FormatStrikethrough, desc = "Strikethrough", onClick = onStrike)
            FormatButton(icon = Icons.Default.Code,                desc = "Code",          onClick = onCode)
            FormatButton(icon = Icons.Default.Link,                desc = "Wikilink",      onClick = onWikilink)
            FormatButton(icon = Icons.Default.FormatQuote,         desc = "Quote",         onClick = onQuote)
        }
    }
}

@Composable
private fun FormatButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(20.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Slash command picker
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SlashCommandPicker(
    query: String,
    onSelect: (SlashCmd) -> Unit,
    onDismiss: () -> Unit,
) {
    val filtered = remember(query) {
        if (query.isBlank()) SLASH_COMMANDS
        else SLASH_COMMANDS.filter { it.label.lowercase().contains(query) }
    }

    Surface(
        color          = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        tonalElevation  = 3.dp,
        modifier        = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp),
    ) {
        if (filtered.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No commands for \"/$query\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        } else {
            LazyColumn {
                item {
                    // Dismiss row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (query.isBlank()) "Insert…" else "Insert /$query",
                            style  = MaterialTheme.typography.labelMedium,
                            color  = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                        }
                    }
                    HorizontalDivider()
                }
                items(filtered) { cmd ->
                    ListItem(
                        headlineContent = { Text(cmd.label, style = MaterialTheme.typography.bodyMedium) },
                        leadingContent  = {
                            Icon(
                                cmd.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint     = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier.clickable { onSelect(cmd) },
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Syntax-highlight transformation
// ─────────────────────────────────────────────────────────────────────────────

private class MarkdownHighlightTransformation(
    private val colors: ColorScheme,
) : VisualTransformation {

    private var cachedText   = " "
    private var cachedResult: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        if (text.text == cachedText) return cachedResult!!
        val result = buildResult(text.text)
        cachedText   = text.text
        cachedResult = result
        return result
    }

    private fun buildResult(src: String): TransformedText {
        val annotated = buildAnnotatedString {
            append(src)

            // ── Fenced code blocks ────────────────────────────────────────
            val codeBlockRanges = mutableListOf<IntRange>()
            Regex("^```.*?^```", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
                .findAll(src).forEach { m ->
                    codeBlockRanges += m.range
                    addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 13.sp,
                            color      = colors.onSurface.copy(alpha = 0.65f),
                        ),
                        m.range.first, m.range.last + 1,
                    )
                }

            fun inCodeBlock(range: IntRange) =
                codeBlockRanges.any { it.first <= range.first && range.last <= it.last }

            // ── Headings ──────────────────────────────────────────────────
            Regex("^(#{1,6}) .+$", RegexOption.MULTILINE).findAll(src).forEach { m ->
                if (inCodeBlock(m.range)) return@forEach
                val fontSize = when (m.groupValues[1].length) {
                    1 -> 26.sp; 2 -> 22.sp; 3 -> 19.sp; else -> 17.sp
                }
                addStyle(
                    SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold, fontSize = fontSize),
                    m.range.first, m.range.last + 1,
                )
            }

            // ── Bold **…** or __…__
            Regex("(\\*\\*|__)(.+?)\\1").findAll(src).forEach { m ->
                if (inCodeBlock(m.range)) return@forEach
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), m.range.first, m.range.last + 1)
            }

            // ── Italic *…* or _…_
            Regex("(?<![*_])[*_](?![*_ ])(.+?)(?<![*_ ])[*_](?![*_])").findAll(src).forEach { m ->
                if (inCodeBlock(m.range)) return@forEach
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), m.range.first, m.range.last + 1)
            }

            // ── Strikethrough ~~…~~
            Regex("~~(.+?)~~").findAll(src).forEach { m ->
                if (inCodeBlock(m.range)) return@forEach
                addStyle(
                    SpanStyle(
                        textDecoration = TextDecoration.LineThrough,
                        color          = colors.onSurface.copy(alpha = 0.55f),
                    ),
                    m.range.first, m.range.last + 1,
                )
            }

            // ── Inline code `…`
            Regex("`([^`\n]+)`").findAll(src).forEach { m ->
                if (inCodeBlock(m.range)) return@forEach
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 13.sp,
                        background = colors.surfaceVariant,
                        color      = colors.onSurfaceVariant,
                    ),
                    m.range.first, m.range.last + 1,
                )
            }

            // ── Wikilinks [[…]]
            Regex("\\[\\[([^\\]\n]+)]]").findAll(src).forEach { m ->
                if (inCodeBlock(m.range)) return@forEach
                addStyle(
                    SpanStyle(color = colors.tertiary, fontWeight = FontWeight.Medium),
                    m.range.first, m.range.last + 1,
                )
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
