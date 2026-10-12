package com.univibe.app.features.profile

import com.univibe.app.ui.components.rememberBottomClearance
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.MyProfile
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.accentGradient
import com.univibe.app.ui.theme.Spacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(onOpenSettings: () -> Unit, viewModel: ProfileViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Profile", actions = { VibeIconButton(Icons.Outlined.Settings, "Settings", onOpenSettings) })
        val profile = state.profile
        when {
            state.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 1)
            state.error != null || profile == null -> ErrorState(state.error ?: "Something went wrong.", viewModel::load)
            else -> LazyColumn(
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = rememberBottomClearance()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item { Header(profile) }
                item {
                    VibeCard {
                        Text("About me", style = MaterialTheme.typography.titleMedium)
                        Text(
                            profile.bio,
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
                            TextButton(onClick = viewModel::toggleEditInterests) {
                                Text(if (state.editingInterests) "Done" else "Edit")
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            if (state.editingInterests) {
                                AllInterests.forEach { name ->
                                    InterestChip(name, selected = name in state.interests, onClick = { viewModel.toggleInterest(name) })
                                }
                            } else {
                                state.interests.forEach { InterestChip(it) }
                            }
                        }
                    }
                }
                item {
                    VibeCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Photos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { /* TODO: photos screen */ }) { Text("See all") }
                        }
                        PhotoGrid()
                    }
                }
                item {
                    VibeCard(onClick = { /* TODO: saved posts screen */ }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(Spacing.md))
                            Column(Modifier.weight(1f)) {
                                Text("Saved posts", style = MaterialTheme.typography.titleMedium)
                                Text("${profile.savedPosts} saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    VibeCard {
                        Text("Groups", style = MaterialTheme.typography.titleMedium)
                        profile.groups.forEachIndexed { index, group ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = Spacing.sm), color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.padding(top = if (index == 0) Spacing.sm else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(accentGradient(group.accent))),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Outlined.Groups, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White) }
                                Spacer(Modifier.width(Spacing.md))
                                Column(Modifier.weight(1f)) {
                                    Text(group.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${group.members} members", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                item {
                    VibeCard {
                        Text("Privacy", style = MaterialTheme.typography.titleMedium)
                        SwitchRow(Icons.Outlined.Visibility, "Show me in Discover", "Other students can find your profile.", state.discoverable, viewModel::setDiscoverable)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SwitchRow(Icons.Outlined.Sensors, "Show online status", "Let others see when you're active.", state.showOnline, viewModel::setShowOnline)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SwitchRow(Icons.Outlined.ChatBubbleOutline, "Allow messages", "Students you don't follow can message you.", state.allowMessages, viewModel::setAllowMessages)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(profile: MyProfile) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(accentGradient(profile.student.accent + 1))),
            ) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.sm),
                    shape = CircleShape,
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f),
                    contentColor = androidx.compose.ui.graphics.Color.White,
                ) {
                    IconButton(onClick = { /* TODO: change cover photo */ }) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = "Edit cover photo", modifier = Modifier.size(20.dp))
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).offset(y = 58.dp)) {
                Box {
                    Box {
                        Surface(shape = CircleShape, color = colors.background, modifier = Modifier.size(116.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                StudentAvatar(profile.student.name, size = 104.dp, accent = profile.student.accent, online = profile.student.online)
                            }
                        }
                        if (profile.student.verified) {
                            Surface(
                                shape = CircleShape, color = colors.background,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp).size(30.dp),
                            ) {
                                Icon(Icons.Filled.Verified, contentDescription = "Verified student", tint = colors.primary, modifier = Modifier.padding(3.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(64.dp))
        Text(profile.student.name, style = MaterialTheme.typography.headlineSmall)
        Text(profile.student.handle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(
            "${profile.student.course} · ${profile.student.year}\n${profile.student.university}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Stat(Icons.Outlined.GridView, profile.posts.toString(), "Posts", Modifier.weight(1f))
            Stat(Icons.Outlined.Groups, compact(profile.followers), "Followers", Modifier.weight(1f))
            Stat(Icons.Outlined.PersonAdd, profile.following.toString(), "Following", Modifier.weight(1f))
        }
        VibeButton("Edit profile", onClick = { /* TODO: edit profile screen */ }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Stat(icon: ImageVector, value: String, label: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(vertical = Spacing.sm + 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun compact(n: Int) = if (n >= 1000) String.format(java.util.Locale.US, "%.1fK", n / 1000f) else n.toString()

@Composable
private fun PhotoGrid() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        repeat(2) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                repeat(3) { col ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(accentGradient(row * 3 + col))),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (checked) "On" else "Off",
            style = MaterialTheme.typography.labelMedium,
            color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.sm),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}
