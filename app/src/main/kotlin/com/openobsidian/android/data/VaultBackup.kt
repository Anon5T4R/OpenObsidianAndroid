package com.openobsidian.android.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The whole vault as a single `.zip`, written wherever the user points.
 *
 * The notes are already plain files, so this is not the only copy that exists —
 * but "already plain files" does not help when the folder is on a phone that
 * gets lost. One file is also something you can actually attach, upload or
 * hand over.
 */
object VaultBackup {

    data class Result(val files: Int, val bytes: Long, val skipped: Int)

    /**
     * Zips [vaultUri] into [destUri].
     *
     * Everything is included, not just the notes: attachments, PDFs and the
     * `.openobsidian` state. A backup that quietly drops the images is worse
     * than no backup, because you find out when you need it.
     *
     * A file that cannot be read is counted and skipped rather than aborting
     * the whole thing — a partial backup that says what it missed beats no
     * backup at all.
     */
    suspend fun zipVault(context: Context, vaultUri: Uri, destUri: Uri): Result =
        withContext(Dispatchers.IO) {
            var files = 0
            var bytes = 0L
            var skipped = 0

            val out = context.contentResolver.openOutputStream(destUri)
                ?: throw java.io.IOException("Could not open the destination for writing")

            ZipOutputStream(out.buffered()).use { zip ->
                // Iterative rather than recursive: listChildren is suspending,
                // and a work list keeps that out of a nested local function
                val pending = ArrayDeque<Pair<Uri, String>>()
                pending += vaultUri to ""
                while (pending.isNotEmpty()) {
                    val (dirUri, prefix) = pending.removeFirst()
                    for (entry in SafFs.listChildren(context, dirUri)) {
                        val path = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
                        if (entry.isDirectory) {
                            pending += entry.uri to path
                            continue
                        }
                        val data = runCatching {
                            context.contentResolver.openInputStream(entry.uri)?.use { it.readBytes() }
                        }.getOrNull()
                        if (data == null) { skipped++; continue }
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(data)
                        zip.closeEntry()
                        files++
                        bytes += data.size
                    }
                }
            }
            Result(files, bytes, skipped)
        }

    /** A name that sorts by date and says which vault it came from. */
    fun suggestedName(vaultName: String, today: java.time.LocalDate = java.time.LocalDate.now()): String {
        val safe = vaultName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "vault" }
        return "$safe-$today.zip"
    }
}
