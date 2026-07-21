package com.openobsidian.android.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openobsidian.android.R
import com.openobsidian.android.data.Epub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** A book, opened: its title and the chapters in reading order. */
private data class Book(val title: String, val chapters: List<Epub.Chapter>, val opfPath: String)

/**
 * An `.epub`, one chapter at a time.
 *
 * The whole book is not loaded at once on purpose: a textbook is tens of
 * megabytes of XHTML, and a phone that has to hold all of it to show page one
 * is a phone that shows nothing.
 *
 * Images inside the book are inlined as data URIs, because the WebView runs
 * with no file and no network access — a book cannot be allowed to fetch
 * anything, and an EPUB is untrusted content like any other download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubViewer(
    fileUri: Uri,
    onOpenNotes: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var book by remember(fileUri) { mutableStateOf<Book?>(null) }
    var failed by remember(fileUri) { mutableStateOf(false) }
    var index by remember(fileUri) { mutableIntStateOf(0) }
    var html by remember(fileUri) { mutableStateOf<String?>(null) }
    var tocOpen by remember(fileUri) { mutableStateOf(false) }

    LaunchedEffect(fileUri) {
        book = runCatching { readBook(context, fileUri) }.getOrNull()
        failed = book == null || book?.chapters.isNullOrEmpty()
    }

    LaunchedEffect(book, index) {
        val b = book ?: return@LaunchedEffect
        val chapter = b.chapters.getOrNull(index) ?: return@LaunchedEffect
        html = runCatching { readChapter(context, fileUri, b.opfPath, chapter.href) }.getOrNull()
    }

    if (failed) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.epub_unreadable),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val b = book
    if (b == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val bg = MaterialTheme.colorScheme.surface.toArgb()
    val fg = MaterialTheme.colorScheme.onSurface

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { tocOpen = true }) {
                Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = stringResource(R.string.menu_outline))
            }
            Text(
                b.chapters.getOrNull(index)?.title ?: b.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${index + 1}/${b.chapters.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { if (index > 0) index-- }, enabled = index > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            IconButton(
                onClick = { if (index < b.chapters.lastIndex) index++ },
                enabled = index < b.chapters.lastIndex,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.action_forward))
            }
            TextButton(onClick = onOpenNotes) { Text(stringResource(R.string.notes_button)) }
        }
        HorizontalDivider()

        ChapterWebView(
            html = html,
            backgroundColor = bg,
            textColor = String.format("#%06X", 0xFFFFFF and fg.toArgb()),
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (tocOpen) {
        ModalBottomSheet(onDismissRequest = { tocOpen = false }) {
            LazyChapterList(b.chapters, index) { i -> index = i; tocOpen = false }
        }
    }
}

@Composable
private fun LazyChapterList(chapters: List<Epub.Chapter>, current: Int, onPick: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
        itemsIndexed(chapters) { i, c ->
            ListItem(
                headlineContent = { Text(c.title) },
                modifier = Modifier.clickable { onPick(i) },
                colors = if (i == current) {
                    ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                } else ListItemDefaults.colors(),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChapterWebView(
    html: String?,
    backgroundColor: Int,
    textColor: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                // A book is untrusted content: no scripts, no files, no network
                settings.javaScriptEnabled = false
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setBackgroundColor(backgroundColor)
            }
        },
        update = { web ->
            web.setBackgroundColor(backgroundColor)
            val body = html ?: ""
            web.loadDataWithBaseURL(
                null,
                """
                <!doctype html><html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                  body { margin: 0; padding: 16px; color: $textColor; background: transparent;
                         font-size: 17px; line-height: 1.6; word-wrap: break-word; }
                  img { max-width: 100%; height: auto; }
                  pre, code { white-space: pre-wrap; }
                </style>
                </head><body>$body</body></html>
                """.trimIndent(),
                "text/html",
                "utf-8",
                null,
            )
        },
    )
}

// ── ZIP reading ─────────────────────────────────────────────────────────────

private suspend fun readEntries(
    context: Context,
    fileUri: Uri,
    wanted: (String) -> Boolean,
): Map<String, ByteArray> = withContext(Dispatchers.IO) {
    val out = LinkedHashMap<String, ByteArray>()
    context.contentResolver.openInputStream(fileUri)?.use { raw ->
        ZipInputStream(raw.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && wanted(entry.name)) {
                    val buf = ByteArrayOutputStream()
                    zip.copyTo(buf)
                    out[entry.name] = buf.toByteArray()
                }
                zip.closeEntry()
            }
        }
    }
    out
}

private suspend fun readBook(context: Context, fileUri: Uri): Book? {
    val container = readEntries(context, fileUri) { it.equals("META-INF/container.xml", ignoreCase = true) }
        .values.firstOrNull()?.decodeToString() ?: return null
    val opfPath = Epub.opfPathFrom(container) ?: return null

    val opf = readEntries(context, fileUri) { it == opfPath }
        .values.firstOrNull()?.decodeToString() ?: return null

    // The table of contents is optional; without it chapters fall back to
    // their file names, which is worse but still navigable
    val toc = readEntries(context, fileUri) {
        it.endsWith(".ncx", ignoreCase = true) || it.endsWith("nav.xhtml", ignoreCase = true)
    }.values.joinToString("\n") { it.decodeToString() }

    val chapters = Epub.chaptersFrom(opf, if (toc.isEmpty()) emptyMap() else Epub.titlesFromToc(toc))
    if (chapters.isEmpty()) return null
    return Book(Epub.titleFrom(opf) ?: "", chapters, opfPath)
}

private val IMG_SRC = Regex("""(<img\b[^>]*\bsrc\s*=\s*["'])([^"']+)(["'])""", RegexOption.IGNORE_CASE)

private suspend fun readChapter(
    context: Context,
    fileUri: Uri,
    opfPath: String,
    href: String,
): String {
    val path = Epub.resolve(opfPath, href)
    val xhtml = readEntries(context, fileUri) { it == path }
        .values.firstOrNull()?.decodeToString() ?: return ""

    // Only the images this chapter actually references get pulled out of the
    // zip; a textbook holds hundreds and loading them all would defeat the
    // point of reading one chapter at a time
    val wanted = IMG_SRC.findAll(xhtml)
        .map { Epub.resolve(path, it.groupValues[2]) }
        .filter { !it.startsWith("http") }
        .toSet()
    val images = if (wanted.isEmpty()) emptyMap() else readEntries(context, fileUri) { it in wanted }

    val body = xhtml.substringAfter("<body", "").substringAfter(">").substringBeforeLast("</body>")
        .ifEmpty { xhtml }

    return IMG_SRC.replace(body) { m ->
        val resolved = Epub.resolve(path, m.groupValues[2])
        val bytes = images[resolved] ?: return@replace m.value
        val mime = when {
            resolved.endsWith(".png", true) -> "image/png"
            resolved.endsWith(".gif", true) -> "image/gif"
            resolved.endsWith(".svg", true) -> "image/svg+xml"
            else -> "image/jpeg"
        }
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        "${m.groupValues[1]}data:$mime;base64,$b64${m.groupValues[3]}"
    }
}
