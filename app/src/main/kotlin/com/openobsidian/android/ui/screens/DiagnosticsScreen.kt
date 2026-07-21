package com.openobsidian.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openobsidian.android.R
import com.openobsidian.android.data.LinkResolver

/**
 * What is quietly wrong with the vault.
 *
 * A broken link, an orphan note and two notes sharing a name are all invisible
 * while you write — you only find out months later, when a link goes nowhere.
 * This is the desktop's diagnostics panel (v0.10.0), read-only: it says what it
 * found, and fixing is your call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    brokenLinks: List<LinkResolver.Broken>,
    orphanNotes: List<String>,
    duplicateNames: List<Pair<String, Int>>,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val nothing = brokenLinks.isEmpty() && orphanNotes.isEmpty() && duplicateNames.isEmpty()
        if (nothing) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.diagnostics_clean),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (brokenLinks.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.diagnostics_broken, brokenLinks.size)) }
                items(brokenLinks, key = { "b:" + it.target }) { b ->
                    ListItem(
                        headlineContent = { Text("[[${b.target}]]") },
                        // Naming who points at it is the difference between a
                        // list of complaints and something you can act on
                        supportingContent = { Text(b.sources.joinToString(" · ")) },
                    )
                }
            }
            if (duplicateNames.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.diagnostics_duplicates, duplicateNames.size)) }
                items(duplicateNames, key = { "d:" + it.first }) { (name, count) ->
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text(stringResource(R.string.diagnostics_notes_count, count)) },
                    )
                }
            }
            if (orphanNotes.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.diagnostics_orphans, orphanNotes.size)) }
                items(orphanNotes, key = { "o:$it" }) { name ->
                    ListItem(headlineContent = { Text(name) })
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}
