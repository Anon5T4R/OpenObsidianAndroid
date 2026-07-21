package com.openobsidian.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openobsidian.android.R
import com.openobsidian.android.data.AppSettings
import com.openobsidian.android.data.AppTheme
import com.openobsidian.android.data.PreviewFontSize
import com.openobsidian.android.data.SortOrder

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onThemeChange: (AppTheme) -> Unit,
    onFontSizeChange: (PreviewFontSize) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        // ── Top bar ────────────────────────────────────────────────────────
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                stringResource(R.string.settings_title),
                style    = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        HorizontalDivider()

        LazyColumn(
            modifier       = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // ── Theme ──────────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    AppTheme.entries.forEachIndexed { index, theme ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = AppTheme.entries.size,
                            ),
                            onClick   = { onThemeChange(theme) },
                            selected  = settings.theme == theme,
                            label     = { Text(theme.label) },
                        )
                    }
                }
            }

            // ── Preview font size ──────────────────────────────────────────
            item {
                Spacer(Modifier.height(28.dp))
                Text(
                    stringResource(R.string.settings_preview_font),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    PreviewFontSize.entries.forEachIndexed { index, size ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = PreviewFontSize.entries.size,
                            ),
                            onClick  = { onFontSizeChange(size) },
                            selected = settings.previewFontSize == size,
                            label    = { Text(size.label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Preview sample
                Surface(
                    modifier       = Modifier.fillMaxWidth(),
                    shape          = MaterialTheme.shapes.medium,
                    color          = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 1.dp,
                ) {
                    Text(
                        stringResource(R.string.settings_font_sample),
                        style    = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = androidx.compose.ui.unit.TextUnit(
                                settings.previewFontSize.sp,
                                androidx.compose.ui.unit.TextUnitType.Sp,
                            )
                        ),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // ── Sort order ──────────────────────────────────────────────────
            item {
                Spacer(Modifier.height(28.dp))
                Text(
                    stringResource(R.string.settings_sort_order),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SortOrder.entries.forEachIndexed { index, order ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = SortOrder.entries.size,
                            ),
                            onClick  = { onSortOrderChange(order) },
                            selected = settings.sortOrder == order,
                            label    = { Text(order.label) },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}
