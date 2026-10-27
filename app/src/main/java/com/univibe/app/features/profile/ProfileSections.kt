package com.univibe.app.features.profile

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.model.MyProfile
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.Student
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VerifiedBadge
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.accentGradient

// ---------------------------------------------------------------------------------------------------------------------
// Header: avatar, identity, statistics, actions
// ---------------------------------------------------------------------------------------------------------------------

/** Circular avatar: turquoise ring, thin dark inner border, the real profile image, camera button at the lower right. */
@Composable
fun ProfileAvatar(profile: MyProfile, outer: androidx.compose.ui.unit.Dp, onChangePhoto: () -> Unit) {
    val t = rememberProfileTokens()
    Box(Modifier.size(outer)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape)
                .background(Brush.linearGradient(listOf(t.primary, t.secondary)))
                .padding(4.dp),
        ) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(t.background).padding(3.dp), contentAlignment = Alignment.Center) {
                StudentAvatar(
                    name = profile.student.name, size = outer - 14.dp, accent = profile.student.accent,
                    online = false, imagePath = profile.avatarPath,
                )
            }
        }
        // Only on your own profile (this screen is only ever your own).
        Box(
            Modifier.align(Alignment.BottomEnd).size(44.dp).clip(CircleShape)
                .background(t.cardElevated).border(2.dp, t.background, CircleShape)
                .clickable(role = Role.Button, onClick = onChangePhoto),
            contentAlignment = Alignment.Center,
        ) {
            Icon(VibeIcons.Camera, contentDescription = "Change profile photo", tint = t.primary, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Name, verification, handle, course line and university. Only fields the profile really has are shown: location, website and
 * join date are not part of the profile data yet, so they are left out rather than invented.
 */
@Composable
fun ProfileIdentity(student: Student) {
    val t = rememberProfileTokens()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(
                student.name, style = MaterialTheme.typography.headlineMedium, fontSize = 30.sp, lineHeight = 36.sp,
                fontWeight = FontWeight.Bold, color = t.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Verification comes from the server's student-verified flag (set in ProfileViewModel), never from sample data.
            if (student.verified) {
                Spacer(Modifier.width(8.dp))
                VerifiedBadge(size = 24.dp, ringColor = t.background)
            }
        }
        Text(student.handle, style = MaterialTheme.typography.bodyLarge, color = t.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (student.courseLine.isNotBlank()) {
            Text(
                student.courseLine, style = MaterialTheme.typography.bodyMedium, fontSize = 15.sp, color = t.textSecondary,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (student.university.isNotBlank()) {
            Text(
                student.university, style = MaterialTheme.typography.bodyMedium, fontSize = 15.sp, color = t.textPrimary,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Posts | Followers | Following in one rounded container. Followers and Following open their lists; Posts has no destination yet. */
@Composable
fun ProfileStatsCard(
    profile: MyProfile,
    onFollowers: () -> Unit,
    onFollowing: () -> Unit,
) {
    val t = rememberProfileTokens()
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .clip(ProfileShapes.card)
            .background(Brush.verticalGradient(listOf(t.cardElevated, t.card)))
            .border(1.dp, t.border, ProfileShapes.card),
    ) {
        StatCell(VibeIcons.Grid, profile.posts.toString(), "Posts", Modifier.weight(1f), null)
        StatDivider()
        StatCell(VibeIcons.Groups, compactCount(profile.followers), "Followers", Modifier.weight(1f), onFollowers)
        StatDivider()
        StatCell(VibeIcons.PersonAdd, compactCount(profile.following), "Following", Modifier.weight(1f), onFollowing)
    }
}

@Composable
private fun StatDivider() {
    val t = rememberProfileTokens()
    Box(Modifier.padding(vertical = 20.dp).width(1.dp).fillMaxHeight().background(t.border))
}

@Composable
private fun StatCell(icon: ImageVector, value: String, label: String, modifier: Modifier, onClick: (() -> Unit)?) {
    val t = rememberProfileTokens()
    Column(
        modifier
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = "$value $label" }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = t.primary, modifier = Modifier.size(22.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = t.textPrimary, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, fontSize = 13.sp, color = t.textMuted, maxLines = 1)
    }
}

/** [ Edit profile ] [ Share ] [ More ]. More holds the screens that used to be separate cards (nothing was removed). */
@Composable
fun ProfileActionRow(profile: MyProfile, actions: ProfileActions) {
    val t = rememberProfileTokens()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(52.dp).clip(ProfileShapes.button)
                .background(Brush.horizontalGradient(listOf(t.primary, t.secondary)))
                .clickable(role = Role.Button, onClick = actions.onEditProfile),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(VibeIcons.Edit, contentDescription = null, tint = t.onPrimary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Edit profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = t.onPrimary, maxLines = 1)
        }
        SquareAction(VibeIcons.Share, "Share profile") {
            // No profile-sharing format existed in the app, so this sends a plain-text card with the profile's deep link.
            val text = "${profile.student.name} (${profile.student.handle}) on UniVibe\nunivibe://student/${profile.student.id}"
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            runCatching { context.startActivity(Intent.createChooser(send, "Share profile")) }
        }
        Box {
            SquareAction(VibeIcons.More, "More options") { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = t.cardElevated) {
                MoreItem(VibeIcons.Sparkle, "Promotions") { menuOpen = false; actions.onOpenPromotions() }
                MoreItem(VibeIcons.Badge, "Campus businesses") { menuOpen = false; actions.onOpenBusinesses() }
                MoreItem(VibeIcons.Shield, "Privacy & safety") { menuOpen = false; actions.onOpenPrivacy() }
                MoreItem(VibeIcons.Settings, "Settings") { menuOpen = false; actions.onOpenSettings() }
            }
        }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    val t = rememberProfileTokens()
    DropdownMenuItem(
        text = { Text(label, color = t.textPrimary) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = t.primary) },
        onClick = onClick,
    )
}

@Composable
private fun SquareAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val t = rememberProfileTokens()
    Box(
        Modifier.size(52.dp).clip(ProfileShapes.button).background(t.card)
            .border(1.dp, t.border, ProfileShapes.button)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = t.textPrimary, modifier = Modifier.size(22.dp))
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Content cards
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun SectionCard(
    title: String,
    actionLabel: String?,
    onAction: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = rememberProfileTokens()
    Column(
        Modifier.fillMaxWidth().clip(ProfileShapes.card).background(t.card).border(1.dp, t.border, ProfileShapes.card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = t.textPrimary, modifier = Modifier.weight(1f))
            if (actionLabel != null) {
                Box(
                    Modifier.heightIn(min = 40.dp).clip(ProfileShapes.chip).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(actionLabel, style = MaterialTheme.typography.labelLarge, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = t.primary) }
            }
        }
        content()
    }
}

@Composable
fun AboutMeCard(bio: String, onEdit: () -> Unit) {
    val t = rememberProfileTokens()
    SectionCard("About me", "Edit", onEdit) {
        Text(
            bio.ifBlank { "Tell your campus community a little about yourself." },
            style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, lineHeight = 23.sp,
            color = if (bio.isBlank()) t.textMuted else t.textSecondary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InterestsCard(interests: List<String>, onEdit: () -> Unit) {
    val t = rememberProfileTokens()
    SectionCard("Interests", "Edit", onEdit) {
        if (interests.isEmpty()) {
            Text("Add interests so people who share your vibe can find you.", style = MaterialTheme.typography.bodyMedium, color = t.textMuted)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                interests.forEach { label ->
                    // Read-only: the app has no "students with this interest" screen yet, so chips are not tappable.
                    Text(
                        label, style = MaterialTheme.typography.labelLarge, fontSize = 14.sp, color = t.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp).clip(ProfileShapes.chip).background(t.background)
                            .border(1.dp, t.primary.copy(alpha = 0.7f), ProfileShapes.chip).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun PhotosCard(photos: List<String>, onOpen: () -> Unit) {
    val t = rememberProfileTokens()
    SectionCard("Photos", if (photos.isEmpty()) null else "See all", onOpen) {
        if (photos.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(VibeIcons.Photo, contentDescription = null, tint = t.primary, modifier = Modifier.size(30.dp))
                Text("Share your first photo", style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
                OutlineAction("+ Add photo", onOpen)
            }
        } else {
            val shown = photos.take(4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                shown.forEachIndexed { index, path ->
                    // Square cells sized by the row: Coil decodes at the displayed size, so thumbnails are not full-resolution.
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(ProfileShapes.thumb).background(t.cardElevated).clickable(role = Role.Button, onClick = onOpen)) {
                        PhotoImage(path, contentDescription = "Photo ${index + 1}")
                    }
                }
                repeat(4 - shown.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun OutlineAction(label: String, onClick: () -> Unit) {
    val t = rememberProfileTokens()
    Box(
        Modifier.heightIn(min = 44.dp).clip(ProfileShapes.button).border(1.dp, t.primary, ProfileShapes.button)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = t.primary) }
}

@Composable
fun CommunitiesCard(groups: List<Community>, onSeeAll: () -> Unit, onOpen: (String) -> Unit) {
    val t = rememberProfileTokens()
    SectionCard("Communities", "See all", onSeeAll) {
        if (groups.isEmpty()) {
            Text("You haven't joined any communities yet.", style = MaterialTheme.typography.bodyMedium, color = t.textMuted)
            OutlineAction("Discover communities", onSeeAll)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(groups, key = { it.id }) { CommunityTile(it) { onOpen(it.id) } }
            }
        }
    }
}

@Composable
private fun CommunityTile(group: Community, onClick: () -> Unit) {
    val t = rememberProfileTokens()
    Row(
        Modifier.width(220.dp).clip(ProfileShapes.thumb).background(t.cardElevated).border(1.dp, t.border, ProfileShapes.thumb)
            .clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(accentGradient(group.accent))), contentAlignment = Alignment.Center) {
            val url = group.avatarUrl
            if (url != null) PhotoImage(url, contentDescription = null, modifier = Modifier.fillMaxSize())
            else Icon(VibeIcons.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.titleSmall, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val role = when (group.role) { CommunityRole.Owner -> " · Owner"; CommunityRole.Moderator -> " · Moderator"; else -> "" }
            Text("${compactCount(group.members)} members$role", style = MaterialTheme.typography.bodySmall, fontSize = 12.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SavedPostsCard(
    latest: Post?,
    loaded: Boolean,
    savedCount: Int,
    onSeeAll: () -> Unit,
    onOpenPost: (String) -> Unit,
) {
    val t = rememberProfileTokens()
    SectionCard("Saved posts", "See all", onSeeAll) {
        when {
            latest != null -> Row(
                Modifier.fillMaxWidth().clip(ProfileShapes.thumb).clickable(role = Role.Button) { onOpenPost(latest.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(64.dp).clip(ProfileShapes.chip).background(t.cardElevated), contentAlignment = Alignment.Center) {
                    val photo = latest.imagePaths.firstOrNull() ?: latest.imagePath
                    if (photo != null) PhotoImage(photo, contentDescription = null)
                    else Icon(VibeIcons.Bookmark, contentDescription = null, tint = t.primary, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(latest.text.ifBlank { "Photo post" }, style = MaterialTheme.typography.bodyMedium, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = t.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${latest.author.name} · ${latest.timeAgo}", style = MaterialTheme.typography.bodySmall, fontSize = 13.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(VibeIcons.BookmarkFilled, contentDescription = "Saved", tint = t.primary, modifier = Modifier.size(24.dp))
            }
            // The preview could not be read, but the profile knows how many are saved.
            savedCount > 0 -> Text("$savedCount saved", style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
            loaded -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("You haven't saved any posts yet.", style = MaterialTheme.typography.bodyMedium, color = t.textSecondary)
                Text("Posts you save will appear here. Only you can see them.", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
            }
            else -> Box(Modifier.fillMaxWidth().height(64.dp).clip(ProfileShapes.chip).background(t.cardElevated))
        }
    }
}

internal fun compactCount(n: Int): String =
    if (n >= 1000) String.format(java.util.Locale.US, "%.1fK", n / 1000f).replace(".0K", "K") else n.toString()
