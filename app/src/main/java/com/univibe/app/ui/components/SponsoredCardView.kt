package com.univibe.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.clickable
import com.univibe.app.app.AppContainer
import kotlinx.coroutines.launch
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.univibe.app.features.promotions.categoryLabel
import com.univibe.app.features.promotions.sponsoredDefaultCta
import com.univibe.app.features.promotions.sponsoredMediaAspect
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

/** After a tap on the button, further taps are ignored for this long. */
private const val CTA_LOCK_MS = 1_500L

/** Reasons offered by "Report this ad", as the server names them. */
private val REPORT_REASONS = listOf("Misleading" to "misleading", "Inappropriate" to "inappropriate", "Spam" to "spam", "Scam" to "scam")

/**
 * A paid card in the feed. It is always labelled "Sponsored" and names who paid, so it can never be mistaken for something a
 * student wrote, but it reacts like a post: a photo or video, a button bar, like, comment and share.
 * Logo, media, event line and category are drawn only when the server sent them; nothing is invented.
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
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    // The button runs the campaign's link; with no link it falls back to opening the event or post the card promotes.
    val ctaAction = onCta ?: onOpen
    val ctaLabel = card.ctaLabel ?: sponsoredDefaultCta(card.targetType)
    // A preview (no impression token) is drawn the same but reacts to nothing and talks to no server.
    val live = card.impressionToken.isNotEmpty()
    val repo = remember { AppContainer.sponsoredRepository }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var liked by remember(card.campaignId) { mutableStateOf(card.liked) }
    var likes by remember(card.campaignId) { mutableIntStateOf(card.likes) }
    var commentCount by remember(card.campaignId) { mutableIntStateOf(card.comments) }
    var showComments by remember { mutableStateOf(false) }
    val engagementRow: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedLikeButton(liked = liked, count = likes, onClick = {
                if (!live) return@AnimatedLikeButton
                val next = !liked
                liked = next; likes = (likes + if (next) 1 else -1).coerceAtLeast(0)
                scope.launch {
                    runCatching { repo.setLiked(card.campaignId, next) }.onFailure {
                        liked = !next; likes = (likes + if (next) -1 else 1).coerceAtLeast(0)
                    }
                }
            })
            Spacer(Modifier.width(Spacing.md))
            SocialActionButton(
                icon = VibeIcons.Comment, contentDescription = "Comments, $commentCount",
                onClick = { if (live) showComments = true }, count = commentCount,
            )
            Spacer(Modifier.width(Spacing.md))
            SocialActionButton(
                icon = VibeIcons.Share, contentDescription = "Share this ad",
                onClick = { shareSponsored(context, card) },
            )
        }
    }
    VibeCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .semantics { contentDescription = "Sponsored. ${card.advertiser}. ${card.headline}" },
        onClick = onOpen,
        contentPadding = PaddingValues(start = Spacing.md, top = Spacing.md, end = Spacing.sm, bottom = Spacing.md),
    ) {
        Row(Modifier.padding(end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            AdvertiserLogo(card.advertiser, card.advertiserLogoUrl)
            Spacer(Modifier.width(Spacing.sm + 2.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card.advertiser, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (card.advertiserVerified) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            Modifier.size(14.dp).clip(CircleShape).background(primary)
                                .semantics { contentDescription = "Verified advertiser" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(VibeIcons.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(9.dp)) }
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Surface(shape = RoundedCornerShape(50), color = primary.copy(alpha = 0.12f), border = BorderStroke(0.75.dp, primary.copy(alpha = 0.5f))) {
                        Text("Sponsored", Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
                card.category?.let {
                    Text(categoryLabel(it), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ad options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Text("Report this ad", Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    REPORT_REASONS.forEach { (label, code) -> DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onReport(code) }) }
                }
            }
        }
        Column(Modifier.padding(end = Spacing.sm)) {
            val media: @Composable () -> Unit = {
                card.imageUrl?.let { url ->
                    Spacer(Modifier.height(Spacing.sm + 2.dp))
                    CampaignMedia(card, url)
                }
            }
            val text: @Composable () -> Unit = {
                Spacer(Modifier.height(Spacing.sm))
                Text(card.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                if (card.body.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(card.body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                card.eventLine?.let {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(it, style = MaterialTheme.typography.labelLarge, color = primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // With a picture the picture leads, like an ad in any feed; a text-only ad leads with its words.
            if (card.imageUrl != null) { media(); CtaBar(ctaLabel, card.advertiser, ctaAction); engagementRow(); text() }
            else { text(); CtaBar(ctaLabel, card.advertiser, ctaAction); engagementRow() }
            if (commentCount > 0) {
                Text(
                    if (commentCount == 1) "View 1 comment" else "View all $commentCount comments",
                    Modifier.clickable { if (live) showComments = true }.padding(vertical = Spacing.xs),
                    style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                )
            }
        }
    }
    if (showComments) {
        SponsoredCommentsSheet(card.campaignId, repo, onDismiss = { showComments = false }, onCountChange = { commentCount = (commentCount + it).coerceAtLeast(0) })
    }
}

/** A full-width button under the picture. It runs the campaign's link, or opens what the card promotes. */
@Composable
private fun CtaBar(label: String?, advertiser: String, action: (() -> Unit)?) {
    if (action == null || label == null) return
    var locked by remember { mutableStateOf(false) }
    // A second tap while the first is still opening the link would open it twice.
    LaunchedEffect(locked) { if (locked) { delay(CTA_LOCK_MS); locked = false } }
    Spacer(Modifier.height(Spacing.sm))
    Surface(
        onClick = { if (!locked) { locked = true; action() } },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label. Sponsored by $advertiser" },
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary,
    ) {
        Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm + 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(VibeIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

/** The card's picture or video, at its own shape. A video is streamed and plays in place. */
@Composable
private fun CampaignMedia(card: SponsoredCard, url: String) {
    val aspect = sponsoredMediaAspect(card.imageWidth, card.imageHeight)
    if (card.imageIsVideo) PostVideo(path = url, authorName = card.advertiser, modifier = Modifier.fillMaxWidth(), knownRatio = aspect)
    else CampaignImage(url, aspect, "${card.advertiser}: ${card.headline}")
}

/** Rounded-square logo; initials on a tinted tile while loading, when there is no logo, or if it fails to load. */
@Composable
private fun AdvertiserLogo(name: String, url: String?) {
    val colors = MaterialTheme.colorScheme
    var failed by remember(url) { mutableStateOf(false) }
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" }, color = colors.primary,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        )
        if (url != null && !failed) {
            AsyncImage(
                model = rememberPhotoModel(url), contentDescription = "$name logo", contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(), onError = { failed = true },
            )
        }
    }
}

/** Campaign picture at its own shape (1:1 to 2:1). The space is held while it loads, and it disappears if it cannot load. */
@Composable
private fun CampaignImage(url: String, aspect: Float, description: String) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        AsyncImage(
            model = rememberPhotoModel(url), contentDescription = description, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(), onError = { failed = true },
        )
    }
}

/** Shares the ad's words and, when it has one, its web link. */
private fun shareSponsored(context: Context, card: SponsoredCard) {
    val text = buildString {
        append(card.advertiser).append(": ").append(card.headline)
        card.ctaUrl?.let { append("\n").append(it) }
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Opens an advertiser's link in the browser. Only https links are ever opened. */
fun openSponsoredLink(context: Context, url: String) {
    if (!url.startsWith("https://")) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
