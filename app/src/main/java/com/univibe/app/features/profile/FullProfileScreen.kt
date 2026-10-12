package com.univibe.app.features.profile

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
import com.univibe.app.ui.components.VerifiedBadge
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
) {
    val viewModel = keyedViewModel("full-profile-$studentId") { FullProfileViewModel(studentId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.consumeNotice() } }

    val profile = s.profile
    if (s.loading || s.notFound || profile == null) {
        Column(Modifier.fillMaxSize()) {
            VibeTopBar("Profile", compactTitle = true, navigationIcon = { VibeIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) })
            if (s.loading) LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 1)
            else ErrorState("This profile isn't available.", onBack, title = "Not found")
        }
        return
    }

    val student = profile.student
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProfileCover(
                        accent = student.accent + 1,
                        coverPath = null,
                        modifier = Modifier.fullBleed(Spacing.md),
                        leading = { CoverIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) },
                        actions = { if (!s.isMe) CoverIconButton(Icons.Outlined.MoreHoriz, "More options", { viewModel.openMenu(true) }) },
                        avatar = {
                            Box {
                                Surface(shape = CircleShape, color = colors.background, modifier = Modifier.size(116.dp)) {
                                    Box(contentAlignment = Alignment.Center) { StudentAvatar(student.name, size = 104.dp, accent = student.accent) }
                                }
                                // Only when the server says this student is verified.
                                if (student.verified) {
                                    VerifiedBadge(size = 30.dp, modifier = Modifier.align(Alignment.TopStart).padding(start = 84.dp, top = 84.dp))
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(64.dp))
                    Text(student.name, style = MaterialTheme.typography.headlineSmall)
                    Text(student.handle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Text(
                        "${student.course} · ${student.year}\n${student.university}".trim(),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.xs),
                    )
                    if (student.online) Text("Active now", style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.padding(top = Spacing.xs))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Stat(profile.posts.toString(), "Posts", Modifier.weight(1f))
                    Stat(compactCount(profile.followers), "Followers", Modifier.weight(1f))
                    Stat(compactCount(profile.following), "Following", Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (s.isMe) {
                        VibeButton("Edit profile", onEditProfile, Modifier.weight(1f))
                    } else {
                        VibeButton(
                            if (s.following) "Following" else "Follow", viewModel::toggleFollow, Modifier.weight(1f),
                            style = if (s.following) VibeButtonStyle.Secondary else VibeButtonStyle.Primary,
                        )
                        VibeButton(
                            "Message", { viewModel.message(onOpenChat) }, Modifier.weight(1f),
                            style = VibeButtonStyle.Secondary, loading = s.opening, leadingIcon = Icons.Outlined.ChatBubbleOutline,
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
                                ) { Icon(Icons.Outlined.Groups, contentDescription = null, tint = Color.White) }
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
        }
        if (s.canDecide) {
            Surface(color = colors.background) {
                Column {
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
                    Column(Modifier.navigationBarsPadding().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            VibeButton("Pass", { viewModel.decide(false, onBack) }, Modifier.weight(1f), style = VibeButtonStyle.Secondary, enabled = !s.deciding, leadingIcon = Icons.Outlined.Close)
                            VibeButton("Connect", { viewModel.decide(true, onBack) }, Modifier.weight(1f), loading = s.deciding, leadingIcon = Icons.Filled.Favorite)
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
            MenuRow(Icons.Outlined.Flag, "Report ${student.name}", false, viewModel::report)
            MenuRow(Icons.Outlined.Block, "Block ${student.name}", true, viewModel::block)
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
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
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
