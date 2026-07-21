package com.openobsidian.android.ui.components

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openobsidian.android.R
import com.openobsidian.android.data.Node
import com.openobsidian.android.data.SortOrder
import com.openobsidian.android.data.Tree

// ─────────────────────────────────────────────────────────────────────────────
// Flat row model
// ─────────────────────────────────────────────────────────────────────────────

private sealed class Row(open val key: String, open val depth: Int) {
    data class SectionHeader(
        val title: String,
        override val key: String,
        override val depth: Int = 0,
    ) : Row(key, depth)

    data class DirRow(
        val node: Node.Dir,
        override val depth: Int,
        val expanded: Boolean,
        override val key: String = node.uri.toString(),
    ) : Row(key, depth)

    data class FileRow(
        val node: Node.File,
        override val depth: Int,
        val isPinned: Boolean = false,
        override val key: String = node.uri.toString(),
    ) : Row(key, depth)
}

private fun findFile(nodes: List<Node>, uri: String): Node.File? {
    for (n in nodes) {
        when (n) {
            is Node.File -> if (n.uri.toString() == uri) return n
            is Node.Dir  -> findFile(n.children, uri)?.let { return it }
        }
    }
    return null
}

private fun buildRows(
    tree: Tree,
    expanded: Set<String>,
    filter: String,
    sortOrder: SortOrder,
    pinnedUris: Set<String>,
): List<Row> {
    val out    = ArrayList<Row>()
    val needle = filter.trim().lowercase()

    // ── Pinned section (only when no filter) ──────────────────────────────
    if (needle.isEmpty() && pinnedUris.isNotEmpty()) {
        val pinned = pinnedUris.mapNotNull { findFile(tree.root, it) }
        if (pinned.isNotEmpty()) {
            out += Row.SectionHeader("Pinned", "section:pinned")
            pinned.forEach { f ->
                out += Row.FileRow(f, 0, isPinned = true, key = "pin:${f.uri}")
            }
            if (tree.root.isNotEmpty()) {
                out += Row.SectionHeader("All Files", "section:all")
            }
        }
    }

    // ── Sort helper: dirs first (always alpha), files by chosen order ──────
    fun sortNodes(nodes: List<Node>): List<Node> {
        val dirs  = nodes.filterIsInstance<Node.Dir>().sortedBy { it.name.lowercase() }
        val files = nodes.filterIsInstance<Node.File>()
        val sortedFiles = when (sortOrder) {
            SortOrder.NAME_ASC  -> files.sortedBy { it.displayName.lowercase() }
            SortOrder.NAME_DESC -> files.sortedByDescending { it.displayName.lowercase() }
            SortOrder.MODIFIED  -> files.sortedByDescending { it.mtime }
        }
        return dirs + sortedFiles
    }

    // ── Filter match ───────────────────────────────────────────────────────
    fun matches(n: Node): Boolean =
        needle.isEmpty() || when (n) {
            is Node.File -> n.displayName.lowercase().contains(needle)
            is Node.Dir  -> n.children.any { matches(it) }
        }

    // ── Walk ───────────────────────────────────────────────────────────────
    fun walk(nodes: List<Node>, depth: Int) {
        for (n in sortNodes(nodes)) {
            if (!matches(n)) continue
            when (n) {
                is Node.Dir -> {
                    val isOpen = expanded.contains(n.uri.toString()) || needle.isNotEmpty()
                    out += Row.DirRow(n, depth, isOpen)
                    if (isOpen) walk(n.children, depth + 1)
                }
                is Node.File -> out += Row.FileRow(n, depth)
            }
        }
    }
    walk(tree.root, 0)
    return out
}

// ─────────────────────────────────────────────────────────────────────────────
// Pending dialog state
// ─────────────────────────────────────────────────────────────────────────────

private sealed class PendingDialog {
    data class CreateFile(val parentUri: Uri)   : PendingDialog()
    data class CreateFolder(val parentUri: Uri) : PendingDialog()
    data class Rename(val node: Node)           : PendingDialog()
    data class ConfirmDelete(val node: Node)    : PendingDialog()
    data class Move(val node: Node)             : PendingDialog()
    object PickTemplate                          : PendingDialog()
    data class CreateFromTemplate(val template: Node.File) : PendingDialog()
}

// ─────────────────────────────────────────────────────────────────────────────
// Public composable
// ─────────────────────────────────────────────────────────────────────────────

// BadgedBox (contador de cartões) ainda é experimental no Material 3
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTreeContent(
    tree: Tree?,
    activeFileUri: String?,
    loading: Boolean,
    sortOrder: SortOrder = SortOrder.NAME_ASC,
    pinnedUris: Set<String> = emptySet(),
    onFileClick: (Node.File) -> Unit,
    onSwitchVault: () -> Unit,
    onRefresh: () -> Unit,
    onCreateFile: (parentUri: Uri, name: String) -> Unit,
    onCreateFolder: (parentUri: Uri, name: String) -> Unit,
    onRenameNode: (node: Node, newName: String) -> Unit,
    onDeleteNode: (node: Node) -> Unit,
    onMoveNode: (node: Node, targetDir: Uri) -> Unit = { _, _ -> },
    onTogglePin: (Node.File) -> Unit = {},
    onDailyNote: () -> Unit = {},
    /** Opens a flashcard session; also lives in the top bar, which gets crowded on a phone */
    onReview: () -> Unit = {},
    /** Cards due today, shown as a badge — 0 hides it */
    dueCards: Int = 0,
    /** Abre o diagnóstico do vault (links mortos, órfãs, nomes duplicados) */
    onDiagnostics: () -> Unit = {},
    /** Abre o seletor de destino do backup .zip */
    onBackup: () -> Unit = {},
    templates: List<Node.File> = emptyList(),
    onCreateFromTemplate: (template: Node.File, name: String) -> Unit = { _, _ -> },
    /** Quando não-nulo, mostra um botão de recolher a barra (tela larga/paisagem). */
    onCollapseSidebar: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable {
        mutableStateOf(
            tree?.root.orEmpty().filterIsInstance<Node.Dir>()
                .map { it.uri.toString() }.toSet()
        )
    }
    var filter      by rememberSaveable { mutableStateOf("") }
    var dialog      by remember { mutableStateOf<PendingDialog?>(null) }
    var showAddMenu   by remember { mutableStateOf(false) }
    var showVaultMenu by remember { mutableStateOf(false) }

    val rows = remember(tree, expanded, filter, sortOrder, pinnedUris) {
        if (tree == null) emptyList() else buildRows(tree, expanded, filter, sortOrder, pinnedUris)
    }

    Column(modifier = modifier.fillMaxSize()) {

        // ── Header ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tree?.name ?: "Vault",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.weight(1f).padding(start = 8.dp),
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )

            // ── Switch vault (à esquerda do calendário) ───────────────────
            IconButton(onClick = onSwitchVault, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.cd_switch_vault), modifier = Modifier.size(20.dp))
            }

            // ── Daily note ────────────────────────────────────────────────
            IconButton(onClick = onDailyNote, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.CalendarToday, contentDescription = stringResource(R.string.cd_daily_note), modifier = Modifier.size(20.dp))
            }

            // ── Flashcards ────────────────────────────────────────────────
            IconButton(onClick = onReview, modifier = Modifier.size(36.dp)) {
                BadgedBox(badge = { if (dueCards > 0) Badge { Text("$dueCards") } }) {
                    Icon(Icons.Default.Style, contentDescription = stringResource(R.string.cd_review), modifier = Modifier.size(20.dp))
                }
            }

            // ── Refresh ───────────────────────────────────────────────────
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh), modifier = Modifier.size(20.dp))
            }

            // ── Create note / folder ──────────────────────────────────────
            Box {
                IconButton(
                    onClick  = { if (tree != null) showAddMenu = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_new), modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded         = showAddMenu,
                    onDismissRequest = { showAddMenu = false },
                ) {
                    DropdownMenuItem(
                        text         = { Text(stringResource(R.string.new_note)) },
                        leadingIcon  = { Icon(Icons.Default.NoteAdd, null) },
                        onClick      = {
                            showAddMenu = false
                            tree?.let { dialog = PendingDialog.CreateFile(it.rootUri) }
                        },
                    )
                    DropdownMenuItem(
                        text         = { Text(stringResource(R.string.new_folder)) },
                        leadingIcon  = { Icon(Icons.Default.CreateNewFolder, null) },
                        onClick      = {
                            showAddMenu = false
                            tree?.let { dialog = PendingDialog.CreateFolder(it.rootUri) }
                        },
                    )
                    if (templates.isNotEmpty()) {
                        DropdownMenuItem(
                            text         = { Text(stringResource(R.string.new_from_template)) },
                            leadingIcon  = { Icon(Icons.Default.ContentCopy, null) },
                            onClick      = {
                                showAddMenu = false
                                dialog = PendingDialog.PickTemplate
                            },
                        )
                    }
                }
            }

            // ── Ações do vault ────────────────────────────────────────────
            // Aqui, e não no menu ⋮ da nota: aquele só existe com uma nota
            // aberta, então diagnóstico e backup ficariam inalcançáveis
            // justamente para quem acabou de abrir o app.
            Box {
                IconButton(onClick = { showVaultMenu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more), modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded         = showVaultMenu,
                    onDismissRequest = { showVaultMenu = false },
                ) {
                    DropdownMenuItem(
                        text        = { Text(stringResource(R.string.menu_diagnostics)) },
                        leadingIcon = { Icon(Icons.Default.HealthAndSafety, null) },
                        onClick     = { showVaultMenu = false; onDiagnostics() },
                    )
                    DropdownMenuItem(
                        text        = { Text(stringResource(R.string.menu_backup)) },
                        leadingIcon = { Icon(Icons.Default.Archive, null) },
                        onClick     = { showVaultMenu = false; onBackup() },
                    )
                }
            }

            // ── Recolher a barra (só existe na barra fixa de tela larga) ──
            if (onCollapseSidebar != null) {
                IconButton(onClick = onCollapseSidebar, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.MenuOpen, contentDescription = stringResource(R.string.cd_collapse_sidebar), modifier = Modifier.size(20.dp))
                }
            }
        }

        // ── Filter input ───────────────────────────────────────────────────
        OutlinedTextField(
            value         = filter,
            onValueChange = { filter = it },
            placeholder   = { Text(stringResource(R.string.filter_notes)) },
            singleLine    = true,
            leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon  = {
                if (filter.isNotEmpty()) {
                    IconButton(onClick = { filter = "" }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier        = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )

        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

        // ── Body ───────────────────────────────────────────────────────────
        when {
            loading && tree == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            tree != null && rows.isEmpty() && filter.isBlank() -> {
                EmptyTreePlaceholder(filterActive = false)
            }
            rows.isEmpty() && filter.isNotBlank() -> {
                EmptyTreePlaceholder(filterActive = true)
            }
            else -> {
                LazyColumn(
                    state    = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = rows, key = { it.key }) { row ->
                        when (row) {
                            is Row.SectionHeader -> SectionHeaderItem(row.title)
                            is Row.DirRow -> DirItem(
                                row         = row,
                                onToggle    = {
                                    val k = row.node.uri.toString()
                                    expanded = if (row.expanded) expanded - k else expanded + k
                                },
                                onNewFile   = { dialog = PendingDialog.CreateFile(row.node.uri) },
                                onNewFolder = { dialog = PendingDialog.CreateFolder(row.node.uri) },
                                onRename    = { dialog = PendingDialog.Rename(row.node) },
                                onMove      = { dialog = PendingDialog.Move(row.node) },
                                onDelete    = { dialog = PendingDialog.ConfirmDelete(row.node) },
                            )
                            is Row.FileRow -> FileItem(
                                row         = row,
                                isActive    = activeFileUri == row.node.uri.toString(),
                                onClick     = { onFileClick(row.node) },
                                onRename    = { dialog = PendingDialog.Rename(row.node) },
                                onMove      = { dialog = PendingDialog.Move(row.node) },
                                onDelete    = { dialog = PendingDialog.ConfirmDelete(row.node) },
                                onTogglePin = { onTogglePin(row.node) },
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Dialogs ─────────────────────────────────────────────────────────────
    when (val d = dialog) {
        is PendingDialog.CreateFile -> InputDialog(
            title     = "New note",
            hint      = "Note name",
            onConfirm = { name -> dialog = null; onCreateFile(d.parentUri, name) },
            onDismiss = { dialog = null },
        )
        is PendingDialog.CreateFolder -> InputDialog(
            title     = "New folder",
            hint      = "Folder name",
            onConfirm = { name -> dialog = null; onCreateFolder(d.parentUri, name) },
            onDismiss = { dialog = null },
        )
        is PendingDialog.Rename -> InputDialog(
            title     = "Rename",
            hint      = d.node.name,
            initial   = if (d.node is Node.File) d.node.displayName else d.node.name,
            onConfirm = { name -> dialog = null; onRenameNode(d.node, name) },
            onDismiss = { dialog = null },
        )
        is PendingDialog.ConfirmDelete -> ConfirmDeleteDialog(
            name      = d.node.name,
            isDir     = d.node is Node.Dir,
            onConfirm = { dialog = null; onDeleteNode(d.node) },
            onDismiss = { dialog = null },
        )
        is PendingDialog.Move -> if (tree != null) MovePickerDialog(
            tree      = tree,
            node      = d.node,
            onConfirm = { target -> dialog = null; onMoveNode(d.node, target) },
            onDismiss = { dialog = null },
        )
        is PendingDialog.PickTemplate -> TemplatePickerDialog(
            templates = templates,
            onPick    = { t -> dialog = PendingDialog.CreateFromTemplate(t) },
            onDismiss = { dialog = null },
        )
        is PendingDialog.CreateFromTemplate -> InputDialog(
            title     = "New note from \"${d.template.displayName}\"",
            hint      = "Note name",
            onConfirm = { name -> dialog = null; onCreateFromTemplate(d.template, name) },
            onDismiss = { dialog = null },
        )
        null -> {}
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Template picker
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TemplatePickerDialog(
    templates: List<Node.File>,
    onPick: (Node.File) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_note_from_template)) },
        text  = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(templates, key = { it.uri.toString() }) { t ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onPick(t) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint     = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t.displayName,
                            style    = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Row items
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeaderItem(title: String) {
    Text(
        text     = title.uppercase(),
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp, end = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DirItem(
    row: Row.DirRow,
    onToggle: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = { showMenu = true })
                .padding(
                    start  = (12 + row.depth * 14).dp,
                    end    = 12.dp,
                    top    = 7.dp,
                    bottom = 7.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (row.expanded)
                    Icons.Default.KeyboardArrowDown
                else
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint     = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (row.expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint     = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                row.node.name,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f),
            )
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text        = { Text(stringResource(R.string.new_note_here)) },
                leadingIcon = { Icon(Icons.Default.NoteAdd, null) },
                onClick     = { showMenu = false; onNewFile() },
            )
            DropdownMenuItem(
                text        = { Text(stringResource(R.string.new_folder_here)) },
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                onClick     = { showMenu = false; onNewFolder() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text        = { Text(stringResource(R.string.action_rename)) },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick     = { showMenu = false; onRename() },
            )
            DropdownMenuItem(
                text        = { Text(stringResource(R.string.move_to)) },
                leadingIcon = { Icon(Icons.Default.DriveFileMove, null) },
                onClick     = { showMenu = false; onMove() },
            )
            DropdownMenuItem(
                text        = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = { showMenu = false; onDelete() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    row: Row.FileRow,
    isActive: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val container = if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(container)
                .combinedClickable(onClick = onClick, onLongClick = { showMenu = true })
                .padding(
                    start  = (8 + row.depth * 14).dp,
                    end    = 10.dp,
                    top    = 9.dp,
                    bottom = 9.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(20.dp))
            val (icon, tint) = when {
                row.node.isPdf  -> Icons.Default.PictureAsPdf to MaterialTheme.colorScheme.error
                row.node.isDocx -> Icons.Default.Description  to MaterialTheme.colorScheme.tertiary
                else            -> Icons.AutoMirrored.Filled.Article to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
            Text(
                row.node.displayName,
                style    = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color    = if (isActive)
                    MaterialTheme.colorScheme.onSecondaryContainer
                else
                    MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (row.isPinned) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint     = MaterialTheme.colorScheme.primary,
                )
            }
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text        = { Text(stringResource(if (row.isPinned) R.string.unpin else R.string.pin_to_top)) },
                leadingIcon = {
                    Icon(
                        if (row.isPinned) Icons.Default.PushPin else Icons.Default.PushPin,
                        contentDescription = null,
                    )
                },
                onClick = { showMenu = false; onTogglePin() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text        = { Text(stringResource(R.string.action_rename)) },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick     = { showMenu = false; onRename() },
            )
            DropdownMenuItem(
                text        = { Text(stringResource(R.string.move_to)) },
                leadingIcon = { Icon(Icons.Default.DriveFileMove, null) },
                onClick     = { showMenu = false; onMove() },
            )
            DropdownMenuItem(
                text        = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = { showMenu = false; onDelete() },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dialogs
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InputDialog(
    title: String,
    hint: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text(title) },
        text    = {
            OutlinedTextField(
                value           = value,
                onValueChange   = { value = it },
                placeholder     = { Text(hint) },
                singleLine      = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (value.isNotBlank()) onConfirm(value.trim()) }
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick  = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled  = value.isNotBlank(),
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ConfirmDeleteDialog(
    name: String,
    isDir: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon    = {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title   = { Text("Delete ${if (isDir) "folder" else "note"}?") },
        text    = {
            Text(
                "\"$name\" will be deleted permanently." +
                if (isDir) "\nAll files inside will also be deleted." else ""
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Move picker
// ─────────────────────────────────────────────────────────────────────────────

private data class MoveTarget(val uri: Uri, val label: String, val depth: Int)

/** Destination folders for moving [moving]; excludes the folder itself + descendants. */
private fun buildMoveTargets(tree: Tree, moving: Node): List<MoveTarget> {
    val excluded = HashSet<String>()
    if (moving is Node.Dir) {
        fun collect(d: Node.Dir) {
            excluded += d.uri.toString()
            d.children.filterIsInstance<Node.Dir>().forEach { collect(it) }
        }
        collect(moving)
    }
    val out = ArrayList<MoveTarget>()
    out += MoveTarget(tree.rootUri, tree.name, 0)
    fun walk(nodes: List<Node>, depth: Int) {
        nodes.filterIsInstance<Node.Dir>().sortedBy { it.name.lowercase() }.forEach { d ->
            if (d.uri.toString() in excluded) return@forEach
            out += MoveTarget(d.uri, d.name, depth)
            walk(d.children, depth + 1)
        }
    }
    walk(tree.root, 1)
    return out
}

@Composable
private fun MovePickerDialog(
    tree: Tree,
    node: Node,
    onConfirm: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val targets = remember(tree, node) { buildMoveTargets(tree, node) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move \"${if (node is Node.File) node.displayName else node.name}\" to…") },
        text  = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(targets, key = { it.uri.toString() }) { t ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onConfirm(t.uri) }
                            .padding(start = (8 + t.depth * 14).dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (t.depth == 0) Icons.Default.Home else Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint     = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t.label,
                            style    = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyTreePlaceholder(filterActive: Boolean) {
    Box(
        Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (filterActive) stringResource(R.string.empty_filter)
            else stringResource(R.string.empty_vault),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
