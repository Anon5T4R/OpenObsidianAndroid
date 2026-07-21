package com.openobsidian.android.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.openobsidian.android.R
import com.openobsidian.android.data.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Native PDF viewer backed by Android's PdfRenderer API.
 *
 * - Pages are rendered lazily as they scroll into view.
 * - Up to 12 pages are cached as bitmaps (LruCache).
 * - A "Notes" FAB opens/creates a companion markdown note.
 */
@Composable
fun PdfViewer(
    file: Node.File,
    onOpenCompanionNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context   = LocalContext.current
    val pdfState  = remember(file.uri) { PdfViewerState(context, file.uri) }

    LaunchedEffect(file.uri) { pdfState.init() }

    DisposableEffect(file.uri) {
        onDispose { pdfState.close() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            pdfState.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            pdfState.error != null -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Cannot render PDF:\n${pdfState.error}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> {
                val listState = rememberLazyListState()

                LazyColumn(
                    state          = listState,
                    modifier       = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(pdfState.pageCount) { index ->
                        PdfPageItem(state = pdfState, pageIndex = index)
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                }

                // Page counter badge
                val visiblePage = listState.firstVisibleItemIndex + 1
                Surface(
                    modifier      = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    shape         = MaterialTheme.shapes.small,
                    color         = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                    tonalElevation = 2.dp,
                ) {
                    Text(
                        "$visiblePage / ${pdfState.pageCount}",
                        style    = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }

                // Companion notes FAB
                FloatingActionButton(
                    onClick        = onOpenCompanionNote,
                    modifier       = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier            = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment   = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.notes_button), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PdfPageItem(state: PdfViewerState, pageIndex: Int) {
    val density       = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val widthPx       = remember(screenWidthDp) { with(density) { screenWidthDp.dp.roundToPx() } }

    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pageIndex, widthPx) {
        bitmap = state.getPage(pageIndex, widthPx)
    }

    if (bitmap != null) {
        Image(
            bitmap             = bitmap!!.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            contentScale       = ContentScale.FillWidth,
            modifier           = Modifier.fillMaxWidth(),
        )
    } else {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.707f),   // A4-ish placeholder
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PdfViewerState — manages PdfRenderer lifecycle + LRU page cache
// ─────────────────────────────────────────────────────────────────────────────

class PdfViewerState(private val context: Context, val uri: Uri) {

    var pageCount by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val mutex = Mutex()
    // Cache up to 12 rendered page bitmaps
    private val cache = LruCache<Int, Bitmap>(12)

    suspend fun init() = withContext(Dispatchers.IO) {
        try {
            pfd      = context.contentResolver.openFileDescriptor(uri, "r")
            renderer = PdfRenderer(pfd!!)
            pageCount = renderer!!.pageCount
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        } finally {
            loading = false
        }
    }

    suspend fun getPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(index)?.let { return@withContext it }
        mutex.withLock {
            cache.get(index)?.let { return@withLock it }        // double-check after lock
            val r = renderer ?: return@withLock null
            runCatching {
                val page  = r.openPage(index)
                val scale = widthPx.toFloat() / page.width
                val h     = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp   = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                cache.put(index, bmp)
                bmp
            }.getOrNull()
        }
    }

    fun close() {
        renderer?.close()
        pfd?.close()
        renderer = null
        pfd      = null
    }
}
