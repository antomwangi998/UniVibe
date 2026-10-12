package com.univibe.app.features.profile

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.MyProfile
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VerifiedBadge
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.accentGradient
import com.univibe.app.ui.components.rememberBottomClearance
import com.univibe.app.ui.theme.Spacing

class ProfileActions(
    val onOpenSettings: () -> Unit = {},
    val onEditProfile: () -> Unit = {},
    val onOpenPhotos: () -> Unit = {},
    val onOpenSaved: () -> Unit = {},
    val onOpenFollowers: () -> Unit = {},
    val onOpenFollowing: () -> Unit = {},
    val onOpenCommunities: () -> Unit = {},
    val onOpenCommunity: (String) -> Unit = {},
    val onOpenPrivacy: () -> Unit = {},
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(actions: ProfileActions, viewModel: ProfileViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Profile", actions = { VibeIconButton(Icons.Outlined.Settings, "Settings", actions.onOpenSettings) })
        val profile = state.profile
        when {
            state.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 1)
            state.error != null || profile == null -> ErrorState(state.error ?: "Something went wrong.", viewModel::load)
            else -> LazyColumn(
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = rememberBottomClearance()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item { Header(profile, actions) }
                item {
                    VibeCard {
                        Text("About me", style = MaterialTheme.typography.titleMedium)
                        Text(
                            profile.bio.ifBlank { "Add a short bio so people know you." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xs),
                        )
                    }
                }
                item {
                    VibeCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Interests", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = actions.onEditProfile) { Text("Edit") }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) { profile.student.interests.forEach { InterestChip(it) } }
                    }
                }
                item {
                    VibeCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Photos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = actions.onOpenPhotos) { Text(if (profile.photos.isEmpty()) "Add" else "See all") }
                        }
                        PhotoPreview(profile.photos, actions.onOpenPhotos)
                    }
                }
                item {
                    VibeCard(onClick = actions.onOpenSaved) {
                        NavLine(Icons.Outlined.BookmarkBorder, "Saved posts", "${profile.savedPosts} saved")
                    }
                }
                item {
                    VibeCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Groups", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = actions.onOpenCommunities) { Text("Discover") }
                        }
                        if (state.groups.isEmpty()) {
                            Text("You haven't joined any communities yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.groups.forEachIndexed { index, group ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = Spacing.xs), color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Button) { actions.onOpenCommunity(group.id) }.padding(vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(accentGradient(group.accent))),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Outlined.Groups, contentDescription = null, tint = Color.White) }
                                Spacer(Modifier.width(Spacing.md))
                                Column(Modifier.weight(1f)) {
                                    Text(group.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${group.members} members", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    VibeCard(onClick = actions.onOpenPrivacy) {
                        NavLine(Icons.Outlined.Shield, "Privacy & safety", "Who can find and message you")
                    }
                }
            }
        }
    }
}

@Composable
private fun NavLine(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PhotoPreview(photos: List<String>, onOpen: () -> Unit) {
    if (photos.isEmpty()) {
        Surface(
            onClick = onOpen, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Add your first photo", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.md))
            }
        }
        return
    }
    val shown = photos.take(6)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        shown.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { path ->
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpen)) {
                        PhotoImage(path, contentDescription = "Photo")
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Header(profile: MyProfile, actions: ProfileActions) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(accentGradient(profile.student.accent + 1))),
            ) {
                profile.coverPath?.let { PhotoImage(it, contentDescription = "Cover photo") }
            }
            Box(Modifier.align(Alignment.BottomCenter).offset(y = 58.dp)) {
                Box {
                    Surface(shape = CircleShape, color = colors.background, modifier = Modifier.size(116.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            // No online dot on your own profile: it would collide with the verified badge.
                            StudentAvatar(
                                profile.student.name, size = 104.dp, accent = profile.student.accent,
                                online = false, imagePath = profile.avatarPath,
                            )
                        }
                    }
                    if (profile.student.verified) {
                        // Centre sits on the avatar ring at 45 degrees (58dp radius * 0.707 = 41dp from centre).
                        VerifiedBadge(
                            size = 30.dp,
                            modifier = Modifier.align(Alignment.TopStart).offset(x = 84.dp, y = 84.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(64.dp))
        Text(profile.student.name, style = MaterialTheme.typography.headlineSmall)
        Text(profile.student.handle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(
            "${profile.student.course} · ${profile.student.year}\n${profile.student.university}",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.xs),
        )
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Stat(Icons.Outlined.GridView, profile.posts.toString(), "Posts", Modifier.weight(1f), null)
            Stat(Icons.Outlined.Groups, compact(profile.followers), "Followers", Modifier.weight(1f), actions.onOpenFollowers)
            Stat(Icons.Outlined.PersonAdd, profile.following.toString(), "Following", Modifier.weight(1f), actions.onOpenFollowing)
        }
        VibeButton("Edit profile", onClick = actions.onEditProfile, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Stat(icon: ImageVector, value: String, label: String, modifier: Modifier, onClick: (() -> Unit)?) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(vertical = Spacing.sm + 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, content = content)
    } else {
        Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, content = content)
    }
}

private fun compact(n: Int) = if (n >= 1000) String.format(java.util.Locale.US, "%.1fK", n / 1000f) else n.toString()
