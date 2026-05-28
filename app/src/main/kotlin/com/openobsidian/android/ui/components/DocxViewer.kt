package com.openobsidian.android.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openobsidian.android.data.Node

/**
 * Viewer shown when the active file is a .docx document.
 *
 * Offers:
 *   - Open in an external app (Word, Google Docs, etc.)
 *   - Convert to Markdown (calls onConvertToMd)
 *   - Companion notes FAB (creates / opens a .md note alongside this file)
 */
@Composable
fun DocxViewer(
    file: Node.File,
    onOpenCompanionNote: () -> Unit,
    onConvertToMd: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Box(
        modifier         = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // ── Centre card ────────────────────────────────────────────────────
        Column(
            modifier                = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment     = Alignment.CenterHorizontally,
            verticalArrangement     = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint     = MaterialTheme.colorScheme.primary,
            )

            Text(
                file.name,
                style     = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )

            Text(
                "Word document\nAndroid preview is not available.",
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(4.dp))

            // Open in external app
            FilledTonalButton(
                onClick = {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                file.uri,
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            )
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open in app")
            }

            // Convert to Markdown
            OutlinedButton(onClick = onConvertToMd) {
                Icon(Icons.Default.Code, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Convert to Markdown")
            }
        }

        // ── Companion notes FAB ────────────────────────────────────────────
        FloatingActionButton(
            onClick        = onOpenCompanionNote,
            modifier       = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Row(
                modifier          = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Notes", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
