package com.openobsidian.android.data

import androidx.compose.runtime.staticCompositionLocalOf

// ─────────────────────────────────────────────────────────────────────────────
// Enums
// ─────────────────────────────────────────────────────────────────────────────

enum class AppTheme(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

enum class PreviewFontSize(val sp: Float, val label: String) {
    SMALL(14f, "Small"),
    MEDIUM(16f, "Medium"),
    LARGE(19f, "Large"),
}

enum class SortOrder(val label: String) {
    NAME_ASC("A → Z"),
    NAME_DESC("Z → A"),
    MODIFIED("Recent"),
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings data class
// ─────────────────────────────────────────────────────────────────────────────

data class AppSettings(
    val theme: AppTheme = AppTheme.SYSTEM,
    val previewFontSize: PreviewFontSize = PreviewFontSize.MEDIUM,
    val sortOrder: SortOrder = SortOrder.NAME_ASC,
)

// ─────────────────────────────────────────────────────────────────────────────
// CompositionLocal — lets any composable read the current settings without
// threading them through every parameter.
// ─────────────────────────────────────────────────────────────────────────────

val LocalAppSettings = staticCompositionLocalOf { AppSettings() }
