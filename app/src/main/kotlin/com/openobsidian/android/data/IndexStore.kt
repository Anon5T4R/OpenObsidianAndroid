package com.openobsidian.android.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where [IndexCache] is written: the app's own private storage, one file per
 * vault.
 *
 * Not inside the vault, on purpose. It is a derived cache — losing it costs a
 * re-read, nothing more — and a JSON blob the size of the whole vault has no
 * business sitting in a folder the user syncs and backs up.
 */
object IndexStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "index").apply { mkdirs() }

    /** A file name from the vault URI, safe on any filesystem. */
    private fun fileFor(context: Context, vaultUri: Uri): File {
        val key = Base64.encodeToString(
            vaultUri.toString().toByteArray(),
            Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
        )
        return File(dir(context), "$key.json")
    }

    suspend fun load(context: Context, vaultUri: Uri): Map<String, IndexCache.Entry> =
        withContext(Dispatchers.IO) {
            val f = fileFor(context, vaultUri)
            if (!f.exists()) return@withContext emptyMap()
            runCatching { IndexCache.fromJson(f.readText()) }.getOrElse { emptyMap() }
        }

    /**
     * Saves the cache. Failure is not reported anywhere on purpose: this is
     * only ever an optimisation, and a full disk must not interrupt someone
     * writing a note.
     */
    suspend fun save(context: Context, vaultUri: Uri, entries: Map<String, IndexCache.Entry>) =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = fileFor(context, vaultUri)
                // Write-then-rename: a cache truncated halfway would be thrown
                // away on the next launch, costing exactly what it saved
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeText(IndexCache.toJson(entries))
                if (!tmp.renameTo(target)) {
                    target.writeText(IndexCache.toJson(entries))
                    tmp.delete()
                }
            }
            Unit
        }

    /** Drops the cache of a vault the user forgot. */
    suspend fun clear(context: Context, vaultUri: Uri) = withContext(Dispatchers.IO) {
        runCatching { fileFor(context, vaultUri).delete() }
        Unit
    }
}
