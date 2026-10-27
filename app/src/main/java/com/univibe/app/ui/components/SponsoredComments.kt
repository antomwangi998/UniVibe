package com.univibe.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.univibe.app.data.repository.SponsoredComment
import com.univibe.app.data.repository.SponsoredRepository
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.launch

private const val MAX_COMMENT = 500

/** Comments on a sponsored card: read them, add one, delete your own. [onCountChange] gets +1 / -1 so the card's count stays right. */
@Composable
fun SponsoredCommentsSheet(
    campaignId: String,
    repository: SponsoredRepository,
    onDismiss: () -> Unit,
    onCountChange: (Int) -> Unit,
) {
    var items by remember { mutableStateOf<List<SponsoredComment>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(campaignId) {
        loading = true
        items = runCatching { repository.comments(campaignId) }.getOrElse { error = "Couldn't load comments."; emptyList() }
        loading = false
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty() || sending) return
        sending = true; error = null
        scope.launch {
            runCatching { repository.addComment(campaignId, body) }
                .onSuccess { items = items + it; text = ""; onCountChange(1) }
                .onFailure { error = "Couldn't send. Try again." }
            sending = false
        }
    }

    VibeBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md).imePadding(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("Comments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                when {
                    loading -> Text("Loading…", color = colors.onSurfaceVariant)
                    items.isEmpty() -> Text("No comments yet. Be the first.", color = colors.onSurfaceVariant)
                    else -> items.forEach { c ->
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(c.authorName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                Text(c.body, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (c.mine) TextButton(onClick = {
                                scope.launch {
                                    runCatching { repository.deleteComment(campaignId, c.id) }
                                        .onSuccess { items = items.filterNot { it.id == c.id }; onCountChange(-1) }
                                        .onFailure { error = "Couldn't delete. Try again." }
                                }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
            error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth().padding(bottom = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(MAX_COMMENT) }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Add a comment") }, maxLines = 4,
                )
                IconButton(onClick = ::send, enabled = text.isNotBlank() && !sending) {
                    Icon(VibeIcons.Send, contentDescription = "Send comment", tint = if (text.isNotBlank()) colors.primary else colors.onSurfaceVariant)
                }
            }
        }
    }
}
