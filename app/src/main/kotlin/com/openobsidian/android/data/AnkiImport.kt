package com.openobsidian.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Importing an Anki `.apkg` into the vault.
 *
 * The collection inside the package is plain SQLite, and Android ships SQLite —
 * so this needs no dependency at all. The desktop had to bring sql.js compiled
 * to WASM for the same job.
 *
 * What Android does *not* have is zstd, which Anki 2.1.50+ uses for
 * `collection.anki21b`. Rather than adding a native library to the APK for it,
 * a package in that format is refused with the exact export option to use —
 * a precise instruction beats a mysterious failure, and most packages found in
 * the wild still carry a legacy collection alongside.
 */
object AnkiImport {

    sealed class Result {
        data class Ready(val deck: String, val cards: List<AnkiPackage.Card>, val notes: Int) : Result()
        /** The package is zstd-compressed; [needsLegacyExport] tells the UI which message to show. */
        object NeedsLegacyExport : Result()
        data class Failed(val reason: String) : Result()
    }

    /** Reads the deck without writing anything, so the UI can confirm first. */
    suspend fun read(context: Context, apkgUri: Uri, fileName: String): Result =
        withContext(Dispatchers.IO) {
            val collection = runCatching { extractCollection(context, apkgUri) }
                .getOrElse { return@withContext Result.Failed(it.message ?: "unreadable package") }
                ?: return@withContext Result.Failed("no collection inside the package")

            if (collection.zstd) return@withContext Result.NeedsLegacyExport

            val cards = runCatching { readNotes(collection.file) }
                .getOrElse { return@withContext Result.Failed(it.message ?: "unreadable collection") }
            collection.file.delete()

            if (cards.isEmpty()) return@withContext Result.Failed("no cards found")
            Result.Ready(
                deck = AnkiPackage.deckNameFor(fileName),
                cards = cards,
                notes = AnkiPackage.chunk(cards).size,
            )
        }

    /**
     * Writes the deck into the vault: one note, or a folder of notes when the
     * deck is big enough that a single note would be slow to open.
     */
    suspend fun write(
        context: Context,
        vaultRoot: Uri,
        deck: String,
        cards: List<AnkiPackage.Card>,
    ): Int = withContext(Dispatchers.IO) {
        val chunks = AnkiPackage.chunk(cards)
        if (chunks.size == 1) {
            val uri = SafFs.createMarkdownFile(context, vaultRoot, uniqueName(context, vaultRoot, deck))
                ?: return@withContext 0
            SafFs.writeTextChecked(context, uri, AnkiPackage.toMarkdown(deck, chunks[0]))
            return@withContext 1
        }
        val dir = SafFs.findOrCreateDir(context, vaultRoot, deck) ?: return@withContext 0
        var written = 0
        chunks.forEachIndexed { i, chunk ->
            val name = "$deck ${i + 1}"
            val uri = SafFs.createMarkdownFile(context, dir, name) ?: return@forEachIndexed
            SafFs.writeTextChecked(context, uri, AnkiPackage.toMarkdown(name, chunk))
            written++
        }
        written
    }

    /** A name that does not overwrite a note already there. */
    private suspend fun uniqueName(context: Context, dir: Uri, base: String): String {
        val taken = SafFs.listChildren(context, dir).map { it.name.lowercase() }.toSet()
        if ("$base.md".lowercase() !in taken) return base
        var i = 2
        while ("$base $i.md".lowercase() in taken) i++
        return "$base $i"
    }

    private data class Collection(val file: File, val zstd: Boolean)

    /**
     * Pulls the collection out of the ZIP into a private temp file — SQLite
     * needs a real path, and a `content://` URI is not one.
     */
    private fun extractCollection(context: Context, apkgUri: Uri): Collection? {
        val found = LinkedHashMap<String, ByteArray>()
        context.contentResolver.openInputStream(apkgUri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name in AnkiPackage.COLLECTION_NAMES) {
                        val buf = ByteArrayOutputStream()
                        zip.copyTo(buf)
                        found[entry.name] = buf.toByteArray()
                    }
                    zip.closeEntry()
                }
            }
        } ?: return null

        // Newest layout first, but a readable older one wins over a compressed
        // newer one: the whole package is usable either way
        var sawZstd = false
        for (name in AnkiPackage.COLLECTION_NAMES) {
            val data = found[name] ?: continue
            if (AnkiPackage.isSqlite(data)) {
                val tmp = File.createTempFile("anki", ".db", context.cacheDir)
                tmp.writeBytes(data)
                return Collection(tmp, zstd = false)
            }
            if (AnkiPackage.isZstd(data)) sawZstd = true
        }
        if (sawZstd) return Collection(File(""), zstd = true)
        return null
    }

    private fun readNotes(db: File): List<AnkiPackage.Card> {
        val out = mutableListOf<AnkiPackage.Card>()
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            sqlite.rawQuery("SELECT flds, tags FROM notes", null).use { c ->
                while (c.moveToNext()) {
                    val flds = c.getString(0) ?: continue
                    val tags = c.getString(1) ?: ""
                    AnkiPackage.rowToCard(flds, tags)?.let { out += it }
                }
            }
        }
        return out
    }

    /** True when the picked document looks like an Anki package. */
    fun isApkg(name: String): Boolean = name.endsWith(".apkg", ignoreCase = true)

    /** Unused today, kept so the import path does not depend on DocumentsContract elsewhere. */
    internal fun documentName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
}
