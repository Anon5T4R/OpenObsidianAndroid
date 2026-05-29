package com.openobsidian.android.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openobsidian.android.data.DocxConverter
import com.openobsidian.android.data.Node
import com.openobsidian.android.data.SafFs
import com.openobsidian.android.data.Tree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ViewMode { EDIT, PREVIEW, SPLIT }

data class SearchResult(
    val file: Node.File,
    /** Short excerpt centred on the first match (≤ 120 chars). */
    val snippet: String,
    /** Index of the match inside `snippet`. */
    val matchStart: Int,
    val matchLength: Int,
)

class VaultViewModel(
    private val appContext: Context,
    private val vaultUri: Uri,
) : ViewModel() {

    private val _state = MutableStateFlow(VaultUiState.Loading)
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    private var autosaveJob: Job? = null

    // Persisted pin set per vault (keyed by vault URI)
    private val pinPrefs by lazy {
        appContext.getSharedPreferences("pins", Context.MODE_PRIVATE)
    }

    init {
        _state.update { it.copy(pinnedUris = loadPins()) }
        loadTree()
    }

    // ── Pin persistence ───────────────────────────────────────────────────

    private fun loadPins(): Set<String> =
        pinPrefs.getStringSet(vaultUri.toString(), emptySet()) ?: emptySet()

    private fun savePins(pins: Set<String>) {
        pinPrefs.edit().putStringSet(vaultUri.toString(), pins).apply()
    }

    fun togglePin(file: Node.File) {
        val current = _state.value.pinnedUris
        val uriStr  = file.uri.toString()
        val updated = if (uriStr in current) current - uriStr else current + uriStr
        _state.update { it.copy(pinnedUris = updated) }
        savePins(updated)
    }

    // ── File resolution ───────────────────────────────────────────────────

    /** Find any file (note or image) in the vault by exact name (case-insensitive). */
    fun resolveFile(name: String): Uri? {
        val tree = _state.value.tree ?: return null
        // Images are indexed separately (not part of the node tree).
        tree.images[name.lowercase()]?.let { return it }
        fun search(nodes: List<Node>): Uri? {
            for (node in nodes) {
                when (node) {
                    is Node.File -> if (node.name.equals(name, ignoreCase = true)) return node.uri
                    is Node.Dir  -> search(node.children)?.let { return it }
                }
            }
            return null
        }
        return search(tree.root)
    }

    // ── Tree ──────────────────────────────────────────────────────────────

    fun loadTree() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val tree = SafFs.walkVault(appContext, vaultUri)
                _state.update { it.copy(loading = false, tree = tree, error = null) }
                buildIndex()
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to read vault") }
            }
        }
    }

    // ── File open / close ─────────────────────────────────────────────────

    fun openFile(file: Node.File) {
        flushDirty()
        val s = _state.value
        val newHistory = (s.navHistory.take(s.navIndex + 1) + file).takeLast(50)
        _state.update {
            it.copy(
                activeFile     = file,
                activeContent  = "",
                contentLoading = file.isText,
                isDirty        = false,
                navHistory     = newHistory,
                navIndex       = newHistory.lastIndex,
            )
        }
        loadFileContent(file)
    }

    fun closeFile() {
        flushDirty()
        _state.update {
            it.copy(
                activeFile     = null,
                activeContent  = "",
                contentLoading = false,
                isDirty        = false,
            )
        }
    }

    fun navBack() {
        val s        = _state.value
        val newIndex = s.navIndex - 1
        if (newIndex < 0) return
        val file = s.navHistory[newIndex]
        flushDirty()
        _state.update {
            it.copy(
                activeFile     = file,
                activeContent  = "",
                contentLoading = file.isText,
                isDirty        = false,
                navIndex       = newIndex,
            )
        }
        loadFileContent(file)
    }

    fun navForward() {
        val s        = _state.value
        val newIndex = s.navIndex + 1
        if (newIndex > s.navHistory.lastIndex) return
        val file = s.navHistory[newIndex]
        flushDirty()
        _state.update {
            it.copy(
                activeFile     = file,
                activeContent  = "",
                contentLoading = file.isText,
                isDirty        = false,
                navIndex       = newIndex,
            )
        }
        loadFileContent(file)
    }

    private fun loadFileContent(file: Node.File) {
        if (!file.isText) return
        viewModelScope.launch {
            val text = runCatching { SafFs.readText(appContext, file.uri) }.getOrDefault("")
            _state.update { s ->
                if (s.activeFile?.uri == file.uri) {
                    val newCache = s.contentCache + (file.uri.toString() to text)
                    s.copy(activeContent = text, contentLoading = false, contentCache = newCache)
                } else s
            }
        }
    }

    // ── Edit ──────────────────────────────────────────────────────────────

    fun updateContent(text: String) {
        val file = _state.value.activeFile ?: return
        _state.update { s ->
            val newCache = s.contentCache + (file.uri.toString() to text)
            s.copy(activeContent = text, isDirty = true, contentCache = newCache)
        }
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(1_500)
            saveCurrentFile()
        }
    }

    fun saveCurrentFile() {
        autosaveJob?.cancel()
        val s    = _state.value
        val file = s.activeFile ?: return
        viewModelScope.launch {
            runCatching { SafFs.writeText(appContext, file.uri, s.activeContent) }
            _state.update { it.copy(isDirty = false) }
        }
    }

    fun setViewMode(mode: ViewMode) {
        _state.update { it.copy(viewMode = mode) }
    }

    // ── Daily note ────────────────────────────────────────────────────────

    fun openDailyNote() {
        val today = java.time.LocalDate.now().toString()
        val s     = _state.value
        val tree  = s.tree ?: return
        val existing = tree.allMarkdownFiles.find { it.displayName == today }
        if (existing != null) { openFile(existing); return }
        viewModelScope.launch {
            val newUri = runCatching {
                SafFs.createMarkdownFile(appContext, tree.rootUri, today)
            }.getOrNull() ?: return@launch
            val header = "# $today\n\n"
            runCatching { SafFs.writeText(appContext, newUri, header) }
            val newFile = Node.File(
                name  = "$today.md",
                uri   = newUri,
                size  = header.length.toLong(),
                mtime = System.currentTimeMillis(),
            )
            openFile(newFile)
            loadTree()
        }
    }

    // ── Companion note ────────────────────────────────────────────────────

    fun openCompanionNote(file: Node.File) {
        val s    = _state.value
        val tree = s.tree ?: return
        val baseName      = file.displayName
        val companionName = "$baseName Notes"

        val existing = tree.allMarkdownFiles
            .find { it.displayName.equals(companionName, ignoreCase = true) }
        if (existing != null) { openFile(existing); return }

        viewModelScope.launch {
            val newUri = runCatching {
                SafFs.createMarkdownFile(appContext, tree.rootUri, companionName)
            }.getOrNull() ?: return@launch
            val header = "# Notes: $baseName\n\n"
            runCatching { SafFs.writeText(appContext, newUri, header) }
            val newFile = Node.File(
                name  = "$companionName.md",
                uri   = newUri,
                size  = header.length.toLong(),
                mtime = System.currentTimeMillis(),
            )
            openFile(newFile)
            loadTree()
        }
    }

    // ── DOCX → Markdown conversion ────────────────────────────────────────

    fun convertDocxToMd(file: Node.File) {
        if (!file.isDocx) return
        val tree = _state.value.tree ?: return
        viewModelScope.launch {
            _state.update { it.copy(contentLoading = true) }
            val md = withContext(Dispatchers.IO) {
                DocxConverter.convert(appContext, file.uri)
            }
            val baseName = file.displayName
            val newUri   = runCatching {
                SafFs.createMarkdownFile(appContext, tree.rootUri, baseName)
            }.getOrNull() ?: run {
                _state.update { it.copy(contentLoading = false) }
                return@launch
            }
            runCatching { SafFs.writeText(appContext, newUri, md) }
            val newFile = Node.File(
                name  = "$baseName.md",
                uri   = newUri,
                size  = md.length.toLong(),
                mtime = System.currentTimeMillis(),
            )
            openFile(newFile)
            loadTree()
        }
    }

    // ── Image import ──────────────────────────────────────────────────────

    /**
     * Copy a picked image into the vault's `attachments/` folder and return the
     * final file name (for an `![[name]]` embed), or null on failure.
     */
    suspend fun importImage(sourceUri: Uri): String? {
        val tree = _state.value.tree ?: return null
        val targetDir = SafFs.findOrCreateDir(appContext, tree.rootUri, "attachments")
            ?: tree.rootUri
        val suggested = queryDisplayName(sourceUri)
            ?: "image-${System.currentTimeMillis()}.${extForMime(sourceUri)}"
        val name = SafFs.importImage(appContext, targetDir, sourceUri, suggested) ?: return null
        loadTree() // refresh the image index so the embed resolves
        return name
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun extForMime(uri: Uri): String = when (appContext.contentResolver.getType(uri)) {
        "image/png"     -> "png"
        "image/gif"     -> "gif"
        "image/webp"    -> "webp"
        "image/bmp"     -> "bmp"
        "image/svg+xml" -> "svg"
        else            -> "jpg"
    }

    // ── CRUD ──────────────────────────────────────────────────────────────

    fun createFile(parentUri: Uri, name: String) {
        viewModelScope.launch {
            runCatching { SafFs.createMarkdownFile(appContext, parentUri, name) }
            loadTree()
        }
    }

    fun createFolder(parentUri: Uri, name: String) {
        viewModelScope.launch {
            runCatching { SafFs.createDirectory(appContext, parentUri, name) }
            loadTree()
        }
    }

    fun renameNode(node: Node, newName: String) {
        viewModelScope.launch {
            val safeName = when (node) {
                is Node.File -> {
                    val ext = when {
                        node.isPdf  -> ".pdf"
                        node.isDocx -> ".docx"
                        else        -> ".md"
                    }
                    if (newName.endsWith(ext, ignoreCase = true)) newName else "$newName$ext"
                }
                is Node.Dir -> newName
            }
            runCatching { SafFs.rename(appContext, node.uri, safeName) }
            loadTree()
        }
    }

    fun deleteNode(node: Node) {
        viewModelScope.launch {
            runCatching { SafFs.delete(appContext, node.uri) }
            if (node is Node.File && _state.value.activeFile?.uri == node.uri) {
                _state.update {
                    it.copy(
                        activeFile     = null,
                        activeContent  = "",
                        contentLoading = false,
                        isDirty        = false,
                    )
                }
            }
            loadTree()
        }
    }

    // ── Search ────────────────────────────────────────────────────────────

    fun openSearch() {
        _state.update { it.copy(searchOpen = true) }
        buildIndex()
    }

    fun closeSearch() {
        _state.update { it.copy(searchOpen = false, searchQuery = "", searchResults = emptyList()) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList()) }
            return
        }
        val needle  = query.trim().lowercase()
        val results = runSearch(needle, _state.value.contentCache, _state.value.tree)
        _state.update { it.copy(searchResults = results) }
    }

    // ── Index / backlinks ─────────────────────────────────────────────────

    private fun buildIndex() {
        val s    = _state.value
        val tree = s.tree ?: return
        val uncached = tree.allMarkdownFiles.filter {
            !s.contentCache.containsKey(it.uri.toString())
        }
        if (uncached.isEmpty()) return

        _state.update { it.copy(indexing = true) }
        viewModelScope.launch {
            val newEntries = withContext(Dispatchers.IO) {
                uncached.map { file ->
                    async {
                        val text = runCatching {
                            SafFs.readText(appContext, file.uri)
                        }.getOrDefault("")
                        file.uri.toString() to text
                    }
                }.awaitAll().toMap()
            }

            _state.update { s2 ->
                val newCache     = s2.contentCache + newEntries
                val newBacklinks = computeBacklinks(
                    s2.tree?.allMarkdownFiles ?: emptyList(),
                    newCache,
                )
                s2.copy(
                    contentCache = newCache,
                    backlinks    = newBacklinks,
                    indexing     = false,
                )
            }

            val q = _state.value.searchQuery
            if (q.isNotBlank()) setSearchQuery(q)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun flushDirty() {
        autosaveJob?.cancel()
        val s = _state.value
        if (!s.isDirty || s.activeFile == null) return
        viewModelScope.launch {
            runCatching { SafFs.writeText(appContext, s.activeFile.uri, s.activeContent) }
        }
    }

    companion object {
        fun factory(appContext: Context, vaultUri: Uri) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    VaultViewModel(appContext, vaultUri) as T
            }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure functions
// ─────────────────────────────────────────────────────────────────────────────

private val WIKILINK_REGEX = Regex("\\[\\[([^\\]|\n]+?)(?:\\|[^\\]\n]+?)?]]")

private fun computeBacklinks(
    files: List<Node.File>,
    cache: Map<String, String>,
): Map<String, List<Node.File>> {
    val map = HashMap<String, MutableList<Node.File>>()
    for (file in files) {
        val text = cache[file.uri.toString()] ?: continue
        WIKILINK_REGEX.findAll(text).forEach { m ->
            val target = m.groupValues[1].trim().lowercase()
            map.getOrPut(target) { mutableListOf() }.add(file)
        }
    }
    return map
}

private fun runSearch(
    needle: String,
    cache: Map<String, String>,
    tree: Tree?,
): List<SearchResult> {
    tree ?: return emptyList()
    val results = mutableListOf<SearchResult>()
    for (file in tree.allMarkdownFiles) {
        val content      = cache[file.uri.toString()] ?: continue
        val contentLower = content.lowercase()
        val idx = contentLower.indexOf(needle)
        if (idx < 0) continue

        val lineStart = content.lastIndexOf('\n', idx).let { if (it < 0) 0 else it + 1 }
        val lineEnd   = content.indexOf('\n', idx).let { if (it < 0) content.length else it }
        val line      = content.substring(lineStart, lineEnd).trim()

        val matchInLine  = idx - lineStart
        val snippetStart = maxOf(0, matchInLine - 30)
        val snippet      = line.drop(snippetStart).take(120)
        val matchInSnip  = (matchInLine - snippetStart).coerceAtLeast(0)

        results.add(
            SearchResult(
                file        = file,
                snippet     = snippet,
                matchStart  = matchInSnip,
                matchLength = needle.length,
            )
        )
    }
    return results.sortedBy { it.file.displayName.lowercase() }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI state
// ─────────────────────────────────────────────────────────────────────────────

data class VaultUiState(
    val loading: Boolean        = true,
    val tree: Tree?             = null,
    val error: String?          = null,
    val activeFile: Node.File?  = null,
    val activeContent: String   = "",
    val contentLoading: Boolean = false,
    val isDirty: Boolean        = false,
    val viewMode: ViewMode      = ViewMode.PREVIEW,
    // ── Navigation history
    val navHistory: List<Node.File> = emptyList(),
    val navIndex: Int               = -1,
    // ── Pinned notes
    val pinnedUris: Set<String>     = emptySet(),
    // ── Search / index
    val contentCache: Map<String, String>       = emptyMap(),
    val backlinks: Map<String, List<Node.File>> = emptyMap(),
    val indexing: Boolean                       = false,
    val searchOpen: Boolean                     = false,
    val searchQuery: String                     = "",
    val searchResults: List<SearchResult>       = emptyList(),
) {
    val canNavBack:    Boolean get() = navIndex > 0
    val canNavForward: Boolean get() = navIndex < navHistory.lastIndex

    companion object {
        val Loading = VaultUiState(loading = true)
    }
}
