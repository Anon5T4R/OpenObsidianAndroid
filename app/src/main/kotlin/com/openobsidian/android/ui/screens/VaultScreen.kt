package com.openobsidian.android.ui.screens

import android.app.Activity
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openobsidian.android.data.AppSettings
import com.openobsidian.android.data.LocalAppSettings
import com.openobsidian.android.data.Node
import com.openobsidian.android.data.VaultRepository
import com.openobsidian.android.ui.components.DocxViewer
import com.openobsidian.android.ui.components.FileTreeContent
import com.openobsidian.android.ui.components.GraphView
import com.openobsidian.android.ui.components.MarkdownEditor
import com.openobsidian.android.ui.components.MarkdownPreview
import com.openobsidian.android.ui.components.PdfViewer
import com.openobsidian.android.viewmodel.ViewMode
import com.openobsidian.android.viewmodel.VaultViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    vaultUri: String,
    repo: VaultRepository,
    onOpenSettings: () -> Unit = {},
) {
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val uri       = remember(vaultUri) { Uri.parse(vaultUri) }
    val settings  = LocalAppSettings.current
    val isTablet  = LocalConfiguration.current.screenWidthDp >= 840

    val vm: VaultViewModel = viewModel(
        key     = vaultUri,
        factory = VaultViewModel.factory(context.applicationContext, uri),
    )
    val state       by vm.state.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Auto-close drawer when file opens (phone only)
    LaunchedEffect(state.activeFile) {
        if (!isTablet && state.activeFile != null && drawerState.isOpen) drawerState.close()
    }

    // ── Graph overlay (full-screen, covers everything) ────────────────────
    var showGraph by remember { mutableStateOf(false) }
    if (showGraph) {
        BackHandler { showGraph = false }
        GraphView(
            nodes         = state.tree?.allMarkdownFiles ?: emptyList(),
            backlinks     = state.backlinks,
            activeFileUri = state.activeFile?.uri?.toString(),
            onNodeClick   = { file -> vm.openFile(file); showGraph = false },
            onClose       = { showGraph = false },
            modifier      = Modifier.fillMaxSize(),
        )
        return
    }

    // ── Search overlay (covers everything) ────────────────────────────────
    if (state.searchOpen) {
        SearchScreen(
            query         = state.searchQuery,
            results       = state.searchResults,
            indexing      = state.indexing,
            totalNotes    = state.tree?.allMarkdownFiles?.size ?: 0,
            onQueryChange = { vm.setSearchQuery(it) },
            onResultClick = { file -> vm.openFile(file); vm.closeSearch() },
            onClose       = { vm.closeSearch() },
        )
        return
    }

    // ── Android Back: navigate within the app instead of closing it ───────
    // Priority: open drawer → close it; nav history → step back; open note → close it.
    // Disabled (lets the system exit) only at the root with nothing open.
    BackHandler(enabled = drawerState.isOpen || state.activeFile != null) {
        when {
            drawerState.isOpen     -> scope.launch { drawerState.close() }
            state.canNavBack       -> vm.navBack()
            state.activeFile != null -> vm.closeFile()
        }
    }

    // ── Shared drawer content ─────────────────────────────────────────────
    val drawerContent: @Composable () -> Unit = {
        FileTreeContent(
            tree           = state.tree,
            activeFileUri  = state.activeFile?.uri?.toString(),
            loading        = state.loading,
            sortOrder      = settings.sortOrder,
            pinnedUris     = state.pinnedUris,
            onFileClick    = { vm.openFile(it) },
            onSwitchVault  = { scope.launch { repo.forgetVault(vaultUri) } },
            onRefresh      = { vm.loadTree() },
            onCreateFile   = { pu, n -> vm.createFile(pu, n) },
            onCreateFolder = { pu, n -> vm.createFolder(pu, n) },
            onRenameNode   = { node, n -> vm.renameNode(node, n) },
            onDeleteNode   = { vm.deleteNode(it) },
            onTogglePin    = { vm.togglePin(it) },
            onDailyNote    = { vm.openDailyNote() },
        )
    }

    // ── Shared scaffold ───────────────────────────────────────────────────
    val scaffoldContent: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                state.activeFile?.displayName ?: (state.tree?.name ?: "OpenObsidian"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.isDirty) {
                                Text(
                                    "Unsaved",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        // Hide hamburger on tablet (drawer is always visible)
                        if (!isTablet) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Open menu")
                            }
                        }
                    },
                    actions = {
                        if (state.activeFile?.isText == true) {
                            ViewModeToggle(current = state.viewMode, onSelect = { vm.setViewMode(it) })
                            // Export as PDF
                            IconButton(
                                onClick = {
                                    exportToPdf(
                                        context = context,
                                        content = state.activeContent,
                                        title   = state.activeFile?.displayName ?: "note",
                                    )
                                }
                            ) {
                                Icon(Icons.Default.Print, contentDescription = "Export as PDF")
                            }
                        }
                        if (state.tree != null) {
                            IconButton(onClick = { showGraph = true }) {
                                Icon(Icons.Default.AccountTree, contentDescription = "Graph view")
                            }
                            IconButton(onClick = { vm.navBack() },    enabled = state.canNavBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            IconButton(onClick = { vm.navForward() }, enabled = state.canNavForward) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                            }
                            IconButton(onClick = { vm.openSearch() }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }
                        if (state.activeFile != null) {
                            IconButton(onClick = { vm.closeFile() }) {
                                Icon(Icons.Default.Close, contentDescription = "Close note")
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
        ) { inner ->
            Box(
                modifier = Modifier
                    .padding(inner)
                    .fillMaxSize()
                    // Hardware keyboard shortcuts
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when {
                                event.isCtrlPressed && event.key == Key.S -> {
                                    vm.saveCurrentFile(); true
                                }
                                event.isCtrlPressed && event.key == Key.F -> {
                                    vm.openSearch(); true
                                }
                                event.key == Key.Escape -> {
                                    if (state.activeFile != null) { vm.closeFile(); true }
                                    else false
                                }
                                else -> false
                            }
                        } else false
                    },
            ) {
                when {
                    state.error != null ->
                        ErrorBox(state.error!!) { vm.loadTree() }

                    state.activeFile != null -> {
                        val activeBacklinks = state.activeFile?.let { f ->
                            state.backlinks[f.displayName.lowercase()] ?: emptyList()
                        } ?: emptyList()
                        NoteArea(
                            file                = state.activeFile!!,
                            content             = state.activeContent,
                            contentLoading      = state.contentLoading,
                            viewMode            = state.viewMode,
                            onContentChange     = { vm.updateContent(it) },
                            onWikilinkClick     = { name ->
                                state.tree?.allMarkdownFiles
                                    ?.find { it.displayName.equals(name, ignoreCase = true) }
                                    ?.let { vm.openFile(it) }
                            },
                            backlinks           = activeBacklinks,
                            onBacklinkClick     = { vm.openFile(it) },
                            onOpenCompanionNote = { vm.openCompanionNote(state.activeFile!!) },
                            resolveImage        = { name -> vm.resolveFile(name) },
                            onConvertDocx       = { vm.convertDocxToMd(state.activeFile!!) },
                        )
                    }

                    state.loading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    else -> EmptyVaultHero(onOpenMenu = { scope.launch { drawerState.open() } })
                }
            }
        }
    }

    // ── Choose drawer type based on screen width ──────────────────────────
    if (isTablet) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(
                    modifier             = Modifier.width(280.dp),
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                ) {
                    drawerContent()
                }
            },
        ) {
            scaffoldContent()
        }
    } else {
        ModalNavigationDrawer(
            drawerState   = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    modifier             = Modifier.fillMaxWidth(0.85f),
                ) {
                    drawerContent()
                }
            },
        ) {
            scaffoldContent()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PDF export
// ─────────────────────────────────────────────────────────────────────────────

private fun exportToPdf(context: android.content.Context, content: String, title: String) {
    val html      = buildPrintHtml(content, title)
    val decorView = (context as? Activity)?.window?.decorView as? android.view.ViewGroup ?: return
    val wv        = WebView(context)
    wv.visibility = android.view.View.INVISIBLE
    decorView.addView(wv, android.view.ViewGroup.LayoutParams(1, 1))
    wv.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            val pm = context.getSystemService(android.content.Context.PRINT_SERVICE) as? PrintManager
            pm?.print(title, view.createPrintDocumentAdapter(title), PrintAttributes.Builder().build())
            runCatching { decorView.removeView(wv) }
        }
    }
    wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
}

private fun buildPrintHtml(markdown: String, title: String): String {
    val lines = markdown.split("\n")
    val sb    = StringBuilder()
    var inCode = false

    for (line in lines) {
        when {
            line.startsWith("```") -> {
                if (inCode) { sb.append("</code></pre>\n"); inCode = false }
                else        { sb.append("<pre><code>"); inCode = true }
            }
            inCode -> sb.append(htmlEscape(line) + "\n")
            line.startsWith("#### ") -> sb.append("<h4>${applyInline(line.drop(5))}</h4>\n")
            line.startsWith("### ")  -> sb.append("<h3>${applyInline(line.drop(4))}</h3>\n")
            line.startsWith("## ")   -> sb.append("<h2>${applyInline(line.drop(3))}</h2>\n")
            line.startsWith("# ")    -> sb.append("<h1>${applyInline(line.drop(2))}</h1>\n")
            line.startsWith("> ")    -> sb.append("<blockquote>${applyInline(line.drop(2))}</blockquote>\n")
            line.matches(Regex("^[-*] .+")) ->
                sb.append("<li>${applyInline(line.drop(2))}</li>\n")
            line.matches(Regex("^\\d+\\. .+")) ->
                sb.append("<li>${applyInline(line.substringAfter(". "))}</li>\n")
            line.matches(Regex("^---+$")) -> sb.append("<hr>\n")
            line.isBlank() -> sb.append("<br>\n")
            else -> sb.append("<p>${applyInline(line)}</p>\n")
        }
    }

    return """<!DOCTYPE html>
<html><head><meta charset="UTF-8"><title>${htmlEscape(title)}</title>
<style>
body{font-family:Georgia,serif;font-size:12pt;line-height:1.6;max-width:800px;margin:0 auto;padding:20px;}
h1{font-size:20pt;}h2{font-size:16pt;}h3{font-size:14pt;}h4{font-size:12pt;}
code{background:#f4f4f4;padding:2px 4px;font-family:monospace;border-radius:2px;}
pre{background:#f4f4f4;padding:12px;border-radius:4px;}pre code{background:none;padding:0;}
blockquote{border-left:4px solid #ccc;margin:0 0 0 4px;padding:0 16px;color:#555;}
hr{border:none;border-top:1px solid #ccc;margin:16px 0;}
li{margin-bottom:4px;}
</style></head><body>$sb</body></html>"""
}

private fun htmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun applyInline(raw: String): String {
    var s = htmlEscape(raw)
    s = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
    s = s.replace(Regex("\\*(.+?)\\*"),       "<em>$1</em>")
    s = s.replace(Regex("~~(.+?)~~"),         "<del>$1</del>")
    s = s.replace(Regex("`(.+?)`"),           "<code>$1</code>")
    return s
}

// ─────────────────────────────────────────────────────────────────────────────
// View-mode toggle
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ViewModeToggle(current: ViewMode, onSelect: (ViewMode) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ViewMode.entries.forEach { mode ->
            val selected = mode == current
            val (icon, desc) = when (mode) {
                ViewMode.EDIT    -> Icons.Default.Edit       to "Edit"
                ViewMode.PREVIEW -> Icons.Default.Visibility to "Preview"
                ViewMode.SPLIT   -> Icons.Default.ViewAgenda to "Split"
            }
            IconButton(onClick = { onSelect(mode) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    icon, desc,
                    modifier = Modifier.size(20.dp),
                    tint     = if (selected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Note area
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteArea(
    file: Node.File,
    content: String,
    contentLoading: Boolean,
    viewMode: ViewMode,
    onContentChange: (String) -> Unit,
    onWikilinkClick: (String) -> Unit = {},
    backlinks: List<Node.File> = emptyList(),
    onBacklinkClick: (Node.File) -> Unit = {},
    onOpenCompanionNote: () -> Unit = {},
    resolveImage: (String) -> Uri? = { null },
    onConvertDocx: () -> Unit = {},
) {
    if (file.isPdf) {
        PdfViewer(
            file                = file,
            onOpenCompanionNote = onOpenCompanionNote,
            modifier            = Modifier.fillMaxSize(),
        )
        return
    }
    if (file.isDocx) {
        DocxViewer(
            file                = file,
            onOpenCompanionNote = onOpenCompanionNote,
            onConvertToMd       = onConvertDocx,
            modifier            = Modifier.fillMaxSize(),
        )
        return
    }
    if (contentLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var backlinkSheetOpen  by remember { mutableStateOf(false) }
    val showBacklinksButton = backlinks.isNotEmpty() && viewMode != ViewMode.EDIT

    // Content fills the full area — no reserved space at the bottom for backlinks
    Box(Modifier.fillMaxSize()) {
        when (viewMode) {
            ViewMode.EDIT -> MarkdownEditor(
                content, onContentChange, Modifier.fillMaxSize()
            )
            ViewMode.PREVIEW -> MarkdownPreview(
                content          = content,
                modifier         = Modifier.fillMaxSize(),
                onWikilinkClick  = onWikilinkClick,
                resolveImage     = resolveImage,
                onToggleCheckbox = onContentChange,
            )
            ViewMode.SPLIT -> {
                Row(Modifier.fillMaxSize()) {
                    MarkdownEditor(content, onContentChange, Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    MarkdownPreview(
                        content          = content,
                        modifier         = Modifier.weight(1f).fillMaxHeight(),
                        onWikilinkClick  = onWikilinkClick,
                        resolveImage     = resolveImage,
                        onToggleCheckbox = onContentChange,
                    )
                }
            }
        }

        // Floating backlinks button — tapping opens a bottom sheet
        if (showBacklinksButton) {
            ExtendedFloatingActionButton(
                onClick        = { backlinkSheetOpen = true },
                icon           = { Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp)) },
                text           = { Text("${backlinks.size}") },
                modifier       = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor   = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    // Bottom sheet — shown on demand, never overlaps the reading area
    if (backlinkSheetOpen) {
        ModalBottomSheet(onDismissRequest = { backlinkSheetOpen = false }) {
            BacklinksSheetContent(
                backlinks   = backlinks,
                onItemClick = { file ->
                    onBacklinkClick(file)
                    backlinkSheetOpen = false
                },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Backlinks sheet content (shown inside ModalBottomSheet)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BacklinksSheetContent(
    backlinks: List<Node.File>,
    onItemClick: (Node.File) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 32.dp)) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Link, null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Backlinks (${backlinks.size})",
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HorizontalDivider()
        backlinks.forEach { file ->
            ListItem(
                headlineContent = {
                    Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.Article, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.clickable { onItemClick(file) },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Placeholder composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ErrorOutline, null, Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Text("Couldn't read the vault", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun EmptyVaultHero(onOpenMenu: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⬡", style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Pick a note", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Open the menu to browse your vault.\nLong-press a file or folder to rename or delete.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onOpenMenu) {
                Icon(Icons.Default.Menu, null)
                Spacer(Modifier.width(8.dp))
                Text("Open menu")
            }
        }
    }
}
