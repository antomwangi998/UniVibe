package com.univibe.app.features.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.theme.Spacing

/** The Terms as list items, so the same text is used in a full page (Settings) and in a sheet (sign-up). */
fun LazyListScope.termsOfServiceItems() {
    item(key = "terms-header") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                "Version ${TermsOfService.VERSION} · Updated ${TermsOfService.UPDATED}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Please read these Terms. They explain what you can expect from UniVibe and what we expect from you.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    TermsOfService.sections.forEach { section ->
        item(key = "terms-${section.number}") {
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
    item(key = "terms-footer") {
        Text(
            LegalContact.COPYRIGHT, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.md),
        )
    }
}

@Composable
internal fun ClauseText(clause: LegalClause) {
    if (clause.label == "•") {
        Row(Modifier.fillMaxWidth().padding(start = Spacing.sm), verticalAlignment = Alignment.Top) {
            Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = Spacing.sm))
            Text(clause.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        return
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(clause.label)
            if (clause.heading.isNotEmpty()) append(" ${clause.heading}")
        }
        if (clause.text.isNotEmpty()) append(if (clause.heading.isEmpty() && clause.label.isNotEmpty()) "  ${clause.text}" else " ${clause.text}")
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/** The Terms in a scrolling box of bounded height, for use inside a bottom sheet. */
@Composable
fun TermsOfServiceBox(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().heightIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) { termsOfServiceItems() }
}
