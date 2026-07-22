package com.openobsidian.android.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
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
import com.openobsidian.android.R
import com.openobsidian.android.data.Insertables
import com.openobsidian.android.data.TagComplete
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Slash commands catalogue
// ─────────────────────────────────────────────────────────────────────────────

// The catalogue itself lives in data/Insertables.kt, shared with the insert
// sheet and translated in strings.xml. There used to be a hand-written list of
// eighteen right here, in English only, mentioning none of the things this app
// is for — no flashcards, callouts, Mermaid, maths, note embeds or query
// blocks. A second list is the bug.

private fun iconFor(item: Insertables.Item): ImageVector = when (item.id) {
    "h1", "h2", "h3" -> Icons.Default.Title
    "bold" -> Icons.Default.FormatBold
    "italic" -> Icons.Default.FormatItalic
    "strike" -> Icons.Default.FormatStrikethrough
    "highlight" -> Icons.Default.FormatColorFill
    "inlineCode" -> Icons.Default.Code
    "codeBlock" -> Icons.Default.DataObject
    "table" -> Icons.Default.TableChart
    "bulletList" -> Icons.Default.FormatListBulleted
    "numList" -> Icons.Default.FormatListNumbered
    "task" -> Icons.Default.CheckBox
    "quote" -> Icons.Default.FormatQuote
    "hr" -> Icons.Default.HorizontalRule
    "imageRef" -> Icons.Default.Image
    "imagePick" -> Icons.Default.AddPhotoAlternate
    "webLink" -> Icons.Default.Link
    else -> when (item.category) {
        Insertables.Category.LINKS -> Icons.Default.Link
        Insertables.Category.STUDY -> Icons.Default.Style
        Insertables.Category.CALLOUTS -> Icons.Default.Info
        Insertables.Category.DIAGRAMS -> Icons.Default.AccountTree
        Insertables.Category.DATA -> Icons.Default.Tag
        Insertables.Category.SYMBOLS -> Icons.Default.Bolt
        else -> Icons.Default.Notes
    }
}

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
 *   • Find & replace bar ([findBarVisible]) e salto de cursor ([cursorRequest],
 *     usado pelo sumário para pular até um heading)
 */
@Composable
fun MarkdownEditor(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onImportImage: (suspend (Uri) -> String?)? = null,
    /** tag → how many notes carry it, for the `#` autocomplete. */
    tagCounts: Map<String, Int> = emptyMap(),
    cursorRequest: Int? = null,
    onCursorRequestHandled: () -> Unit = {},
    /** Bumped by the toolbar button to open the insert picker at the cursor. */
    insertRequest: Int? = null,
    onInsertRequestHandled: () -> Unit = {},
    findBarVisible: Boolean = false,
    onFindBarClose: () -> Unit = {},
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

    // ── Text layout (para rolar até um offset) ───────────────────────────────
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    suspend fun scrollToOffset(offset: Int) {
        val layout  = textLayout ?: return
        val clamped = offset.coerceIn(0, layout.layoutInput.text.length)
        val line    = layout.getLineForOffset(clamped)
        val y       = (layout.getLineTop(line).toInt() - 48).coerceAtLeast(0)
        scrollState.animateScrollTo(y)
    }

    // Salto de cursor pedido de fora (sumário → heading)
    LaunchedEffect(cursorRequest) {
        val off = cursorRequest ?: return@LaunchedEffect
        val clamped = off.coerceIn(0, tfv.text.length)
        tfv = tfv.copy(selection = TextRange(clamped))
        scrollToOffset(clamped)
        onCursorRequestHandled()
    }

    // ── Slash command state ──────────────────────────────────────────────────
    var showSlash  by remember { mutableStateOf(false) }
    var slashStart by remember { mutableStateOf(-1) }
    var slashQuery by remember { mutableStateOf("") }
    // 1 when the picker was opened by typing `/`, 0 when it was opened by the
    // toolbar button — the button leaves no trigger character to skip over.
    var slashTriggerLen by remember { mutableStateOf(1) }

    // Opened from the toolbar. Typing `/` at line start is not a thing anyone
    // discovers on a phone; a button is how you find out the catalogue exists.
    LaunchedEffect(insertRequest) {
        if (insertRequest == null) return@LaunchedEffect
        slashStart = tfv.selection.start.coerceIn(0, tfv.text.length)
        slashQuery = ""
        slashTriggerLen = 0
        showSlash = true
        onInsertRequestHandled()
    }

    // ── Tag autocomplete state ───────────────────────────────────────────────
    // Recomputed only when the vault index changes, not on every keystroke
    var tagStart by remember { mutableStateOf(-1) }
    var tagQuery by remember { mutableStateOf("") }
    val tagMatches = remember(tagQuery, tagCounts) {
        if (tagStart < 0) emptyList() else TagComplete.rank(tagCounts, tagQuery)
    }

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

    // ── Find & replace state ─────────────────────────────────────────────────
    var findQuery    by remember { mutableStateOf("") }
    var replaceValue by remember { mutableStateOf("") }
    var showReplace  by remember { mutableStateOf(false) }
    var currentMatch by remember { mutableStateOf(0) }

    val matches = remember(tfv.text, findQuery) {
        if (findQuery.isEmpty()) emptyList()
        else {
            val out = mutableListOf<Int>()
            var i = tfv.text.indexOf(findQuery, 0, ignoreCase = true)
            while (i >= 0) {
                out += i
                i = tfv.text.indexOf(findQuery, i + 1, ignoreCase = true)
            }
            out
        }
    }
    LaunchedEffect(matches.size) {
        if (currentMatch >= matches.size) currentMatch = 0
    }

    fun gotoMatch(index: Int) {
        if (matches.isEmpty()) return
        val i     = ((index % matches.size) + matches.size) % matches.size
        val start = matches[i]
        currentMatch = i
        tfv = tfv.copy(selection = TextRange(start, start + findQuery.length))
        scope.launch { scrollToOffset(start) }
    }

    fun replaceCurrent() {
        if (matches.isEmpty()) return
        val start   = matches[currentMatch.coerceIn(0, matches.lastIndex)]
        val newText = tfv.text.substring(0, start) + replaceValue +
                      tfv.text.substring(start + findQuery.length)
        tfv = TextFieldValue(newText, selection = TextRange(start + replaceValue.length))
        onContentChange(newText)
    }

    fun replaceAll() {
        if (findQuery.isEmpty() || matches.isEmpty()) return
        val newText = tfv.text.replace(findQuery, replaceValue, ignoreCase = true)
        tfv = TextFieldValue(newText, selection = TextRange(0))
        onContentChange(newText)
    }

    // ── Selection state ──────────────────────────────────────────────────────
    val hasSelection = !tfv.selection.collapsed && tfv.selection.length > 0

    // ── Helpers ──────────────────────────────────────────────────────────────

    fun wrapSelection(before: String, after: String) {
        val sel  = tfv.selection
        if (sel.collapsed) return
        val text = tfv.text
        val selected = text.substring(sel.min, sel.max)
        val newText  = text.substring(0, sel.min) + before + selected + after + text.substring(sel.max)
        val newEnd   = sel.min + before.length + selected.length + after.length
        tfv = TextFieldValue(newText, selection = TextRange(sel.min + before.length, newEnd))
        onContentChange(newText)
    }

    fun insertSnippet(cmd: Insertables.Item) {
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

        // The cursor position comes from a marker inside the snippet, not from
        // an offset counted by hand — the desktop counts backwards from the end
        // and got it wrong three times, twice landing the caret inside a word so
        // the first keystroke destroyed the snippet.
        val insertion = Insertables.resolve(cmd)
        val newText   = text.substring(0, slashStart) + insertion.text + text.substring(cursor)
        tfv = TextFieldValue(newText, selection = TextRange(slashStart + insertion.cursor))
        onContentChange(newText)
        showSlash = false
    }

    /** Replaces the `#tag` being typed with the chosen one. */
    fun insertTag(option: TagComplete.Option) {
        val at = tagStart
        if (at < 0) return
        val cursor = tfv.selection.start.coerceIn(0, tfv.text.length)
        val inserted = "#" + option.tag
        val newText = tfv.text.substring(0, at) + inserted + tfv.text.substring(cursor)
        tfv = TextFieldValue(newText, selection = TextRange(at + inserted.length))
        onContentChange(newText)
        tagStart = -1
        tagQuery = ""
    }

    fun onTfvChange(new: TextFieldValue) {
        tfv = new
        onContentChange(new.text)

        val cursor = new.selection.start
        val text   = new.text

        // `#` autocomplete. The rule for when this may open lives in
        // TagComplete, tested there — above all it must stay shut while a
        // heading is being written, which is most of what `#` is used for.
        if (new.selection.collapsed && cursor <= text.length) {
            val lineStart = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0))
                .let { if (it < 0) 0 else it + 1 }
            val match = TagComplete.matchAtCursor(text.substring(lineStart, cursor))
            if (match == null) {
                tagStart = -1
                tagQuery = ""
            } else {
                tagStart = lineStart + match.from
                tagQuery = match.query
            }
        } else {
            tagStart = -1
            tagQuery = ""
        }

        if (showSlash) {
            // Update or dismiss existing slash menu
            if (!new.selection.collapsed || cursor <= slashStart) {
                showSlash = false
            } else {
                val afterSlash = text.substring(minOf(slashStart + slashTriggerLen, text.length), minOf(cursor, text.length))
                if (afterSlash.contains('\n') || afterSlash.length > 20) {
                    showSlash = false
                } else {
                    slashQuery = afterSlash.lowercase()
                }
            }
        } else {
            // Detect new '/' at line start
            if (new.selection.collapsed && cursor > 0 && text.getOrNull(cursor - 1) == '/') {
                slashTriggerLen = 1
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

        // Find & replace bar (top)
        AnimatedVisibility(visible = findBarVisible) {
            FindReplaceBar(
                query           = findQuery,
                onQueryChange   = { findQuery = it; currentMatch = 0 },
                matchCount      = matches.size,
                currentMatch    = if (matches.isEmpty()) 0 else currentMatch + 1,
                onPrev          = { gotoMatch(currentMatch - 1) },
                onNext          = { gotoMatch(currentMatch + 1) },
                showReplace     = showReplace,
                onToggleReplace = { showReplace = !showReplace },
                replaceValue    = replaceValue,
                onReplaceChange = { replaceValue = it },
                onReplaceOne    = { replaceCurrent() },
                onReplaceAll    = { replaceAll() },
                onClose         = { findQuery = ""; showReplace = false; onFindBarClose() },
            )
        }

        // Selection toolbar (top) — shown while text is selected.
        AnimatedVisibility(visible = hasSelection && !showSlash) {
            SelectionToolbar(
                onBold     = { wrapSelection("**", "**") },
                onItalic   = { wrapSelection("*",  "*")  },
                onStrike   = { wrapSelection("~~", "~~") },
                onCode     = { wrapSelection("`",  "`")  },
                onHighlight = { wrapSelection("==", "==") },
                onWikilink = { wrapSelection("[[", "]]") },
                onQuote    = { wrapSelection("> ", "")   },
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
                onTextLayout         = { textLayout = it },
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

        // Tag picker. Never both at once: `/` and `#` cannot be the same word.
        AnimatedVisibility(visible = !showSlash && tagMatches.isNotEmpty()) {
            TagPicker(
                options   = tagMatches,
                onSelect  = { insertTag(it) },
                onDismiss = { tagStart = -1; tagQuery = "" },
            )
        }
    }
}

@Composable
private fun TagPicker(
    options: List<TagComplete.Option>,
    onSelect: (TagComplete.Option) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color           = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        tonalElevation  = 3.dp,
        modifier        = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp),
    ) {
        LazyColumn {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.ins_tag),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                    }
                }
                HorizontalDivider()
            }
            items(options) { option ->
                ListItem(
                    headlineContent = { Text("#" + option.tag, style = MaterialTheme.typography.bodyMedium) },
                    // The count is what makes the order legible, and it also
                    // exposes a typo: a tag with one note next to one with forty
                    trailingContent = {
                        Text(
                            stringResource(R.string.tag_complete_notes, option.count),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        )
                    },
                    modifier = Modifier.clickable { onSelect(option) },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Find & replace bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FindReplaceBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentMatch: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    showReplace: Boolean,
    onToggleReplace: () -> Unit,
    replaceValue: String,
    onReplaceChange: (String) -> Unit,
    onReplaceOne: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color          = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier       = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value         = query,
                    onValueChange = onQueryChange,
                    placeholder   = { Text(stringResource(R.string.find_placeholder)) },
                    singleLine    = true,
                    textStyle     = MaterialTheme.typography.bodyMedium,
                    modifier      = Modifier.weight(1f),
                )
                Text(
                    text  = if (query.isEmpty()) "" else "$currentMatch/$matchCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(onClick = onPrev, enabled = matchCount > 0, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.cd_prev_match), modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onNext, enabled = matchCount > 0, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.cd_next_match), modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onToggleReplace, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.FindReplace,
                        contentDescription = stringResource(R.string.cd_replace),
                        modifier = Modifier.size(20.dp),
                        tint = if (showReplace) MaterialTheme.colorScheme.primary
                               else LocalContentColor.current,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_find), modifier = Modifier.size(20.dp))
                }
            }
            AnimatedVisibility(visible = showReplace) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier          = Modifier.padding(top = 4.dp),
                ) {
                    OutlinedTextField(
                        value         = replaceValue,
                        onValueChange = onReplaceChange,
                        placeholder   = { Text(stringResource(R.string.replace_placeholder)) },
                        singleLine    = true,
                        textStyle     = MaterialTheme.typography.bodyMedium,
                        modifier      = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onReplaceOne, enabled = matchCount > 0) { Text(stringResource(R.string.action_replace)) }
                    TextButton(onClick = onReplaceAll, enabled = matchCount > 0) { Text(stringResource(R.string.action_replace_all)) }
                }
            }
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
    onHighlight: () -> Unit,
    onWikilink: () -> Unit,
    onQuote: () -> Unit,
) {
    Surface(
        color          = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier       = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            FormatButton(Icons.Default.FormatBold,          "Bold",          onBold)
            FormatButton(Icons.Default.FormatItalic,        "Italic",        onItalic)
            FormatButton(Icons.Default.FormatStrikethrough, "Strikethrough", onStrike)
            FormatButton(Icons.Default.Code,                "Code",          onCode)
            FormatButton(Icons.Default.FormatColorFill,     "Highlight",     onHighlight)
            FormatButton(Icons.Default.Link,                "Wikilink",      onWikilink)
            FormatButton(Icons.Default.FormatQuote,         "Quote",         onQuote)
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
    onSelect: (Insertables.Item) -> Unit,
    onDismiss: () -> Unit,
) {
    val filtered = remember(query) { Insertables.search(query) }

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
                    stringResource(R.string.insert_no_match),
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
                            if (query.isBlank()) stringResource(R.string.insert_title) else "/" + query,
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
                        headlineContent = { Text(stringResource(cmd.labelRes), style = MaterialTheme.typography.bodyMedium) },
                        // The description is what makes a feature discoverable
                        // rather than merely re-findable by someone who already
                        // knows it exists
                        supportingContent = {
                            Text(
                                stringResource(cmd.descRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        },
                        leadingContent  = {
                            Icon(
                                iconFor(cmd),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint     = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Text(
                                Insertables.primarySlash(cmd),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
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
