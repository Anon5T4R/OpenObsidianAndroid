package com.openobsidian.android.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the review schedule lives: `.openobsidian/srs.json` in the vault.
 *
 * Never inside the notes — scheduling changes on every single review and would
 * dirty the diff of every file. And the same file the desktop writes, so a card
 * reviewed on the phone is not asked again on the computer that evening.
 */
object SrsStore {

    private const val DIR = ".openobsidian"
    private const val FILE = "srs.json"

    /**
     * The `.openobsidian` folder, created on demand.
     * `walkVault` skips dotfiles, so it never shows up as a folder in the tree.
     */
    private suspend fun stateDir(context: Context, rootUri: Uri): Uri? =
        SafFs.findOrCreateDir(context, rootUri, DIR)

    private suspend fun stateFile(context: Context, rootUri: Uri, create: Boolean): Uri? =
        withContext(Dispatchers.IO) {
            val dir = stateDir(context, rootUri) ?: return@withContext null
            val existing = SafFs.listChildren(context, dir).find { it.name == FILE }
            if (existing != null) return@withContext existing.uri
            if (!create) return@withContext null
            runCatching {
                DocumentsContract.createDocument(
                    context.contentResolver, dir, "application/json", FILE,
                )
            }.getOrNull()
        }

    /** The stored schedule; an empty one when there is nothing (or nothing readable). */
    suspend fun load(context: Context, rootUri: Uri): Map<String, Srs.Card> {
        val uri = stateFile(context, rootUri, create = false) ?: return emptyMap()
        val json = SafFs.readTextOrNull(context, uri) ?: return emptyMap()
        return Srs.fromJson(json)
    }

    /**
     * Persists the schedule. Returns false when it could not be written, so a
     * review session can say so instead of pretending it was recorded.
     */
    suspend fun save(context: Context, rootUri: Uri, cards: Map<String, Srs.Card>): Boolean {
        val uri = stateFile(context, rootUri, create = true) ?: return false
        return runCatching {
            SafFs.writeTextChecked(context, uri, Srs.toJson(cards))
        }.isSuccess
    }
}
