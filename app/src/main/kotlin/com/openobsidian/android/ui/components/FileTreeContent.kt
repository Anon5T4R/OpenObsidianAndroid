package com.openobsidian.android.ui.components

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
}

// ─────────────────────────────────────────────────────────────────────────────
// Public composable
// ─────────────────────────────────────────────────────────────────────────────

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
    onTogglePin: (Node.File) -> Unit = {},
    onDailyNote: () -> Unit = {},
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
    var showAddMenu by remember { mutableStateOf(false) }

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

            // ── Daily note ────────────────────────────────────────────────
            IconButton(onClick = onDailyNote, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.CalendarToday, contentDescription = "Today's note", modifier = Modifier.size(20.dp))
            }

            // ── Refresh ───────────────────────────────────────────────────
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(20.dp))
            }

            // ── Create note / folder ──────────────────────────────────────
            Box {
                IconButton(
                    onClick  = { if (tree != null) showAddMenu = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded         = showAddMenu,
                    onDismissRequest = { showAddMenu = false },
                ) {
                    DropdownMenuItem(
                        text         = { Text("New note") },
                        leadingIcon  = { Icon(Icons.Default.NoteAdd, null) },
                        onClick      = {
                            showAddMenu = false
                            tree?.let { dialog = PendingDialog.CreateFile(it.rootUri) }
                        },
                    )
                    DropdownMenuItem(
                        text         = { Text("New folder") },
                        leadingIcon  = { Icon(Icons.Default.CreateNewFolder, null) },
                        onClick      = {
                            showAddMenu = false
                            tree?.let { dialog = PendingDialog.CreateFolder(it.rootUri) }
                        },
                    )
                }
            }

            // ── Switch vault ──────────────────────────────────────────────
            IconButton(onClick = onSwitchVault, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Switch vault", modifier = Modifier.size(20.dp))
            }
        }

        // ── Filter input ───────────────────────────────────────────────────
        OutlinedTextField(
            value         = filter,
            onValueChange = { filter = it },
            placeholder   = { Text("Filter notes…") },
            singleLine    = true,
            leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon  = {
                if (filter.isNotEmpty()) {
                    IconButton(onClick = { filter = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
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
                                onDelete    = { dialog = PendingDialog.ConfirmDelete(row.node) },
                            )
                            is Row.FileRow -> FileItem(
                                row         = row,
                                isActive    = activeFileUri == row.node.uri.toString(),
                                onClick     = { onFileClick(row.node) },
                                onRename    = { dialog = PendingDialog.Rename(row.node) },
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
        null -> {}
    }
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
                text        = { Text("New note here") },
                leadingIcon = { Icon(Icons.Default.NoteAdd, null) },
                onClick     = { showMenu = false; onNewFile() },
            )
            DropdownMenuItem(
                text        = { Text("New folder here") },
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                onClick     = { showMenu = false; onNewFolder() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text        = { Text("Rename") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick     = { showMenu = false; onRename() },
            )
            DropdownMenuItem(
                text        = { Text("Delete", color = MaterialTheme.colorScheme.error) },
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
                text        = { Text(if (row.isPinned) "Unpin" else "Pin to top") },
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
                text        = { Text("Rename") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick     = { showMenu = false; onRename() },
            )
            DropdownMenuItem(
                text        = { Text("Delete", color = MaterialTheme.colorScheme.error) },
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
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
            if (filterActive) "No notes match this filter."
            else "This vault is empty.\nTap + to create a note.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
