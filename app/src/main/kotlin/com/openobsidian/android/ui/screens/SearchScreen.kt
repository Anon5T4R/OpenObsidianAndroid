package com.openobsidian.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openobsidian.android.R
import com.openobsidian.android.data.Node
import com.openobsidian.android.viewmodel.SearchResult

@Composable
fun SearchScreen(
    query: String,
    results: List<SearchResult>,
    indexing: Boolean,
    totalNotes: Int,
    onQueryChange: (String) -> Unit,
    onResultClick: (Node.File) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // ── Search bar ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_close_search))
            }
            OutlinedTextField(
                value         = query,
                onValueChange = onQueryChange,
                placeholder   = { Text(stringResource(R.string.search_placeholder)) },
                singleLine    = true,
                leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon  = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
        }

        HorizontalDivider()

        // ── Status line ────────────────────────────────────────────────────
        if (indexing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Indexing $totalNotes notes…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        } else if (query.isNotBlank()) {
            Text(
                "${results.size} result${if (results.size != 1) "s" else ""}",
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // ── Results ────────────────────────────────────────────────────────
        when {
            query.isBlank() -> {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
            results.isEmpty() && !indexing -> {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.search_no_results, query),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(results, key = { it.file.uri.toString() }) { result ->
                        SearchResultItem(
                            result  = result,
                            query   = query,
                            onClick = { onResultClick(result.file) },
                        )
                        HorizontalDivider(
                            modifier  = Modifier.padding(horizontal = 16.dp),
                            thickness = 0.5.dp,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SearchResultItem(
    result: SearchResult,
    query: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Article,
            contentDescription = null,
            modifier = Modifier.size(18.dp).padding(top = 2.dp),
            tint     = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                result.file.displayName,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = buildSnippetAnnotated(result),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun buildSnippetAnnotated(result: SearchResult) = buildAnnotatedString {
    val s = result.snippet
    val start = result.matchStart.coerceIn(0, s.length)
    val end   = (result.matchStart + result.matchLength).coerceIn(start, s.length)
    append(s.substring(0, start))
    pushStyle(SpanStyle(
        fontWeight = FontWeight.Bold,
        background = Color(0xFFFFEE44),
        color      = Color.Black,
    ))
    append(s.substring(start, end))
    pop()
    append(s.substring(end))
}
