package com.univibe.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.data.repository.SponsoredCard
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.delay

/** How long a card must stay composed before it counts as seen. */
private const val SEEN_AFTER_MS = 1_000L

/** Reasons offered by "Report this ad", as the server names them. */
private val REPORT_REASONS = listOf("Misleading" to "misleading", "Inappropriate" to "inappropriate", "Spam" to "spam", "Scam" to "scam")

/**
 * A paid card in the feed. It is always labelled "Sponsored" and names who paid, and it looks like a card rather than a
 * post: no likes, comments or follower counts, so it can never be mistaken for something a student wrote.
 */
@Composable
fun SponsoredCardView(
    card: SponsoredCard,
    onShown: () -> Unit,
    onOpen: (() -> Unit)?,
    onCta: (() -> Unit)?,
    onReport: (reason: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(card.campaignId) { delay(SEEN_AFTER_MS); onShown() }
    var menu by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    VibeCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .semantics { contentDescription = "Sponsored. ${card.advertiser}. ${card.headline}" },
        onClick = onOpen,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(50), color = primary.copy(alpha = 0.12f), border = BorderStroke(0.75.dp, primary.copy(alpha = 0.5f))) {
                Text("Sponsored", Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = primary, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(Spacing.sm))
            Text(
                if (card.advertiserVerified) "${card.advertiser} · Verified" else card.advertiser,
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ad options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text("Report this ad", Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                REPORT_REASONS.forEach { (label, code) -> DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onReport(code) }) }
            }
        }
        Spacer(Modifier.padding(top = Spacing.xs))
        Text(card.headline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (card.body.isNotBlank()) Text(card.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        card.eventLine?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (onCta != null && card.ctaLabel != null) {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.End) {
                VibeButton(text = card.ctaLabel, onClick = onCta, compact = true)
            }
        }
    }
}

/** Opens an advertiser's link in the browser. Only https links are ever opened. */
fun openSponsoredLink(context: Context, url: String) {
    if (!url.startsWith("https://")) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
