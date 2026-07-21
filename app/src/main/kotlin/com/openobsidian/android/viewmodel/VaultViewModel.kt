package com.openobsidian.android.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openobsidian.android.R
import com.openobsidian.android.data.Cards
import com.openobsidian.android.data.DocxConverter
import com.openobsidian.android.data.Frontmatter
import com.openobsidian.android.data.IndexCache
import com.openobsidian.android.data.IndexStore
import com.openobsidian.android.data.LinkResolver
import com.openobsidian.android.data.LinkRewrite
import com.openobsidian.android.data.SearchQuery
import com.openobsidian.android.data.Node
import com.openobsidian.android.data.SafFs
import com.openobsidian.android.data.Srs
import com.openobsidian.android.data.SrsStore
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

/** One card as the review screen needs it: text resolved, ready to show. */
data class ReviewCard(
    val id: String,
    val question: String,
    val answer: String,
    /** Which note it came from, so you can tell where to go fix it */
    val note: String,
)

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
            val text = SafFs.readTextOrNull(appContext, file.uri)
            _state.update { s ->
                if (s.activeFile?.uri != file.uri) return@update s
                if (text == null) {
                    // Never show an unreadable note as an empty one. The editor
                    // would look like a blank page, the first keystroke would
                    // make autosave write that blank over a note that is still
                    // fine on disk, and nothing would have warned anyone.
                    s.copy(contentLoading = false, unreadable = true, activeContent = "")
                } else {
                    s.copy(
                        activeContent = text,
                        contentLoading = false,
                        unreadable = false,
                        contentCache = s.contentCache + (file.uri.toString() to text),
                    )
                }
            }
        }
    }

    // ── Edit ──────────────────────────────────────────────────────────────

    fun updateContent(text: String) {
        val s0 = _state.value
        val file = s0.activeFile ?: return
        // What was never read must not be overwritten
        if (s0.unreadable) return
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
        if (s.unreadable) return
        viewModelScope.launch {
            val ok = writeNote(file, s.activeContent)
            // The dirty mark used to be cleared no matter what, so a failed save
            // looked exactly like a successful one — the app claimed to have
            // saved. It only comes off when the bytes actually landed.
            if (ok) {
                _state.update { it.copy(isDirty = false, saveError = null) }
                // The card you just wrote counts from now, not from the next
                // time the whole vault happens to be re-indexed
                syncCards()
            }
        }
    }

    /** Writes a note, reporting failure instead of swallowing it. */
    private suspend fun writeNote(file: Node.File, content: String): Boolean {
        val tree = _state.value.tree
        val parent = tree?.let { findParentUri(file, it) }
        return runCatching {
            SafFs.writeTextChecked(appContext, file.uri, content, parent, file.name)
        }.onFailure { e ->
            val msg = appContext.getString(R.string.toast_save_failed, file.displayName)
            _state.update { it.copy(saveError = msg) }
            toast(msg)
            android.util.Log.w("OpenObsidian", "save failed for ${file.name}", e)
        }.isSuccess
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
            // Usa templates/daily.md quando existir; senão só o cabeçalho.
            val template = tree.templates.find { it.displayName.equals("daily", ignoreCase = true) }
            val header = template
                ?.let { t -> runCatching { SafFs.readText(appContext, t.uri) }.getOrNull() }
                ?.let { raw -> applyTemplatePlaceholders(raw, today) }
                ?: "# $today\n\n"
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

    // ── Templates ─────────────────────────────────────────────────────────

    /**
     * Cria uma nota na raiz do vault a partir de um template da pasta
     * `templates/`, aplicando os placeholders {{title}}, {{date}} e {{time}}.
     */
    fun createFromTemplate(template: Node.File, name: String) {
        val tree = _state.value.tree ?: return
        viewModelScope.launch {
            val raw     = runCatching { SafFs.readText(appContext, template.uri) }.getOrDefault("")
            val content = applyTemplatePlaceholders(raw, name)
            val newUri  = runCatching {
                SafFs.createMarkdownFile(appContext, tree.rootUri, name)
            }.getOrNull() ?: return@launch
            runCatching { SafFs.writeText(appContext, newUri, content) }
            val newFile = Node.File(
                name  = "$name.md",
                uri   = newUri,
                size  = content.length.toLong(),
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
            val oldDisplay = when (node) {
                is Node.File -> node.displayName
                is Node.Dir  -> node.name
            }
            val renamed = runCatching { SafFs.rename(appContext, node.uri, safeName) }.getOrNull()
            if (renamed == null) {
                toast(appContext.getString(R.string.toast_rename_failed, node.name))
                loadTree()
                return@launch
            }
            // A rename used to break every [[link]] pointing at the note, in
            // silence. Only notes have links; a folder rename has none to fix.
            if (node is Node.File && node.isText) {
                val newDisplay = safeName.removeSuffix(".md")
                if (!newDisplay.equals(oldDisplay, ignoreCase = true)) {
                    rewriteLinksAfterRename(oldDisplay, newDisplay)
                }
            }
            loadTree()
        }
    }

    /**
     * Points every `[[oldName]]` in the vault at [newName].
     * The desktop asks first and reports the count; here the count is reported
     * after the fact, which keeps the operation one tap instead of two — but it
     * is never silent, which was the actual problem.
     */
    private suspend fun rewriteLinksAfterRename(oldName: String, newName: String) {
        val s = _state.value
        val tree = s.tree ?: return
        var links = 0
        var notes = 0
        val updatedCache = s.contentCache.toMutableMap()

        for (file in tree.allMarkdownFiles) {
            val key = file.uri.toString()
            val text = s.contentCache[key] ?: SafFs.readTextOrNull(appContext, file.uri) ?: continue
            val result = LinkRewrite.rewriteLinks(text, oldName, newName)
            if (result.count == 0) continue
            val ok = runCatching {
                SafFs.writeTextChecked(appContext, file.uri, result.content)
            }.isSuccess
            if (!ok) continue
            updatedCache[key] = result.content
            links += result.count
            notes++
        }

        if (notes > 0) {
            _state.update { it.copy(contentCache = updatedCache) }
            toast(appContext.getString(R.string.toast_links_updated, links, notes))
        }
    }

    /**
     * Move a file/folder into [targetDir]. Carefully guards the common foot-guns:
     *  - dropping onto the folder it already lives in → silent no-op,
     *  - moving a folder into itself or one of its descendants → blocked,
     *  - a name collision in the destination → blocked with a message.
     * On success the tree is reloaded; an affected open note is closed.
     */
    fun moveNode(node: Node, targetDir: Uri) {
        viewModelScope.launch {
            val tree         = _state.value.tree ?: return@launch
            val sourceParent = findParentUri(node, tree)

            // No-op: already in the target folder.
            if (sourceParent == targetDir) return@launch

            // Can't move a folder into itself or a descendant.
            if (node is Node.Dir && isSelfOrDescendant(node, targetDir)) {
                toast(appContext.getString(R.string.toast_move_into_itself))
                return@launch
            }

            // Name collision in the destination.
            if (SafFs.hasChildNamed(appContext, targetDir, node.name)) {
                toast(appContext.getString(R.string.toast_name_exists, node.name))
                return@launch
            }

            val newUri = runCatching {
                SafFs.move(appContext, node.uri, sourceParent, targetDir)
            }.getOrNull()

            if (newUri == null) {
                toast(appContext.getString(R.string.toast_move_failed, node.name))
                return@launch
            }

            // Close the open note if it was moved (its URI is now stale).
            val active = _state.value.activeFile
            if (active != null && (active.uri == node.uri ||
                    (node is Node.Dir && isInSubtree(node, active.uri)))
            ) {
                _state.update {
                    it.copy(activeFile = null, activeContent = "", contentLoading = false, isDirty = false)
                }
            }
            loadTree()
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** The directory URI that currently holds [target] (vault root if top-level). */
    private fun findParentUri(target: Node, tree: Tree): Uri {
        fun search(nodes: List<Node>, parent: Uri): Uri? {
            for (n in nodes) {
                if (n.uri == target.uri) return parent
                if (n is Node.Dir) search(n.children, n.uri)?.let { return it }
            }
            return null
        }
        return search(tree.root, tree.rootUri) ?: tree.rootUri
    }

    private fun isSelfOrDescendant(dir: Node.Dir, targetUri: Uri): Boolean =
        targetUri == dir.uri || isInSubtree(dir, targetUri)

    private fun isInSubtree(dir: Node.Dir, uri: Uri): Boolean {
        for (child in dir.children) {
            if (child.uri == uri) return true
            if (child is Node.Dir && isInSubtree(child, uri)) return true
        }
        return false
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
        val s = _state.value
        val results = runSearch(query, s.contentCache, s.tree, s.tagsByPath)
        _state.update { it.copy(searchResults = results) }
    }

    // ── Link resolution ───────────────────────────────────────────────────

    private fun refsOf(tree: Tree?): List<LinkResolver.Ref> =
        tree?.allMarkdownFiles.orEmpty().map {
            LinkResolver.Ref(it.displayName, it.relativePath, it.uri.toString())
        }

    /**
     * Opens what a `[[wikilink]]` points at.
     *
     * Used to be an exact-name `find()`, so `[[Nota#Seção]]`, `[[Pasta/Nota]]`
     * and any alias simply did nothing when tapped — no note, no message.
     */
    fun openByLink(rawTarget: String) {
        val s = _state.value
        val target = LinkResolver.parseTarget(rawTarget)
        // `[[#Seção]]` points inside the open note; there is nowhere to navigate
        if (target.name.isEmpty()) return
        val refs = refsOf(s.tree)
        val hit = LinkResolver.resolve(refs, target.name, s.activeFile?.relativePath, s.aliases)
        if (hit == null) {
            toast(appContext.getString(R.string.toast_link_unresolved, target.name))
            return
        }
        s.tree?.allMarkdownFiles?.firstOrNull { it.uri.toString() == hit.key }?.let { openFile(it) }
    }

    // ── Vault diagnostics ─────────────────────────────────────────────────

    fun openDiagnostics() {
        val s = _state.value
        val refs = refsOf(s.tree)
        _state.update {
            it.copy(
                diagnosticsOpen = true,
                brokenLinks = LinkResolver.brokenLinks(refs, s.contentCache, s.aliases),
                orphanNotes = LinkResolver.orphanNotes(refs, s.contentCache).map { r -> r.name },
                duplicateNames = LinkResolver.duplicateNames(refs).map { d -> d.first to d.second.size },
            )
        }
    }

    fun closeDiagnostics() = _state.update { it.copy(diagnosticsOpen = false) }

    // ── Index / backlinks ─────────────────────────────────────────────────

    /** True once the on-disk cache has been consulted for this vault. */
    private var diskCacheLoaded = false

    private fun buildIndex() {
        val s    = _state.value
        val tree = s.tree ?: return

        _state.update { it.copy(indexing = true) }
        viewModelScope.launch {
            val files = tree.allMarkdownFiles
            val refs = files.map { IndexCache.Ref(it.uri.toString(), it.mtime) }

            // The cache from the last session, consulted once per vault. What
            // it holds with a matching mtime is not read from the provider
            // again — over SAF that read is the slowest thing the app does.
            var fromDisk = emptyMap<String, String>()
            if (!diskCacheLoaded) {
                diskCacheLoaded = true
                val stored = IndexStore.load(appContext, vaultUri)
                if (stored.isNotEmpty()) fromDisk = IndexCache.freshContent(refs, stored)
            }

            val known = s.contentCache + fromDisk
            val uncached = files.filter { !known.containsKey(it.uri.toString()) }

            // Sync the cards even when there is nothing new to read: writing a
            // card into an already-cached note does not change the index, but
            // it does change the cards — and that is how a card just written
            // never showed up in the counter, leaving the button hidden.
            if (uncached.isEmpty() && fromDisk.isEmpty()) {
                _state.update { it.copy(indexing = false) }
                syncCards()
                return@launch
            }

            val newEntries = withContext(Dispatchers.IO) {
                uncached.map { file ->
                    async {
                        // A failed read is left out entirely. Caching it as ""
                        // would drop the note from backlinks, tags and search
                        // with nothing to show for it.
                        SafFs.readTextOrNull(appContext, file.uri)
                            ?.let { file.uri.toString() to it }
                    }
                }.awaitAll().filterNotNull().toMap()
            } + fromDisk

            _state.update { s2 ->
                val newCache     = s2.contentCache + newEntries
                val indexed      = s2.tree?.allMarkdownFiles ?: emptyList()
                val newBacklinks = computeBacklinks(indexed, newCache)
                val files        = indexed

                // Frontmatter rides along with this pass — the text is already
                // here, and both the alias index and the tag index come from it
                val parsed = files.mapNotNull { f ->
                    newCache[f.uri.toString()]?.let { f to Frontmatter.parse(it) }
                }
                val tags = files.mapNotNull { f ->
                    newCache[f.uri.toString()]?.let {
                        f.relativePath to Frontmatter.expandHierarchy(Frontmatter.extractTags(it))
                    }
                }.toMap()

                s2.copy(
                    contentCache = newCache,
                    backlinks    = newBacklinks,
                    aliases      = LinkResolver.buildAliasIndex(
                        parsed.associate { (f, p) -> f.relativePath to p },
                    ),
                    tagsByPath   = tags,
                    indexing     = false,
                )
            }

            val q = _state.value.searchQuery
            if (q.isNotBlank()) setSearchQuery(q)
            syncCards()

            // Persist what was just read, so the next launch does not re-read
            // the whole vault over the provider
            val cache = _state.value.contentCache
            IndexStore.save(appContext, vaultUri, IndexCache.toEntries(refs, cache))
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun flushDirty() {
        autosaveJob?.cancel()
        val s = _state.value
        if (!s.isDirty || s.activeFile == null || s.unreadable) return
        val file = s.activeFile
        val content = s.activeContent
        viewModelScope.launch { writeNote(file, content) }
    }

    // ── Flashcards ────────────────────────────────────────────────────────

    private var srsCards: Map<String, Srs.Card> = emptyMap()

    /**
     * Reconciles every note's cards with the stored schedule.
     * Runs on vault open, not only on save: a card used to exist only after you
     * happened to edit the note it lives in.
     */
    fun syncCards() {
        val tree = _state.value.tree ?: return
        viewModelScope.launch {
            val stored = SrsStore.load(appContext, tree.rootUri)
            var cards = stored
            for (file in tree.allMarkdownFiles) {
                val text = _state.value.contentCache[file.uri.toString()]
                    ?: SafFs.readTextOrNull(appContext, file.uri) ?: continue
                val found = Cards.extractCards(file.relativePath, text).map { it.id to it.q }
                cards = Srs.syncFile(cards, file.relativePath, found)
            }
            srsCards = cards
            if (cards != stored) SrsStore.save(appContext, tree.rootUri, cards)
            _state.update { it.copy(srsStats = Srs.stats(cards)) }
        }
    }

    /**
     * Opens a session with what is due. The answer is read from the note at
     * review time rather than from the schedule, so fixing a typo in the note
     * fixes the card with no re-sync.
     */
    fun startReview() {
        val tree = _state.value.tree ?: return
        viewModelScope.launch {
            val due = Srs.dueCards(srsCards)
            val queue = mutableListOf<ReviewCard>()
            for ((id, card) in due) {
                val file = tree.allMarkdownFiles.find { it.relativePath == card.file } ?: continue
                val text = _state.value.contentCache[file.uri.toString()]
                    ?: SafFs.readTextOrNull(appContext, file.uri) ?: continue
                val fresh = Cards.extractCards(file.relativePath, text).find { it.id == id } ?: continue
                queue += ReviewCard(id = id, question = fresh.q, answer = fresh.a, note = file.displayName)
            }
            _state.update {
                it.copy(
                    reviewOpen = true, reviewQueue = queue, reviewIndex = 0,
                    reviewRevealed = false, reviewDone = 0, reviewError = false,
                )
            }
        }
    }

    fun revealAnswer() = _state.update { it.copy(reviewRevealed = true) }

    fun gradeCurrent(grade: Srs.Grade) {
        val s = _state.value
        val card = s.currentCard ?: return
        val stored = srsCards[card.id] ?: return
        srsCards = srsCards + (card.id to Srs.grade(stored, grade))
        val tree = s.tree
        viewModelScope.launch {
            // If the schedule cannot be written, say so: a session that is not
            // recorded looks exactly like one that is
            val ok = tree != null && SrsStore.save(appContext, tree.rootUri, srsCards)
            _state.update {
                it.copy(
                    reviewIndex = it.reviewIndex + 1,
                    reviewRevealed = false,
                    reviewDone = it.reviewDone + 1,
                    srsStats = Srs.stats(srsCards),
                    reviewError = it.reviewError || !ok,
                )
            }
        }
    }

    fun closeReview() = _state.update { it.copy(reviewOpen = false, reviewQueue = emptyList()) }

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

/** Placeholders de template: {{title}}, {{date}} (ISO) e {{time}} (HH:mm). */
private fun applyTemplatePlaceholders(raw: String, title: String): String {
    val now = java.time.LocalDateTime.now()
    return raw
        .replace("{{title}}", title, ignoreCase = true)
        .replace("{{date}}", now.toLocalDate().toString(), ignoreCase = true)
        .replace(
            "{{time}}",
            now.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
            ignoreCase = true,
        )
}

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
    query: String,
    cache: Map<String, String>,
    tree: Tree?,
    tagsByPath: Map<String, List<String>>,
): List<SearchResult> {
    tree ?: return emptyList()
    val parsed = SearchQuery.parse(query)
    if (parsed.isEmpty) return emptyList()

    // Only a free-text term has something to highlight inside the body; a
    // `tag:` filter points at metadata, not at a place in the note
    val needle = parsed.terms.firstOrNull { !it.exclude && it.field == null }?.text?.lowercase().orEmpty()

    val scored = mutableListOf<Pair<Int, SearchResult>>()
    for (file in tree.allMarkdownFiles) {
        val content = cache[file.uri.toString()] ?: continue
        val note = SearchQuery.Note(
            name = file.displayName,
            relativePath = file.relativePath,
            content = content,
            tags = tagsByPath[file.relativePath].orEmpty(),
        )
        if (!SearchQuery.matches(note, parsed)) continue

        // A tag-only query has nothing to centre the snippet on: start at the top
        val idx = if (needle.isEmpty()) 0
        else content.lowercase().indexOf(needle).coerceAtLeast(0)

        val lineStart = content.lastIndexOf('\n', idx).let { if (it < 0) 0 else it + 1 }
        val lineEnd   = content.indexOf('\n', idx).let { if (it < 0) content.length else it }
        val line      = content.substring(lineStart, lineEnd).trim()

        val matchInLine  = idx - lineStart
        val snippetStart = maxOf(0, matchInLine - 30)
        val snippet      = line.drop(snippetStart).take(120)
        val matchInSnip  = (matchInLine - snippetStart).coerceAtLeast(0)

        scored += SearchQuery.score(note, parsed) to SearchResult(
            file        = file,
            snippet     = snippet,
            matchStart  = if (needle.isEmpty()) 0 else matchInSnip,
            matchLength = needle.length,
        )
    }
    // Most relevant first; the name breaks ties so the order is stable
    return scored
        .sortedWith(compareByDescending<Pair<Int, SearchResult>> { it.first }
            .thenBy { it.second.file.displayName.lowercase() })
        .map { it.second }
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
    /** The open note could not be read; editing and saving are blocked */
    val unreadable: Boolean     = false,
    /** Last save failure, shown in the bar so it does not vanish like a toast */
    val saveError: String?      = null,
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
    /** alias (minúsculo) → caminho relativo da nota que o declara */
    val aliases: Map<String, String>            = emptyMap(),
    /** caminho relativo → tags da nota, já com a hierarquia expandida */
    val tagsByPath: Map<String, List<String>>   = emptyMap(),
    // ── Diagnóstico do vault
    val diagnosticsOpen: Boolean                    = false,
    val brokenLinks: List<LinkResolver.Broken>      = emptyList(),
    val orphanNotes: List<String>                   = emptyList(),
    val duplicateNames: List<Pair<String, Int>>     = emptyList(),
    // ── Flashcards
    val reviewOpen: Boolean                 = false,
    val reviewQueue: List<ReviewCard>       = emptyList(),
    val reviewIndex: Int                    = 0,
    val reviewRevealed: Boolean             = false,
    val reviewDone: Int                     = 0,
    val srsStats: Srs.Stats                 = Srs.Stats(0, 0, 0, 0),
    /** The schedule could not be written; the session is not being recorded */
    val reviewError: Boolean                = false,
) {
    val currentCard: ReviewCard? get() = reviewQueue.getOrNull(reviewIndex)
    val canNavBack:    Boolean get() = navIndex > 0
    val canNavForward: Boolean get() = navIndex < navHistory.lastIndex

    companion object {
        val Loading = VaultUiState(loading = true)
    }
}
