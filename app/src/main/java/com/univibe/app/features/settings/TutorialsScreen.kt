package com.univibe.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.app.AppContainer
import com.univibe.app.guide.Tutorial
import com.univibe.app.guide.TourProgress
import com.univibe.app.guide.TutorialRegistry
import com.univibe.app.ui.components.VibeIcons

/**
 * Help and tutorials: every registered tour with its status. "Continue" picks up at the saved step; "Start over"
 * replays from the beginning. Starting a tour opens the right screen first (done by the app shell via [onStart]).
 */
@Composable
fun TutorialsScreen(userId: String, onBack: () -> Unit, onStart: (Tutorial, Int) -> Unit) {
    val tutorials = remember { TutorialRegistry.all() }
    SettingsPage("Help and tutorials", onBack) {
        item {
            Text(
                "Short guided tours of UniVibe. Nothing is changed or sent while you take one, and you can stop at any step.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (tutorials.isEmpty()) {
            item { Text("No tutorials are available right now.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            item {
                SettingsGroup {
                    tutorials.forEachIndexed { i, t ->
                        if (i > 0) RowDivider()
                        val progress by remember(t.id) { AppContainer.guideStore.progress(userId, t.id) }
                            .collectAsStateWithLifecycle(TourProgress.NotStarted)
                        val resumeAt = (progress as? TourProgress.InProgress)?.step?.takeIf { it in 1 until t.steps.size }
                        val status = when (val p = progress) {
                            TourProgress.NotStarted -> "Not started"
                            is TourProgress.InProgress -> if (resumeAt != null) "In progress · step ${resumeAt + 1} of ${t.steps.size}" else "Not finished"
                            TourProgress.Completed -> "Completed"
                            TourProgress.Skipped -> "Skipped"
                        }
                        Column {
                            SettingsNavRow(
                                VibeIcons.Info, t.title, { onStart(t, resumeAt ?: 0) },
                                subtitle = "$status · ${t.description}",
                            )
                            if (resumeAt != null) {
                                RowDivider()
                                SettingsNavRow(VibeIcons.Help, "Start “${t.title}” over", { onStart(t, 0) })
                            }
                        }
                    }
                }
            }
        }
    }
}
