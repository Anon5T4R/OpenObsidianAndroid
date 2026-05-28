package com.openobsidian.android.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "openobsidian")

// Field separator inside one persisted "recent" line.
// Tab is rare in content URIs and SAF display names.
private const val SEP = "\t"

/**
 * Owns vault selection state and persisted URI permissions.
 *
 * A "vault" = a content:// tree URI returned by ACTION_OPEN_DOCUMENT_TREE.
 * We persist:
 *   - lastVault: URI of the most recently opened vault (auto-reopen)
 *   - recentVaults: up to 8 vaults the user has opened, newest first
 *
 * Permission persistence is handled by ContentResolver — we just remember
 * which URIs we granted ourselves access to.
 */
class VaultRepository(private val context: Context) {

    private val keyLast    = stringPreferencesKey("last_vault")
    private val keyRecents = stringPreferencesKey("recent_vaults")

    val lastVault: Flow<String?> =
        context.dataStore.data.map { it[keyLast] }

    val recentVaults: Flow<List<RecentVault>> =
        context.dataStore.data.map { prefs ->
            (prefs[keyRecents] ?: "")
                .split("\n")
                .mapNotNull { line ->
                    val parts = line.split(SEP, limit = 2)
                    if (parts.size != 2 || parts[0].isBlank()) null
                    else RecentVault(uri = parts[0], displayName = parts[1])
                }
        }

    /** Take persistent permission and remember the vault. */
    suspend fun acceptPickedVault(uri: Uri): RecentVault {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val name = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: "Vault"
        val entry = RecentVault(uri.toString(), name)

        context.dataStore.edit { prefs ->
            prefs[keyLast] = entry.uri
            val current = (prefs[keyRecents] ?: "")
                .split("\n")
                .filter { it.isNotBlank() && !it.startsWith("${entry.uri}$SEP") }
            val updated = listOf("${entry.uri}$SEP${entry.displayName}") + current
            prefs[keyRecents] = updated.take(8).joinToString("\n")
        }
        return entry
    }

    suspend fun setLastVault(uri: String) {
        context.dataStore.edit { it[keyLast] = uri }
    }

    suspend fun forgetVault(uri: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        context.dataStore.edit { prefs ->
            if (prefs[keyLast] == uri) prefs.remove(keyLast)
            val remaining = (prefs[keyRecents] ?: "")
                .split("\n")
                .filter { it.isNotBlank() && !it.startsWith("$uri$SEP") }
            prefs[keyRecents] = remaining.joinToString("\n")
        }
    }

    suspend fun currentLast(): String? = lastVault.first()
}

data class RecentVault(val uri: String, val displayName: String)
