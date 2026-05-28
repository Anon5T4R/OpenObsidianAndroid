package com.openobsidian.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private val keyTheme     = stringPreferencesKey("theme")
    private val keyFontSize  = stringPreferencesKey("font_size")
    private val keySortOrder = stringPreferencesKey("sort_order")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            theme = when (prefs[keyTheme]) {
                AppTheme.LIGHT.name -> AppTheme.LIGHT
                AppTheme.DARK.name  -> AppTheme.DARK
                else                -> AppTheme.SYSTEM
            },
            previewFontSize = when (prefs[keyFontSize]) {
                PreviewFontSize.SMALL.name -> PreviewFontSize.SMALL
                PreviewFontSize.LARGE.name -> PreviewFontSize.LARGE
                else                       -> PreviewFontSize.MEDIUM
            },
            sortOrder = when (prefs[keySortOrder]) {
                SortOrder.NAME_DESC.name -> SortOrder.NAME_DESC
                SortOrder.MODIFIED.name  -> SortOrder.MODIFIED
                else                     -> SortOrder.NAME_ASC
            },
        )
    }

    suspend fun setTheme(theme: AppTheme) {
        context.settingsDataStore.edit { it[keyTheme] = theme.name }
    }

    suspend fun setPreviewFontSize(size: PreviewFontSize) {
        context.settingsDataStore.edit { it[keyFontSize] = size.name }
    }

    suspend fun setSortOrder(order: SortOrder) {
        context.settingsDataStore.edit { it[keySortOrder] = order.name }
    }
}
