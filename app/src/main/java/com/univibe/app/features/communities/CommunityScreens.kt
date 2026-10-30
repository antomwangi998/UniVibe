package com.univibe.app.features.communities

import com.univibe.app.ui.components.VerifiedName
import com.univibe.app.ui.components.VibePullToRefresh
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.univibe.app.ui.components.VibeIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonRemove
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.CommunityMember
import com.univibe.app.data.model.CommunityPrivacy
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.model.Post
import com.univibe.app.features.chats.SheetRow
import com.univibe.app.features.settings.SettingsGroup
import com.univibe.app.features.settings.SettingsSwitchRow
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.CommunityMembershipButton
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.accentGradient
import com.univibe.app.ui.theme.Spacing


@Composable
fun CommunitiesScreen(onBack: () -> Unit, onOpenCommunity: (String) -> Unit, onCreate: () -> Unit = {}, viewModel: CommunitiesViewModel = viewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }

    Column(Modifier.fillMaxSize()) {
        VibeTopBar(
            "Communities", compactTitle = true,
            navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) },
            actions = { VibeIconButton(VibeIcons.Add, "New community", onCreate) },
        )
        VibeTextField(
            s.query, viewModel::setQuery, placeholder = "Search communities", leadingIcon = VibeIcons.Search,
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
            s.visible.isEmpty() -> EmptyState(
                VibeIcons.Groups, "No communities found", "Try a different search, or start your own.",
                actionLabel = "Create a community", onAction = onCreate,
            )
            else -> VibePullToRefresh(refreshing = s.refreshing, onRefresh = viewModel::pullRefresh) { LazyColumn(
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (c.privacy == CommunityPrivacy.Private) {
                                        Icon(VibeIcons.Lock, contentDescription = "Private", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text("${c.members} members · ${c.category}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            // The same shared control as on Home and Discover posts.
                            CommunityMembershipButton(
                                c.id, c.effectiveMembership, c.members, compact = true,
                                onLeaveRequested = { viewModel.onMembershipClick(c) },
                                onChanged = { viewModel.refresh() },
                            )
                        }
                        Text(c.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm), maxLines = 2)
                    }
                }
            } }
        }
    }
    s.pendingLeave?.let { c ->
        ConfirmDialog(
            title = "Leave ${c.name}?",
            message = if (c.privacy == CommunityPrivacy.Private) "It's private, so you'll need approval to join again." else "You can join again any time.",
            confirmLabel = "Leave", destructive = true,
            onConfirm = viewModel::confirmLeave, onDismiss = viewModel::dismissLeave,
        )
    }
}

@Composable
fun CommunityBadge(c: Community, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(accentGradient(c.accent))),
        contentAlignment = Alignment.Center,
    ) { Icon(VibeIcons.Groups, contentDescription = null, tint = Color.White) }
}

@Composable
fun CommunityDetailScreen(
    communityId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit = {},
    onPost: () -> Unit = {},
    onOpenComments: (String) -> Unit = {},
    onOpenStudent: (String) -> Unit = {},
    onOpenEvent: (String) -> Unit = {},
    onOpenChannel: (String) -> Unit = {},
    onManageChannels: () -> Unit = {},
) {
    val viewModel = keyedViewModel("community-$communityId") { CommunityDetailViewModel(communityId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    LaunchedEffect(s.deleted) { if (s.deleted) onBack() }
    val c = s.community

    Column(Modifier.fillMaxSize()) {
        VibeTopBar(
            c?.name ?: "Community", compactTitle = true,
            navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) },
            actions = { if (c?.role == CommunityRole.Owner) VibeIconButton(VibeIcons.Edit, "Edit community", onEdit) },
        )
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 1)
            s.notFound || c == null -> ErrorState("This community isn't available.", onBack, title = "Not found")
            else -> VibePullToRefresh(refreshing = s.refreshing, onRefresh = viewModel::pullRefresh) { LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item {
                    Box(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(accentGradient(c.accent)))) {
                        Row(Modifier.align(Alignment.BottomStart).padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Pill(c.category)
                            if (c.privacy == CommunityPrivacy.Private) Pill("Private", VibeIcons.Lock)
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            "${c.members} members" + when (c.role) { CommunityRole.Owner -> " · You own this"; CommunityRole.Moderator -> " · You moderate"; else -> "" },
                            style = MaterialTheme.typography.titleSmall, color = colors.primary,
                        )
                        Text(c.description, style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            CommunityMembershipButton(
                                c.id, c.effectiveMembership, c.members, compact = false, modifier = Modifier.weight(1f),
                                onLeaveRequested = viewModel::onMembershipClick,
                                onChanged = { viewModel.load() },
                            )
                            if (c.isMember && !c.archived && (c.membership == null || c.canPost)) VibeButton("Post", onPost, Modifier.weight(1f), leadingIcon = VibeIcons.Edit)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        CommunityTab.entries.forEach { t ->
                            val label = if (t == CommunityTab.Members && c.isStaff && s.requests.isNotEmpty()) "Members (${s.requests.size} new)" else t.name
                            InterestChip(label, selected = s.tab == t, onClick = { viewModel.setTab(t) })
                        }
                    }
                }
                when (s.tab) {
                    CommunityTab.Posts -> postsTab(c, s, onPost, onOpenComments, onOpenStudent, onOpenEvent)
                    CommunityTab.Discussions -> item { ChannelDirectoryContent(c, onOpenChannel, onManageChannels) }
                    CommunityTab.Events -> eventsTab(c, s, onOpenEvent)
                    CommunityTab.Members -> membersTab(c, s, viewModel, onOpenStudent)
                    CommunityTab.About -> aboutTab(c, s, viewModel, onEdit)
                }
            } }
        }
    }

    s.memberMenu?.let { m ->
        VibeBottomSheet(onDismiss = { viewModel.memberMenu(null) }) {
            Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                StudentAvatar(m.student.name, size = 44.dp, accent = m.student.accent, imagePath = m.student.avatarUrl, look = m.student.look)
                Column(Modifier.padding(start = Spacing.md)) {
                    VerifiedName(m.student.name, m.student.verified, style = MaterialTheme.typography.titleMedium)
                    Text(m.role.name, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            SheetRow(VibeIcons.Profile, "View profile", false) { viewModel.memberMenu(null); onOpenStudent(m.student.id) }
            val owner = c?.role == CommunityRole.Owner
            if (owner && m.role == CommunityRole.Member) SheetRow(VibeIcons.Shield, "Make moderator", false) { viewModel.makeModerator(m) }
            if (owner && m.role == CommunityRole.Moderator) SheetRow(VibeIcons.Shield, "Remove as moderator", false) { viewModel.removeModerator(m) }
            if (c?.isStaff == true && m.role != CommunityRole.Owner && (m.role != CommunityRole.Moderator || owner)) {
                SheetRow(VibeIcons.PersonRemove, "Remove from community", true) { viewModel.confirm(CommunityConfirm.RemoveMember(m)) }
            }
            Spacer(Modifier.size(Spacing.md))
        }
    }

    when (val conf = s.confirm) {
        CommunityConfirm.Leave -> ConfirmDialog(
            title = "Leave ${c?.name ?: "community"}?",
            message = if (c?.privacy == CommunityPrivacy.Private) "It's private, so you'll need approval to join again." else "You can join again any time.",
            confirmLabel = "Leave", destructive = true, onConfirm = viewModel::doConfirmed, onDismiss = { viewModel.confirm(null) },
        )
        CommunityConfirm.Delete -> ConfirmDialog(
            title = "Delete ${c?.name ?: "community"}?",
            message = "This removes the community, its posts and the events it hosts for everyone. This can't be undone.",
            confirmLabel = "Delete", destructive = true, onConfirm = viewModel::doConfirmed, onDismiss = { viewModel.confirm(null) },
        )
        is CommunityConfirm.RemoveMember -> ConfirmDialog(
            title = "Remove ${conf.member.student.name}?", message = "They'll lose access to the community's posts.",
            confirmLabel = "Remove", destructive = true, onConfirm = viewModel::doConfirmed, onDismiss = { viewModel.confirm(null) },
        )
        null -> Unit
    }
}

@Composable
private fun Pill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.postsTab(
    c: Community, s: CommunityDetailUiState, onPost: () -> Unit, onOpenComments: (String) -> Unit, onOpenStudent: (String) -> Unit, onOpenEvent: (String) -> Unit,
) {
    when {
        s.postsLoading -> item { LoadingSkeleton(itemCount = 1) }
        c.privacy == CommunityPrivacy.Private && !c.isMember -> item {
            EmptyState(VibeIcons.Lock, "This community is private", "Request to join to see posts and members.")
        }
        s.posts.isEmpty() -> item {
            EmptyState(
                VibeIcons.Comment, "No posts yet", if (c.isMember) "Start the conversation." else "Join to post here.",
                actionLabel = if (c.isMember) "Write a post" else null, onAction = if (c.isMember) onPost else null,
            )
        }
        // The organizer's own announcements live in the Events tab; events a member shared stay here as posts.
        else -> items(s.posts.filter { it.event == null || it.sharedEvent }, key = { it.id }) { p -> CommunityPostRow(p, onOpenComments, onOpenStudent, onOpenEvent) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.eventsTab(c: Community, s: CommunityDetailUiState, onOpenEvent: (String) -> Unit) {
    when {
        s.postsLoading -> item { LoadingSkeleton(itemCount = 1) }
        c.privacy == CommunityPrivacy.Private && !c.isMember -> item {
            EmptyState(VibeIcons.Lock, "This community is private", "Request to join to see its events.")
        }
        s.events.isEmpty() -> item { EmptyState(VibeIcons.Events, "No upcoming events", "Events this community hosts will show up here.") }
        else -> items(s.events, key = { it.id }) { e ->
            VibeCard(onClick = { onOpenEvent(e.id) }) {
                com.univibe.app.features.events.CoverStrip(e)
                Text(e.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.xs))
                Text(listOf(e.timeLabel, e.location).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${e.going} going", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CommunityPostRow(p: Post, onOpenComments: (String) -> Unit, onOpenStudent: (String) -> Unit, onOpenEvent: (String) -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    VibeCard(onClick = { onOpenComments(p.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StudentAvatar(p.author.name, size = 36.dp, accent = p.author.accent, imagePath = p.author.avatarUrl, look = p.author.look, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open profile") { onOpenStudent(p.author.id) })
            Column(Modifier.padding(start = Spacing.sm + 4.dp)) {
                VerifiedName(p.author.name, p.author.verified, style = MaterialTheme.typography.titleSmall)
                Text(p.timeAgo, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (p.text.isNotBlank()) Text(p.text, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = Spacing.sm))
        p.event?.takeIf { p.sharedEvent }?.let { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs).clickable(role = Role.Button, onClickLabel = "Open event") { onOpenEvent(e.id) }) {
                com.univibe.app.features.events.CoverStrip(e)
                Text(e.title, style = MaterialTheme.typography.titleSmall)
                Text(listOf(e.timeLabel, e.location).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(VibeIcons.Heart, contentDescription = "Likes", tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(" ${p.likeCount}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.md))
            Icon(VibeIcons.Comment, contentDescription = "Comments", tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(" ${p.commentCount}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.membersTab(
    c: Community, s: CommunityDetailUiState, vm: CommunityDetailViewModel, onOpenStudent: (String) -> Unit,
) {
    if (c.isStaff && s.requests.isNotEmpty()) item {
        VibeCard {
            Text("Join requests (${s.requests.size})", style = MaterialTheme.typography.titleMedium)
            s.requests.forEach { st ->
                Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    StudentAvatar(st.name, size = 40.dp, accent = st.accent, imagePath = st.avatarUrl, look = st.look)
                    Column(Modifier.weight(1f).padding(horizontal = Spacing.sm + 4.dp)) {
                        Text(st.name, style = MaterialTheme.typography.titleSmall)
                        Text("${st.courseLine}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    VibeButton("Decline", { vm.answerRequest(st, false) }, style = VibeButtonStyle.Secondary, compact = true)
                    Spacer(Modifier.width(Spacing.xs))
                    VibeButton("Approve", { vm.answerRequest(st, true) }, compact = true)
                }
            }
        }
    }
    when {
        s.membersError != null -> item { EmptyState(VibeIcons.Lock, "Members are hidden", s.membersError) }
        else -> items(s.members, key = { it.student.id }) { m ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClickLabel = if (c.isStaff && !m.isMe) "Member options" else "Open profile") {
                        if (c.isStaff && !m.isMe) vm.memberMenu(m) else onOpenStudent(m.student.id)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StudentAvatar(m.student.name, size = 44.dp, accent = m.student.accent, online = m.student.online, imagePath = m.student.avatarUrl, look = m.student.look)
                Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                    VerifiedName(if (m.isMe) "${m.student.name} (you)" else m.student.name, m.student.verified, style = MaterialTheme.typography.titleSmall)
                    Text("${m.student.courseLine}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (m.role == CommunityRole.Owner || m.role == CommunityRole.Moderator) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                        Text(m.role.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.aboutTab(
    c: Community, s: CommunityDetailUiState, vm: CommunityDetailViewModel, onEdit: () -> Unit,
) {
    if (c.isMember) item {
        SettingsGroup {
            SettingsSwitchRow("Mute notifications", c.muted, vm::setMuted, subtitle = "Stop alerts from this community.", icon = VibeIcons.Bell)
        }
    }
    item {
        VibeCard {
            Text("Details", style = MaterialTheme.typography.titleMedium)
            Text(
                "${if (c.privacy == CommunityPrivacy.Private) "Private: moderators approve every request" else "Public: anyone at your university can join"}\n" +
                    "Category: ${c.category}" + if (c.createdLabel.isNotBlank()) "\n${c.createdLabel}" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
    item {
        VibeCard {
            Text("Rules", style = MaterialTheme.typography.titleMedium)
            if (c.rules.isEmpty()) Text("No rules yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
            c.rules.forEachIndexed { i, rule -> Text("${i + 1}. $rule", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs)) }
        }
    }
    item {
        VibeCard {
            Text("Moderators", style = MaterialTheme.typography.titleMedium)
            Text(c.moderators.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
        }
    }
    if (c.isStaff) item {
        VibeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(VibeIcons.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                        VibeButton("Dismiss", { vm.resolve(r.id, remove = false) }, style = VibeButtonStyle.Secondary, compact = true)
                        VibeButton("Remove post", { vm.resolve(r.id, remove = true) }, compact = true)
                    }
                }
            }
            Text("Mock moderation queue. Real moderation needs server-side roles and an audit log.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm))
        }
    }
    item {
        Column {
            TextButton(onClick = vm::reportCommunity) {
                Icon(VibeIcons.Report, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Report this community")
            }
            if (c.role == CommunityRole.Owner) {
                TextButton(onClick = onEdit) {
                    Icon(VibeIcons.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit community")
                }
                TextButton(onClick = { vm.confirm(CommunityConfirm.Delete) }) {
                    Icon(VibeIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete community", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
