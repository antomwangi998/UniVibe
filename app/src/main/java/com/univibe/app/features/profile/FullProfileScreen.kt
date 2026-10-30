package com.univibe.app.features.profile

import com.univibe.app.ui.components.SecureProfilePhotoViewer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.univibe.app.ui.components.VibePullToRefresh
import com.univibe.app.ui.components.FollowButton
import com.univibe.app.ui.components.VibeIcons
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.StudentProfile
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VerifiedName
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.accentGradient
import com.univibe.app.ui.theme.Spacing

/**
 * Another student's full profile. Links out to chat, their communities and their posts; every one of
 * those targets is also a registered deep link (see docs/DEEP_LINKS.md).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FullProfileScreen(
    studentId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onEditProfile: () -> Unit,
    onOpenCommunity: (String) -> Unit,
    onOpenComments: (String) -> Unit,
    onOpenFollowers: (String) -> Unit = {},
    onOpenFollowing: (String) -> Unit = {},
) {
    var showPhoto by remember { mutableStateOf(false) }
    val viewModel = keyedViewModel("full-profile-$studentId") { FullProfileViewModel(studentId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.consumeNotice() } }

    val profile = s.profile
    if (s.loading || s.notFound || profile == null) {
        Column(Modifier.fillMaxSize()) {
            VibeTopBar("Profile", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
            if (s.loading) LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 1)
            else ErrorState("This profile isn't available.", onBack, title = "Not found")
        }
        return
    }

    val student = profile.student
    if (showPhoto) SecureProfilePhotoViewer(student.name, student.avatarUrl, student.accent) { showPhoto = false }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        VibePullToRefresh(refreshing = s.refreshing, onRefresh = viewModel::pullRefresh, modifier = Modifier.weight(1f)) { LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProfileCover(
                        accent = student.accent + 1,
                        coverPath = null,
                        look = profile.look,
                        modifier = Modifier.fullBleed(Spacing.md),
                        leading = { CoverIconButton(VibeIcons.Back, "Back", onBack) },
                        actions = { if (!s.isMe) CoverIconButton(VibeIcons.More, "More options", { viewModel.openMenu(true) }) },
                        avatar = {
                            Box(Modifier.profileFrame(profile.look?.frame, lookAccent(profile.look, colors.primary))) {
                                Surface(shape = CircleShape, color = colors.background, modifier = Modifier.size(116.dp)) {
                                    Box(
                                        Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "View profile photo") { showPhoto = true },
                                        contentAlignment = Alignment.Center,
                                    ) { StudentAvatar(student.name, size = 104.dp, accent = student.accent, imagePath = student.avatarUrl, look = student.look) }
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(64.dp))
                    // The seal sits beside the name (only when the server says verified) and takes the look's accent.
                    VerifiedName(student.name, student.verified, style = MaterialTheme.typography.headlineSmall, badgeSize = 22.dp, look = profile.look)
                    Text(student.handle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    ProfileBadgeChip(profile.look?.badge, lookAccent(profile.look, colors.primary), Modifier.padding(top = Spacing.xs))
                    Text(
                        "${student.courseLine}\n${student.university}".trim(),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.xs),
                    )
                    if (student.online) Text("Active now", style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.padding(top = Spacing.xs))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Stat(profile.posts.toString(), "Posts", Modifier.weight(1f))
                    Stat(compactCount(profile.followers), "Followers", Modifier.weight(1f)) { onOpenFollowers(student.id) }
                    Stat(compactCount(profile.following), "Following", Modifier.weight(1f)) { onOpenFollowing(student.id) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (s.isMe) {
                        VibeButton("Edit profile", onEditProfile, Modifier.weight(1f))
                    } else {
                        FollowButton(s.following, viewModel::toggleFollow, Modifier.weight(1f))
                        VibeButton(
                            "Message", { viewModel.message(onOpenChat) }, Modifier.weight(1f),
                            style = VibeButtonStyle.Secondary, loading = s.opening, leadingIcon = VibeIcons.Comment,
                        )
                    }
                }
            }
            if (profile.bio.isNotBlank()) {
                item {
                    VibeCard {
                        Text("About", style = MaterialTheme.typography.titleMedium)
                        Text(profile.bio, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
                    }
                }
            }
            if (!s.isMe && (student.shared.isNotEmpty() || student.sameCourse)) {
                item {
                    VibeCard {
                        Text("In common with you", style = MaterialTheme.typography.titleMedium)
                        FlowRow(
                            Modifier.padding(top = Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            if (student.sameCourse) InterestChip("Same course", selected = true)
                            student.shared.forEach { InterestChip(it, selected = true) }
                        }
                    }
                }
            }
            if (student.interests.isNotEmpty()) {
                item {
                    VibeCard {
                        Text("Interests", style = MaterialTheme.typography.titleMedium)
                        FlowRow(
                            Modifier.padding(top = Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) { student.interests.forEach { InterestChip(it, selected = it in student.shared) } }
                    }
                }
            }
            if (s.communities.isNotEmpty()) {
                item {
                    VibeCard {
                        Text("Communities", style = MaterialTheme.typography.titleMedium)
                        s.communities.forEachIndexed { index, c ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = Spacing.xs), color = colors.outlineVariant)
                            LinkRow(onClick = { onOpenCommunity(c.id) }, onClickLabel = "Open ${c.name}") {
                                Box(
                                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(accentGradient(c.accent))),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(VibeIcons.Groups, contentDescription = null, tint = Color.White) }
                                Spacer(Modifier.width(Spacing.md))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${c.members} members", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            if (s.posts.isNotEmpty()) {
                item {
                    VibeCard {
                        Text("Recent posts", style = MaterialTheme.typography.titleMedium)
                        s.posts.forEachIndexed { index, post ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = Spacing.xs), color = colors.outlineVariant)
                            LinkRow(onClick = { onOpenComments(post.id) }, onClickLabel = "Open comments") {
                                Column(Modifier.weight(1f)) {
                                    Text(post.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${post.timeAgo} · ${post.likeCount} likes · ${post.commentCount} comments",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } }
        if (s.canDecide) {
            Surface(color = colors.background) {
                Column {
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
                    Column(Modifier.navigationBarsPadding().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            VibeButton("Pass", { viewModel.decide(false, onBack) }, Modifier.weight(1f), style = VibeButtonStyle.Secondary, enabled = !s.deciding, leadingIcon = VibeIcons.Close)
                            VibeButton("Connect", { viewModel.decide(true, onBack) }, Modifier.weight(1f), loading = s.deciding, leadingIcon = VibeIcons.HeartFilled)
                        }
                        Text(
                            "A connection only happens when you both say yes.",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (s.menuOpen) {
        VibeBottomSheet(onDismiss = { viewModel.openMenu(false) }) {
            MenuRow(VibeIcons.Report, "Report ${student.name}", false, viewModel::report)
            MenuRow(VibeIcons.Block, "Block ${student.name}", true, viewModel::block)
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val tap = if (onClick != null) Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClickLabel = "See $label", onClick = onClick) else Modifier
    Surface(modifier = modifier.then(tap), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(vertical = Spacing.sm + 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A tappable row with a trailing chevron: the profile's way of linking to another screen. */
@Composable
private fun LinkRow(onClick: () -> Unit, onClickLabel: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick).padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        Icon(VibeIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, destructive: Boolean, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().height(56.dp).clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(Spacing.md))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

private fun compactCount(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1_000 -> "%.1fK".format(n / 1_000f).replace(".0K", "K")
    else -> n.toString()
}
