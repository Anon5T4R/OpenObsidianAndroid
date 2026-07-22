package com.openobsidian.android.ui.screens

import android.app.Activity
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openobsidian.android.R
import com.openobsidian.android.data.AppSettings
import com.openobsidian.android.data.LocalAppSettings
import com.openobsidian.android.data.Node
import com.openobsidian.android.data.Srs
import com.openobsidian.android.data.TagComplete
import com.openobsidian.android.data.VaultRepository
import com.openobsidian.android.ui.components.DocxViewer
import com.openobsidian.android.ui.components.EpubViewer
import com.openobsidian.android.ui.components.FileTreeContent
import com.openobsidian.android.ui.components.MarkdownEditor
import com.openobsidian.android.ui.components.MarkdownPreview
import com.openobsidian.android.ui.components.PdfViewer
import com.openobsidian.android.ui.components.PreviewScrollConnection
import com.openobsidian.android.ui.components.TocSheetContent
import com.openobsidian.android.ui.components.parseHeadings
import com.openobsidian.android.ui.components.plainHeadingText
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

    // Estado por nota: sumário (TOC) e barra de buscar/substituir
    var tocOpen     by remember(state.activeFile?.uri) { mutableStateOf(false) }
    var findBarOpen by remember(state.activeFile?.uri) { mutableStateOf(false) }

    // O destino do backup é escolhido pelo seletor do sistema: assim o .zip
    // pode ir para o Drive, para o cartão SD ou para onde o usuário quiser,
    // sem o app pedir permissão de armazenamento nenhuma.
    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { dest -> if (dest != null) vm.backupVault(dest) }

    // O .apkg vem do seletor do sistema, como o backup
    val ankiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { picked -> if (picked != null) vm.readAnkiPackage(picked) }

    // Exportar HTML reusa o mesmo HTML que já é montado para imprimir em PDF.
    // O conteúdo é lido no momento do clique e guardado aqui, porque o retorno
    // do seletor é assíncrono e a nota pode ter mudado no caminho.
    var htmlToExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    val htmlPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html"),
    ) { dest ->
        val pending = htmlToExport
        htmlToExport = null
        if (dest != null && pending != null) vm.exportHtml(dest, pending.first, pending.second)
    }

    // Contador, não booleano: tocar o botão duas vezes seguidas precisa abrir
    // o menu as duas vezes, e um booleano já em `true` não notificaria nada.
    var insertRequest by remember { mutableStateOf<Int?>(null) }

    // Barra lateral fixa (tela larga/paisagem): recolher/expandir.
    // No retrato (modal) isso não se aplica — lá o gesto de puxar cuida disso.
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }

    // Auto-close drawer when file opens (phone only)
    LaunchedEffect(state.activeFile) {
        if (!isTablet && state.activeFile != null && drawerState.isOpen) drawerState.close()
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

    // ── Review overlay (covers everything, like search) ───────────────────
    if (state.reviewOpen) {
        ReviewScreen(
            card           = state.currentCard,
            revealed       = state.reviewRevealed,
            done           = state.reviewDone,
            remaining      = (state.reviewQueue.size - state.reviewIndex).coerceAtLeast(0),
            totalCards     = state.srsStats.total,
            scheduleFailed = state.reviewError,
            onReveal       = { vm.revealAnswer() },
            onGrade        = { grade -> vm.gradeCurrent(grade) },
            onClose        = { vm.closeReview() },
        )
        return
    }

    // ── Review statistics overlay ─────────────────────────────────────────
    if (state.statsOpen) {
        StatsScreen(report = state.srsReport, onClose = { vm.closeStats() })
        return
    }

    // ── Vault diagnostics overlay ─────────────────────────────────────────
    if (state.diagnosticsOpen) {
        DiagnosticsScreen(
            brokenLinks    = state.brokenLinks,
            orphanNotes    = state.orphanNotes,
            duplicateNames = state.duplicateNames,
            onClose        = { vm.closeDiagnostics() },
        )
        return
    }

    // ── Anki import: confirma antes de escrever no vault ──────────────────
    state.ankiPending?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.cancelAnkiImport() },
            title   = { Text(stringResource(R.string.anki_confirm_title)) },
            text    = {
                Column {
                    Text(stringResource(R.string.anki_confirm_body, pending.cards.size, pending.notes, pending.deck))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.anki_media_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmAnkiImport() }) { Text(stringResource(R.string.anki_import)) } },
            dismissButton = { TextButton(onClick = { vm.cancelAnkiImport() }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (state.ankiNeedsLegacy) {
        AlertDialog(
            onDismissRequest = { vm.cancelAnkiImport() },
            title   = { Text(stringResource(R.string.anki_confirm_title)) },
            text    = { Text(stringResource(R.string.anki_needs_legacy)) },
            confirmButton = { TextButton(onClick = { vm.cancelAnkiImport() }) { Text(stringResource(R.string.action_ok)) } },
        )
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
            onMoveNode     = { node, target -> vm.moveNode(node, target) },
            onTogglePin    = { vm.togglePin(it) },
            onDailyNote    = { vm.openDailyNote() },
            onReview       = { vm.startReview() },
            dueCards       = state.srsStats.due,
            onDiagnostics  = { vm.openDiagnostics() },
            onStats        = { vm.openStats() },
            onAnkiImport   = { ankiPicker.launch(arrayOf("*/*")) },
            onRandomNote   = { vm.openRandomNote() },
            onBackup       = { backupPicker.launch(vm.backupFileName()) },
            templates      = state.tree?.templates ?: emptyList(),
            onCreateFromTemplate = { t, n -> vm.createFromTemplate(t, n) },
            // Botão de recolher só na barra fixa (tela larga); no modal é null.
            onCollapseSidebar = if (isTablet) ({ sidebarCollapsed = true }) else null,
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
                                    stringResource(R.string.unsaved),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        when {
                            // Retrato/telas estreitas: hambúrguer abre o drawer modal.
                            !isTablet -> IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.cd_open_menu))
                            }
                            // Tela larga com a barra recolhida: hambúrguer re-expande.
                            sidebarCollapsed -> IconButton(onClick = { sidebarCollapsed = false }) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.cd_expand_sidebar))
                            }
                            // Tela larga com a barra visível: sem ícone aqui (a própria
                            // barra tem o botão de recolher no cabeçalho).
                        }
                    },
                    actions = {
                        if (state.activeFile?.isText == true) {
                            ViewModeToggle(current = state.viewMode, onSelect = { vm.setViewMode(it) })
                            // Typing `/` at line start is not something anyone
                            // discovers on a phone. Without a button the whole
                            // catalogue — flashcards, callouts, diagrams — is
                            // reachable only by someone who already knows it.
                            if (state.viewMode != ViewMode.PREVIEW) {
                                IconButton(onClick = { insertRequest = (insertRequest ?: 0) + 1 }) {
                                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.insert_title))
                                }
                            }
                        }
                        if (state.tree != null) {
                            IconButton(onClick = { vm.navBack() },    enabled = state.canNavBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                            IconButton(onClick = { vm.navForward() }, enabled = state.canNavForward) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.action_forward))
                            }
                            // Always here once a vault is open. Hiding it until
                            // the vault had cards made the whole feature
                            // undiscoverable: there was no way to find out it
                            // existed, and nothing to tell you how to write one.
                            IconButton(onClick = { vm.startReview() }) {
                                BadgedBox(badge = {
                                    if (state.srsStats.due > 0) Badge { Text("${state.srsStats.due}") }
                                }) {
                                    Icon(Icons.Default.Style, contentDescription = stringResource(R.string.cd_review))
                                }
                            }
                            IconButton(onClick = { vm.openSearch() }) {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search))
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
                        }
                        // Overflow menu for note-specific actions (Export PDF).
                        if (state.activeFile?.isText == true) {
                            var showNoteMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showNoteMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
                                }
                                DropdownMenu(
                                    expanded         = showNoteMenu,
                                    onDismissRequest = { showNoteMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text        = { Text(stringResource(R.string.menu_outline)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = null) },
                                        onClick     = {
                                            showNoteMenu = false
                                            tocOpen = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text        = { Text(stringResource(R.string.menu_find_in_note)) },
                                        leadingIcon = { Icon(Icons.Default.FindInPage, contentDescription = null) },
                                        onClick     = {
                                            showNoteMenu = false
                                            // A barra vive no editor — garante um modo com editor visível.
                                            if (state.viewMode == ViewMode.PREVIEW) vm.setViewMode(ViewMode.EDIT)
                                            findBarOpen = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text        = { Text(stringResource(R.string.menu_diagnostics)) },
                                        leadingIcon = { Icon(Icons.Default.HealthAndSafety, contentDescription = null) },
                                        onClick     = {
                                            showNoteMenu = false
                                            vm.openDiagnostics()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text        = { Text(stringResource(R.string.menu_export_pdf)) },
                                        leadingIcon = { Icon(Icons.Default.Print, contentDescription = null) },
                                        onClick     = {
                                            showNoteMenu = false
                                            exportToPdf(
                                                context = context,
                                                content = state.activeContent,
                                                title   = state.activeFile?.displayName ?: "note",
                                            )
                                        },
                                    )
                                    DropdownMenuItem(
                                        text        = { Text(stringResource(R.string.menu_export_html)) },
                                        leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                                        onClick     = {
                                            showNoteMenu = false
                                            val title = state.activeFile?.displayName ?: "note"
                                            htmlToExport = buildPrintHtml(state.activeContent, title) to title
                                            htmlPicker.launch("$title.html")
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            },
        ) { inner ->
            Box(
                modifier = Modifier
                    .padding(inner)
                    .fillMaxSize()
                    // Shrink content above the soft keyboard so the end of a
                    // note stays visible while typing (edge-to-edge window).
                    .imePadding()
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
                            // Inverted once per index change, not per keystroke
                            insertRequest       = insertRequest,
                            onInsertRequestHandled = { insertRequest = null },
                            tagCounts           = remember(state.tagsByPath) {
                                TagComplete.countTags(state.tagsByPath)
                            },
                            // Was an exact-name find(), so [[Nota#Seção]],
                            // [[Pasta/Nota]] and any alias did nothing at all
                            onWikilinkClick     = { target -> vm.openByLink(target) },
                            backlinks           = activeBacklinks,
                            onBacklinkClick     = { vm.openFile(it) },
                            onOpenCompanionNote = { vm.openCompanionNote(state.activeFile!!) },
                            resolveImage        = { name -> vm.resolveFile(name) },
                            runQuery            = { src -> vm.runQueryBlock(src) },
                            onConvertDocx       = { vm.convertDocxToMd(state.activeFile!!) },
                            onImportImage       = { uri -> vm.importImage(uri) },
                            tocOpen             = tocOpen,
                            onTocDismiss        = { tocOpen = false },
                            findBarOpen         = findBarOpen,
                            onFindBarClose      = { findBarOpen = false },
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
    // Tela larga (tablet/paisagem): barra lateral fixa, mas recolhível.
    if (isTablet) {
        Row(Modifier.fillMaxSize()) {
            if (!sidebarCollapsed) {
                Surface(
                    modifier = Modifier.width(280.dp).fillMaxHeight(),
                    color    = MaterialTheme.colorScheme.surface,
                ) {
                    // O Surface preenche até o topo (fundo contínuo atrás da
                    // status bar), mas o conteúdo é empurrado para baixo das
                    // barras de sistema e do notch (paisagem) — senão o
                    // relógio/wifi/bateria ficam por cima do cabeçalho.
                    Box(
                        Modifier.windowInsetsPadding(
                            WindowInsets.systemBars
                                .union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Start + WindowInsetsSides.Top + WindowInsetsSides.Bottom)
                        )
                    ) {
                        drawerContent()
                    }
                }
                VerticalDivider()
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                scaffoldContent()
            }
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
    runQuery: ((String) -> Pair<List<String>, List<String>>)? = null,
    onConvertDocx: () -> Unit = {},
    onImportImage: (suspend (Uri) -> String?)? = null,
    tocOpen: Boolean = false,
    onTocDismiss: () -> Unit = {},
    findBarOpen: Boolean = false,
    onFindBarClose: () -> Unit = {},
    /** tag -> quantas notas a carregam, para o autocomplete do # */
    tagCounts: Map<String, Int> = emptyMap(),
    insertRequest: Int? = null,
    onInsertRequestHandled: () -> Unit = {},
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
    if (file.isEpub) {
        EpubViewer(
            fileUri     = file.uri,
            onOpenNotes = onOpenCompanionNote,
            modifier    = Modifier.fillMaxSize(),
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

    // Sumário (TOC): ponte de scroll com o preview + salto de cursor no editor
    val previewConn = remember { PreviewScrollConnection() }
    var editorCursorRequest by remember { mutableStateOf<Int?>(null) }

    // Content fills the full area — no reserved space at the bottom for backlinks
    Box(Modifier.fillMaxSize()) {
        when (viewMode) {
            ViewMode.EDIT -> MarkdownEditor(
                content, onContentChange, Modifier.fillMaxSize(),
                onImportImage          = onImportImage,
                tagCounts              = tagCounts,
                insertRequest          = insertRequest,
                onInsertRequestHandled = onInsertRequestHandled,
                cursorRequest          = editorCursorRequest,
                onCursorRequestHandled = { editorCursorRequest = null },
                findBarVisible         = findBarOpen,
                onFindBarClose         = onFindBarClose,
            )
            ViewMode.PREVIEW -> MarkdownPreview(
                content          = content,
                modifier         = Modifier.fillMaxSize(),
                onWikilinkClick  = onWikilinkClick,
                resolveImage     = resolveImage,
                runQuery         = runQuery,
                onToggleCheckbox = onContentChange,
                scrollConnection = previewConn,
            )
            ViewMode.SPLIT -> {
                Row(Modifier.fillMaxSize()) {
                    MarkdownEditor(
                        content, onContentChange, Modifier.weight(1f).fillMaxHeight(),
                        onImportImage          = onImportImage,
                        tagCounts              = tagCounts,
                        insertRequest          = insertRequest,
                        onInsertRequestHandled = onInsertRequestHandled,
                        cursorRequest          = editorCursorRequest,
                        onCursorRequestHandled = { editorCursorRequest = null },
                        findBarVisible         = findBarOpen,
                        onFindBarClose         = onFindBarClose,
                    )
                    VerticalDivider()
                    MarkdownPreview(
                        content          = content,
                        modifier         = Modifier.weight(1f).fillMaxHeight(),
                        onWikilinkClick  = onWikilinkClick,
                        resolveImage     = resolveImage,
                runQuery         = runQuery,
                        onToggleCheckbox = onContentChange,
                        scrollConnection = previewConn,
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

    // Sumário (TOC) — tocar num heading rola o preview ou move o cursor
    if (tocOpen) {
        val headings = remember(content) { parseHeadings(content) }
        ModalBottomSheet(onDismissRequest = onTocDismiss) {
            TocSheetContent(headings) { index, h ->
                onTocDismiss()
                if (viewMode == ViewMode.EDIT) {
                    editorCursorRequest = h.offset
                } else {
                    val plain = plainHeadingText(h.text)
                    val occurrence = headings.take(index)
                        .count { plainHeadingText(it.text) == plain }
                    previewConn.scrollToHeading(plain, occurrence)
                }
            }
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
            Text(stringResource(R.string.vault_read_error), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
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
            Text(stringResource(R.string.pick_a_note), style = MaterialTheme.typography.headlineSmall)
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
                Text(stringResource(R.string.cd_open_menu))
            }
        }
    }
}
