package com.univibe.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.data.repository.SponsoredComment
import com.univibe.app.data.repository.SponsoredRepository
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val MAX_COMMENT = 500

/**
 * Comments on a sponsored card, styled like the post comments screen: flat rows with an avatar, name and handle,
 * hairline dividers, and a pill-shaped reply bar. [onCountChange] gets +1 / -1 so the card's count stays right.
 */
@Composable
fun SponsoredCommentsSheet(
    campaignId: String,
    repository: SponsoredRepository,
    onDismiss: () -> Unit,
    onCountChange: (Int) -> Unit,
) {
    var items by remember { mutableStateOf<List<SponsoredComment>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val hairline = colors.outlineVariant.copy(alpha = 0.6f)

    LaunchedEffect(campaignId, reload) {
        loading = true; loadFailed = false
        try {
            items = repository.comments(campaignId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadFailed = true
        }
        loading = false
    }

    val canSend = text.isNotBlank() && !sending
    fun send() {
        val body = text.trim()
        if (body.isEmpty() || sending) return
        sending = true; error = null
        scope.launch {
            runCatching { repository.addComment(campaignId, body) }
                .onSuccess { items = items + it; text = ""; onCountChange(1) }
                .onFailure { if (it is CancellationException) throw it; error = "Couldn't send. Try again." }
            sending = false
        }
    }

    VibeBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Text(
                "Comments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )
            HorizontalDivider(color = hairline, thickness = 0.5.dp)
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp)) {
                when {
                    loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
                    loadFailed -> ErrorState("We couldn't load the comments. Check your connection and try again.", { reload++ })
                    items.isEmpty() -> EmptyState(VibeIcons.Comment, "No comments yet", "Be the first to say something.")
                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(items, key = { it.id }) { c ->
                            Row(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs, top = 12.dp), verticalAlignment = Alignment.Top) {
                                StudentAvatar(c.authorName, size = 36.dp)
                                Column(Modifier.weight(1f).padding(start = Spacing.sm + 4.dp, bottom = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            c.authorName, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                        )
                                        Text(
                                            "  ${c.authorHandle}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                        )
                                    }
                                    Text(c.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                                }
                                if (c.mine) {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                runCatching { repository.deleteComment(campaignId, c.id) }
                                                    .onSuccess { items = items.filterNot { it.id == c.id }; onCountChange(-1) }
                                                    .onFailure { if (it is CancellationException) throw it; error = "Couldn't delete. Try again." }
                                            }
                                        },
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Icon(VibeIcons.Delete, contentDescription = "Delete your comment", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            HorizontalDivider(color = hairline, thickness = 0.5.dp)
                        }
                    }
                }
            }
            error?.let {
                Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs))
            }
            if (text.length > MAX_COMMENT - 40) {
                Text(
                    "${text.length}/$MAX_COMMENT", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End).padding(end = Spacing.md, top = Spacing.xs),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.Bottom) {
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
                        .background(colors.surfaceContainerHighest).padding(horizontal = Spacing.md, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = text, onValueChange = { text = it.take(MAX_COMMENT) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary), maxLines = 5,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box {
                                if (text.isEmpty()) Text("Add a comment", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                                inner()
                            }
                        },
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Box(
                    Modifier.heightIn(min = 44.dp).clip(CircleShape)
                        .background(if (canSend) colors.primary else colors.surfaceContainerHighest)
                        .clickable(enabled = canSend, role = Role.Button, onClickLabel = "Post comment", onClick = ::send)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Post", style = MaterialTheme.typography.labelLarge,
                        color = if (canSend) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}
