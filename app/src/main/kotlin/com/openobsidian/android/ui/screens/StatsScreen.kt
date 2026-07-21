package com.openobsidian.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openobsidian.android.R
import com.openobsidian.android.data.SrsReport
import kotlin.math.roundToInt

/**
 * How the studying is going, and how bad next week looks.
 *
 * Those are the two questions a review backlog actually raises; counting cards
 * is the easy part. Read-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(report: SrsReport.Report, onClose: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (report.total == 0) {
                Text(
                    stringResource(R.string.review_no_cards),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.stats_total), "${report.total}", Modifier.weight(1f))
                Stat(stringResource(R.string.stats_due), "${report.due}", Modifier.weight(1f))
                Stat(stringResource(R.string.stats_fresh), "${report.fresh}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(
                    stringResource(R.string.stats_retention),
                    "${(report.retention * 100).roundToInt()}%",
                    Modifier.weight(1f),
                )
                Stat(
                    stringResource(R.string.stats_ease),
                    String.format("%.2f", report.averageEase),
                    Modifier.weight(1f),
                )
                Stat(stringResource(R.string.stats_suspended), "${report.suspended}", Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.stats_forecast),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Forecast(report.forecast)

            if (report.topNotes.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.stats_top_notes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                for ((note, count) in report.topNotes) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(note.removeSuffix(".md"), style = MaterialTheme.typography.bodyMedium)
                        Text("$count", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A bar per day. Deliberately drawn with plain boxes rather than a chart
 * library: it is fourteen numbers, and the shape of the backlog is the whole
 * message — a wall on day one reads the same at any resolution.
 */
@Composable
private fun Forecast(days: List<Pair<String, Int>>) {
    val peak = (days.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth().height(120.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((iso, count) in days) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (count > 0) {
                    Text("$count", style = MaterialTheme.typography.labelSmall)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        // A day with nothing due still shows a sliver, so the
                        // row reads as a calendar rather than as gaps
                        .height((6 + (94f * count / peak)).dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(
                            if (count > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                )
                Text(
                    iso.takeLast(2),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
