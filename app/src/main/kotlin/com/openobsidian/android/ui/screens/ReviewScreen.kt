package com.openobsidian.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openobsidian.android.data.Srs
import com.openobsidian.android.viewmodel.ReviewCard

/**
 * A review session.
 *
 * Deliberately one card at a time, filling the screen: reviewing on a phone
 * competes with everything else on it, and a dense screen makes you skim
 * instead of recall. Show the question, decide you know it, then check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    card: ReviewCard?,
    revealed: Boolean,
    done: Int,
    remaining: Int,
    scheduleFailed: Boolean,
    onReveal: () -> Unit,
    onGrade: (Srs.Grade) -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (card != null) "$done done · $remaining to go" else "Review",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close review")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            // A schedule that cannot be written means the session is not being
            // recorded — better to say it during than to lose it at the end
            if (scheduleFailed) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        "The schedule could not be saved — this session is not being recorded.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            if (card == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (done > 0) "Session finished" else "Nothing due right now",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (done > 0) "$done card(s) reviewed."
                            else "Cards show up here on the day they are due.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onClose) { Text("Done") }
                    }
                }
                return@Column
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    card.note,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    card.question,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                )
                if (revealed) {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(20.dp))
                    Text(card.answer, style = MaterialTheme.typography.bodyLarge)
                }
            }

            if (!revealed) {
                Button(
                    onClick = onReveal,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                ) { Text("Show answer") }
            } else {
                // The four SM-2 grades. "Again" first, on the left, because it
                // is the one you reach for without thinking twice.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GradeButton("Again", Modifier.weight(1f)) { onGrade(Srs.Grade.AGAIN) }
                    GradeButton("Hard", Modifier.weight(1f)) { onGrade(Srs.Grade.HARD) }
                    GradeButton("Good", Modifier.weight(1f)) { onGrade(Srs.Grade.GOOD) }
                    GradeButton("Easy", Modifier.weight(1f)) { onGrade(Srs.Grade.EASY) }
                }
            }
        }
    }
}

@Composable
private fun GradeButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
