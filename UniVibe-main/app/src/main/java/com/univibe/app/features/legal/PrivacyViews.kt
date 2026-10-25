package com.univibe.app.features.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.theme.Spacing

/** The Privacy Policy as list items, so the same text is used in a full page (Settings) and in a sheet (sign-up, About). */
fun LazyListScope.privacyPolicyItems() {
    item(key = "privacy-header") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                "Version ${PrivacyPolicy.VERSION} · Updated ${PrivacyPolicy.UPDATED}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "This Policy explains what personal data UniVibe collects, why, who sees it, how long we keep it, and the choices you have.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    PrivacyPolicy.sections.forEach { section ->
        item(key = "privacy-${section.number}") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    "${section.number}. ${section.title}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }.padding(top = Spacing.sm),
                )
                section.clauses.forEach { clause -> ClauseText(clause) }
            }
        }
    }
    item(key = "privacy-footer") {
        Text(
            LegalContact.COPYRIGHT, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.md),
        )
    }
}

/** The Privacy Policy in a scrolling box of bounded height, for use inside a bottom sheet. */
@Composable
fun PrivacyPolicyBox(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().heightIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) { privacyPolicyItems() }
}
