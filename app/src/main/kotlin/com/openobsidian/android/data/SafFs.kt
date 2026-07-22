package com.openobsidian.android.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Native filesystem layer over the Storage Access Framework.
 *
 * Why not just use `DocumentFile`:
 *   - `DocumentFile.listFiles()` instantiates one wrapper per child
 *     and `findFile()` does a linear scan with allocations.
 *   - We use `ContentResolver.query` against the children URI directly,
 *     which is a single cursor read — ~10x faster on large folders.
 *
 * Why not just use `java.io.File`:
 *   - The vault URI is `content://...tree/...`, not a real path. The user
 *     picked it via SAF, so we *must* go through the content provider.
 *
 * All I/O is suspendable on `Dispatchers.IO`. The UI thread never blocks.
 */
object SafFs {

    private val DOC_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    // ── Listing ────────────────────────────────────────────────────────────

    /**
     * List immediate children of a directory URI.
     * Cheap (single cursor query).
     */
    suspend fun listChildren(context: Context, dirUri: Uri): List<Entry> = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        // Tree URIs (vault root) use getTreeDocumentId(); document-in-tree URIs use getDocumentId().
        // Pure tree URI path: /tree/<id>  (2 segments)
        // Document-in-tree URI path: /tree/<id>/document/<id>  (4 segments)
        val docId = if (dirUri.pathSegments.size <= 2) {
            DocumentsContract.getTreeDocumentId(dirUri)
        } else {
            DocumentsContract.getDocumentId(dirUri)
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, docId)
        val out = ArrayList<Entry>(16)
        cr.query(childrenUri, DOC_PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id    = c.getString(0)
                val name  = c.getString(1) ?: continue
                val mime  = c.getString(2) ?: ""
                val size  = if (c.isNull(3)) 0L else c.getLong(3)
                val mtime = if (c.isNull(4)) 0L else c.getLong(4)
                val uri   = DocumentsContract.buildDocumentUriUsingTree(dirUri, id)
                out += Entry(
                    name = name,
                    uri = uri,
                    mimeType = mime,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = size,
                    lastModified = mtime,
                )
            }
        }
        out
    }

    /**
     * Walk the vault recursively, building a tree of relevant files.
     * Heavy operation — call once on vault open, cache the result.
     *
     * Filters out:
     *   - hidden entries (name starts with `.`)
     *   - non-markdown / non-pdf / non-docx files
     */
    suspend fun walkVault(context: Context, rootUri: Uri): Tree = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: return@withContext Tree.empty(rootUri)
        val rootName = root.name ?: "Vault"
        val images   = HashMap<String, Uri>()
        val children = walkInto(context, rootUri, images, "")
        Tree(name = rootName, rootUri = rootUri, root = children, images = images)
    }

    private suspend fun walkInto(
        context: Context,
        dirUri: Uri,
        images: MutableMap<String, Uri>,
        prefix: String,
    ): List<Node> {
        val entries = listChildren(context, dirUri)
        val out = ArrayList<Node>(entries.size)
        for (e in entries) {
            if (e.name.startsWith(".")) continue
            if (e.isDirectory) {
                val children = walkInto(context, e.uri, images, "$prefix${e.name}/")
                // Show all non-hidden dirs (including empty ones the user just created)
                out += Node.Dir(name = e.name, uri = e.uri, children = children)
            } else if (isSupportedFile(e.name)) {
                out += Node.File(
                    name = e.name,
                    uri = e.uri,
                    size = e.size,
                    mtime = e.lastModified,
                    relativePath = "$prefix${e.name}",
                )
            } else if (isImageFile(e.name)) {
                // Images aren't shown as note rows but are indexed so that
                // ![[image.png]] embeds can resolve to a content:// URI.
                images[e.name.lowercase()] = e.uri
            }
        }
        // Dirs first, then files; alphabetical within each group
        out.sortWith(compareBy({ it !is Node.Dir }, { it.name.lowercase() }))
        return out
    }

    private fun isSupportedFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".md") || lower.endsWith(".markdown")
            || lower.endsWith(".pdf") || lower.endsWith(".docx")
            || lower.endsWith(".epub") || lower.endsWith(".txt")
            || lower.endsWith(".odt")
    }

    private val IMAGE_EXTS = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg")

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return IMAGE_EXTS.any { lower.endsWith(it) }
    }

    // ── Read / write ──────────────────────────────────────────────────────

    suspend fun readText(context: Context, fileUri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(fileUri)?.use { it.readBytes().decodeToString() } ?: ""
    }

    /**
     * The note's text, or null when it could not be read.
     *
     * The difference from [readText] is the whole point: returning "" for an
     * unreadable file was the most expensive answer possible. The editor opened
     * empty, and the first keystroke made autosave write that emptiness over a
     * note that was still perfectly fine on disk. A null says "I don't know",
     * and the caller can refuse to overwrite what it never read.
     */
    suspend fun readTextOrNull(context: Context, fileUri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(fileUri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
    }

    suspend fun readBytes(context: Context, fileUri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(fileUri)?.use {
            val buf = ByteArrayOutputStream()
            it.copyTo(buf)
            buf.toByteArray()
        } ?: ByteArray(0)
    }

    suspend fun writeText(context: Context, fileUri: Uri, content: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(fileUri, "wt")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
            it.flush()
        }
    }

    /**
     * Writes a note and says so — the caller learns whether the bytes landed.
     *
     * A null output stream used to mean the write silently did nothing, which
     * is how a save can fail with the app showing no sign of it.
     *
     * SAF gives no atomic replace: `renameDocument` refuses to overwrite an
     * existing name, so the desktop's write-then-rename cannot be reproduced
     * here. What is possible is to never be in a state where the only copy is
     * the truncated one — hence the sibling `.name.bak`, written before the
     * truncating write and removed once the real one is confirmed. `"wt"` means
     * truncate first, and on Android the process is killed as a matter of
     * routine, not as an exception.
     *
     * Throws on failure. Callers must not clear the "unsaved" mark unless this
     * returns normally.
     */
    suspend fun writeTextChecked(
        context: Context,
        fileUri: Uri,
        content: String,
        backupDir: Uri? = null,
        backupName: String? = null,
    ) = withContext(Dispatchers.IO) {
        // The name starts with a dot: walkVault skips dotfiles, so the copy
        // never shows up as a phantom note in the tree
        var backup: Uri? = null
        if (backupDir != null && backupName != null) {
            val previous = runCatching {
                context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
            }.getOrNull()
            if (previous != null && previous.isNotEmpty()) {
                backup = runCatching {
                    val uri = DocumentsContract.createDocument(
                        context.contentResolver, toDocumentUri(backupDir),
                        "application/octet-stream", ".$backupName.bak",
                    )
                    uri?.also { u ->
                        context.contentResolver.openOutputStream(u, "wt")?.use { it.write(previous) }
                    }
                }.getOrNull()
            }
        }

        val stream = context.contentResolver.openOutputStream(fileUri, "wt")
            ?: throw java.io.IOException("The system did not let this file be opened for writing")
        stream.use {
            it.write(content.toByteArray(Charsets.UTF_8))
            it.flush()
        }

        // The write landed; the copy has done its job
        backup?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
    }

    // ── Create / delete / rename ──────────────────────────────────────────

    /** Create a new markdown file in `parentDir`. Returns the new file URI. */
    suspend fun createMarkdownFile(
        context: Context,
        parentDir: Uri,
        baseName: String,
    ): Uri? = withContext(Dispatchers.IO) {
        val safeName = if (baseName.endsWith(".md")) baseName else "$baseName.md"
        DocumentsContract.createDocument(
            context.contentResolver,
            toDocumentUri(parentDir),   // tree URI → document URI for the root case
            "text/markdown",
            safeName
        )
    }

    suspend fun createDirectory(
        context: Context,
        parentDir: Uri,
        name: String,
    ): Uri? = withContext(Dispatchers.IO) {
        DocumentsContract.createDocument(
            context.contentResolver,
            toDocumentUri(parentDir),   // tree URI → document URI for the root case
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        )
    }

    /**
     * DocumentsContract.createDocument() requires a *document* URI as parent,
     * not a plain tree URI. The vault root is stored as a tree URI
     * (content://authority/tree/docId — 2 path segments). Convert it;
     * subdirectory URIs are already document-in-tree URIs and pass through unchanged.
     */
    private fun toDocumentUri(uri: Uri): Uri =
        if (uri.pathSegments.size <= 2) {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            DocumentsContract.buildDocumentUriUsingTree(uri, docId)
        } else {
            uri
        }

    /** Find an immediate child directory by name, creating it if absent. */
    suspend fun findOrCreateDir(
        context: Context,
        parentDir: Uri,
        name: String,
    ): Uri? = withContext(Dispatchers.IO) {
        listChildren(context, parentDir)
            .firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
            ?.uri
            ?: createDirectory(context, parentDir, name)
    }

    /**
     * Copy an external image (from the gallery picker) into [targetDir].
     * Returns the final file name written (deduplicated), or null on failure.
     */
    suspend fun importImage(
        context: Context,
        targetDir: Uri,
        sourceUri: Uri,
        suggestedName: String,
    ): String? = withContext(Dispatchers.IO) {
        val existing = listChildren(context, targetDir).map { it.name.lowercase() }.toHashSet()
        val finalName = uniqueName(suggestedName, existing)
        val mime = context.contentResolver.getType(sourceUri)
            ?: mimeForName(finalName)
        val destUri = DocumentsContract.createDocument(
            context.contentResolver,
            toDocumentUri(targetDir),
            mime,
            finalName,
        ) ?: return@withContext null
        runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(destUri)?.use { output ->
                    input.copyTo(output)
                }
            }
        }.getOrElse {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, destUri) }
            return@withContext null
        }
        finalName
    }

    private fun uniqueName(name: String, taken: Set<String>): String {
        if (name.lowercase() !in taken) return name
        val dot  = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext  = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while ("$base-$i$ext".lowercase() in taken) i++
        return "$base-$i$ext"
    }

    private fun mimeForName(name: String): String = when {
        name.endsWith(".png", true)  -> "image/png"
        name.endsWith(".gif", true)  -> "image/gif"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".bmp", true)  -> "image/bmp"
        name.endsWith(".svg", true)  -> "image/svg+xml"
        else                          -> "image/jpeg"
    }

    suspend fun delete(context: Context, docUri: Uri): Boolean = withContext(Dispatchers.IO) {
        DocumentsContract.deleteDocument(context.contentResolver, docUri)
    }

    suspend fun rename(context: Context, docUri: Uri, newName: String): Uri? = withContext(Dispatchers.IO) {
        DocumentsContract.renameDocument(context.contentResolver, docUri, newName)
    }

    // ── Move ──────────────────────────────────────────────────────────────

    /** True if [targetDir] already contains an entry named [name] (case-insensitive). */
    suspend fun hasChildNamed(context: Context, targetDir: Uri, name: String): Boolean =
        withContext(Dispatchers.IO) {
            listChildren(context, targetDir).any { it.name.equals(name, ignoreCase = true) }
        }

    /**
     * Move [sourceUri] from [sourceParent] into [targetParent].
     * Parent URIs may be tree URIs (vault root) or document URIs (subfolders);
     * both are normalised to document URIs as moveDocument requires.
     * Returns the moved document's URI, or null if the provider rejected the move.
     */
    suspend fun move(
        context: Context,
        sourceUri: Uri,
        sourceParent: Uri,
        targetParent: Uri,
    ): Uri? = withContext(Dispatchers.IO) {
        DocumentsContract.moveDocument(
            context.contentResolver,
            sourceUri,
            toDocumentUri(sourceParent),
            toDocumentUri(targetParent),
        )
    }
}

/** Raw cursor row — internal. */
data class Entry(
    val name: String,
    val uri: Uri,
    val mimeType: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/** Hierarchical node used by the UI. */
sealed class Node {
    abstract val name: String
    abstract val uri: Uri

    data class File(
        override val name: String,
        override val uri: Uri,
        val size: Long,
        val mtime: Long,
        /**
         * Path from the vault root, `/` separated — `Clinica/Sepse.md`.
         *
         * Flashcard ids are a hash of this plus the question, and the desktop
         * hashes the same string. Without it the phone would mint different
         * ids for the same cards and the shared srs.json would hold each card
         * twice, each with its own schedule.
         */
        val relativePath: String = name,
    ) : Node() {
        val isPdf:  Boolean get() = name.endsWith(".pdf",  ignoreCase = true)
        val isDocx: Boolean get() = name.endsWith(".docx", ignoreCase = true)
        val isEpub: Boolean get() = name.endsWith(".epub", ignoreCase = true)
        val isOdt:  Boolean get() = name.endsWith(".odt",  ignoreCase = true)
        /** Documento que o app sabe converter para Markdown: `.docx` e `.odt`. */
        val isConvertible: Boolean get() = isDocx || isOdt
        val isText: Boolean get() = !isPdf && !isDocx && !isEpub && !isOdt
        /** Display name without the .md extension. */
        val displayName: String get() = name.removeSuffix(".md").removeSuffix(".markdown")
    }

    data class Dir(
        override val name: String,
        override val uri: Uri,
        val children: List<Node>,
    ) : Node()
}

data class Tree(
    val name: String,
    val rootUri: Uri,
    val root: List<Node>,
    /** Lowercased image filename → content URI, for resolving ![[image.png]] embeds. */
    val images: Map<String, Uri> = emptyMap(),
) {
    companion object {
        fun empty(rootUri: Uri) = Tree(name = "Vault", rootUri = rootUri, root = emptyList())
    }

    /** Flat list of all markdown files — convenient for search/backlinks. */
    val allMarkdownFiles: List<Node.File> by lazy {
        val out = ArrayList<Node.File>()
        fun walk(nodes: List<Node>) {
            for (n in nodes) when (n) {
                is Node.File -> if (n.isText) out += n
                is Node.Dir  -> walk(n.children)
            }
        }
        walk(root)
        out
    }

    /** Notas .md da pasta `templates/` na raiz do vault (vazio se não existir). */
    val templates: List<Node.File> by lazy {
        root.filterIsInstance<Node.Dir>()
            .find { it.name.equals("templates", ignoreCase = true) }
            ?.children
            ?.filterIsInstance<Node.File>()
            ?.filter { it.isText }
            ?.sortedBy { it.displayName.lowercase() }
            ?: emptyList()
    }
}
