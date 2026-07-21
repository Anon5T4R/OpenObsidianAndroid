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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openobsidian.android.R
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
    /** Cards in the whole vault — zero means the syntax has to be explained */
    totalCards: Int,
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
                        if (card != null) stringResource(R.string.review_progress, done, remaining) else stringResource(R.string.review_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_review))
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
                        stringResource(R.string.review_schedule_failed),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            if (card == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            when {
                                done > 0 -> stringResource(R.string.review_finished)
                                totalCards == 0 -> stringResource(R.string.review_no_cards)
                                else -> stringResource(R.string.review_nothing_due)
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (totalCards == 0 && done == 0) {
                            // A button that opens onto nothing teaches nothing.
                            // Cards are written by hand, so the empty state is
                            // the only place that can say how.
                            Text(
                                stringResource(R.string.review_syntax_intro),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    stringResource(R.string.review_syntax_example),
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.review_syntax_outro),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            Text(
                                if (done > 0) stringResource(R.string.review_reviewed, done)
                                else stringResource(R.string.review_in_vault, totalCards),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onClose) { Text(stringResource(R.string.action_done)) }
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
                ) { Text(stringResource(R.string.review_show_answer)) }
            } else {
                // The four SM-2 grades. "Again" first, on the left, because it
                // is the one you reach for without thinking twice.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GradeButton(stringResource(R.string.grade_again), Modifier.weight(1f)) { onGrade(Srs.Grade.AGAIN) }
                    GradeButton(stringResource(R.string.grade_hard), Modifier.weight(1f)) { onGrade(Srs.Grade.HARD) }
                    GradeButton(stringResource(R.string.grade_good), Modifier.weight(1f)) { onGrade(Srs.Grade.GOOD) }
                    GradeButton(stringResource(R.string.grade_easy), Modifier.weight(1f)) { onGrade(Srs.Grade.EASY) }
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
