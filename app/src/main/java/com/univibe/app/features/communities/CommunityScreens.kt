package com.univibe.app.features.communities

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.features.settings.SettingsGroup
import com.univibe.app.features.settings.SettingsPage
import com.univibe.app.features.settings.SettingsSwitchRow
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.accentGradient
import com.univibe.app.ui.theme.Spacing

@Composable
fun CommunitiesScreen(onBack: () -> Unit, onOpenCommunity: (String) -> Unit, viewModel: CommunitiesViewModel = viewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Communities", compactTitle = true, navigationIcon = { VibeIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) })
        VibeTextField(
            s.query, viewModel::setQuery, placeholder = "Search communities", leadingIcon = Icons.Outlined.Search,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(s.categories, key = { it }) { c -> InterestChip(c, selected = s.category == c, onClick = { viewModel.setCategory(c) }) }
        }
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
            s.error != null -> ErrorState(s.error!!, viewModel::load)
            s.visible.isEmpty() -> EmptyState(Icons.Outlined.Groups, "No communities found", "Try a different search or category.")
            else -> LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(s.visible, key = { it.id }) { c ->
                    VibeCard(onClick = { onOpenCommunity(c.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CommunityBadge(c, 56.dp)
                            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium)
                                Text("${c.members} members · ${c.category}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            VibeButton(
                                if (c.role == CommunityRole.None) "Join" else "Joined", { viewModel.onMembershipClick(c) },
                                style = if (c.role == CommunityRole.None) VibeButtonStyle.Primary else VibeButtonStyle.Secondary, compact = true,
                            )
                        }
                        Text(c.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm), maxLines = 2)
                    }
                }
            }
        }
    }
    s.pendingLeave?.let { c ->
        ConfirmDialog(
            title = "Leave ${c.name}?", message = "You can join again any time.", confirmLabel = "Leave", destructive = true,
            onConfirm = viewModel::confirmLeave, onDismiss = viewModel::dismissLeave,
        )
    }
}

@Composable
private fun CommunityBadge(c: Community, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(accentGradient(c.accent))),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Groups, contentDescription = null, tint = Color.White) }
}

@Composable
fun CommunityDetailScreen(communityId: String, onBack: () -> Unit) {
    val viewModel = keyedViewModel("community-$communityId") { CommunityDetailViewModel(communityId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    val c = s.community

    SettingsPage(c?.name ?: "Community", onBack) {
        when {
            s.loading -> item { LoadingSkeleton(itemCount = 1) }
            s.notFound || c == null -> item { ErrorState("This community isn't available.", onBack, title = "Not found") }
            else -> {
                item {
                    Box(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(accentGradient(c.accent)))) {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.md), shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White,
                        ) { Text(c.category, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("${c.members} members" + if (c.role == CommunityRole.Moderator) " · You moderate this community" else "", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(c.description, style = MaterialTheme.typography.bodyLarge)
                        VibeButton(
                            if (c.role == CommunityRole.None) "Join community" else "Joined", viewModel::onMembershipClick, Modifier.fillMaxWidth(),
                            style = if (c.role == CommunityRole.None) VibeButtonStyle.Primary else VibeButtonStyle.Secondary,
                            leadingIcon = if (c.role == CommunityRole.None) null else Icons.Outlined.Check,
                        )
                    }
                }
                if (c.role != CommunityRole.None) {
                    item {
                        SettingsGroup {
                            SettingsSwitchRow("Mute notifications", c.muted, viewModel::setMuted, subtitle = "Stop alerts from this community.", icon = Icons.Outlined.Notifications)
                        }
                    }
                }
                item {
                    VibeCard {
                        Text("Rules", style = MaterialTheme.typography.titleMedium)
                        c.rules.forEachIndexed { i, rule -> Text("${i + 1}. $rule", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs)) }
                    }
                }
                item {
                    VibeCard {
                        Text("Moderators", style = MaterialTheme.typography.titleMedium)
                        Text(c.moderators.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
                    }
                }
                if (c.role == CommunityRole.Moderator) {
                    item {
                        VibeCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Moderation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = Spacing.sm))
                            }
                            if (s.reports.isEmpty()) {
                                Text("No open reports. Nice and quiet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm))
                            }
                            s.reports.forEachIndexed { i, r ->
                                if (i > 0) HorizontalDivider(Modifier.padding(vertical = Spacing.sm), color = MaterialTheme.colorScheme.outlineVariant)
                                Column(Modifier.padding(top = Spacing.sm)) {
                                    Text("${r.reason} · by ${r.authorName}", style = MaterialTheme.typography.labelLarge)
                                    Text("\"${r.excerpt}\"", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.xs))
                                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                        VibeButton("Dismiss", { viewModel.resolve(r.id, remove = false) }, style = VibeButtonStyle.Secondary, compact = true)
                                        VibeButton("Remove post", { viewModel.resolve(r.id, remove = true) }, compact = true)
                                    }
                                }
                            }
                            Text("Mock moderation queue. Real moderation needs server-side roles and an audit log.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm))
                        }
                    }
                }
                item {
                    TextButton(onClick = viewModel::reportCommunity) {
                        Icon(Icons.Outlined.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Report this community")
                    }
                }
            }
        }
    }

    if (s.pendingLeave) {
        ConfirmDialog(
            title = "Leave ${c?.name ?: "community"}?", message = "You can join again any time.", confirmLabel = "Leave", destructive = true,
            onConfirm = viewModel::confirmLeave, onDismiss = viewModel::dismissLeave,
        )
    }
}
