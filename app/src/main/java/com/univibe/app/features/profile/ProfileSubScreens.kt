package com.univibe.app.features.profile

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.core.keyedViewModel
import com.univibe.app.features.settings.SettingsGroup
import com.univibe.app.features.settings.SettingsPage
import com.univibe.app.ui.components.CampusPostCard
import com.univibe.app.ui.components.sharePost
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.accentGradient
import com.univibe.app.ui.components.rememberPhotoPicker
import com.univibe.app.ui.theme.Spacing

private const val MAX_PHOTOS = 30

// ------------------------------------------------------------------ photos
@Composable
fun PhotosScreen(onBack: () -> Unit, viewModel: PhotosViewModel = viewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pick = rememberPhotoPicker("gallery") { viewModel.add(it) }

    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Photos", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 1)
            s.error != null -> ErrorState(s.error!!, viewModel::load)
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (s.photos.size < MAX_PHOTOS) {
                    item(key = "add") {
                        Surface(
                            onClick = pick, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.aspectRatio(1f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(VibeIcons.Photo, contentDescription = "Add photo", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
                items(s.photos, key = { it }) { path ->
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { selected = path }) {
                        PhotoImage(path, "Photo, tap to view")
                    }
                }
            }
        }
    }

    if (!s.loading && s.photos.isEmpty() && s.error == null) {
        // Empty hint sits under the grid's add tile.
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 200.dp), contentAlignment = Alignment.TopCenter) {
            Text("No photos yet. Add some to show on your profile.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    selected?.let { path ->
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                PhotoImage(path, "Photo", contentScale = ContentScale.Fit)
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(Spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { selected = null }) { Icon(VibeIcons.Close, contentDescription = "Close", tint = Color.White) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(VibeIcons.Delete, contentDescription = "Delete photo", tint = Color.White) }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete photo?", message = "This removes the photo from your profile.", confirmLabel = "Delete", destructive = true,
            onConfirm = { selected?.let(viewModel::remove); selected = null; confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
}

// ------------------------------------------------------------------ saved posts
@Composable
fun SavedPostsScreen(
    onBack: () -> Unit,
    onOpenComments: (String) -> Unit,
    onOpenStudent: (String) -> Unit,
    viewModel: SavedPostsViewModel = viewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val shareContext = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Saved posts", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
            s.error != null -> ErrorState(s.error!!, viewModel::load)
            s.posts.isEmpty() -> EmptyState(
                VibeIcons.Bookmark, "Nothing saved yet",
                "Tap the bookmark on a post to keep it here.",
            )
            else -> {
              val listState = rememberLazyListState()
              val nearEnd by remember {
                  derivedStateOf {
                      val info = listState.layoutInfo
                      info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4
                  }
              }
              LaunchedEffect(nearEnd, s.posts.size) { if (nearEnd) viewModel.loadMore() }
              LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.lg),
            ) {
                items(s.posts, key = { it.id }) { post ->
                    CampusPostCard(
                        post, onLike = { viewModel.toggleLike(post.id) }, onComment = { onOpenComments(post.id) },
                        onSave = { viewModel.unsave(post.id) }, onAuthorClick = { onOpenStudent(post.author.id) },
                        onShare = {
                            if (sharePost(shareContext, post)) viewModel.noteShared(post.id)
                            else Toast.makeText(shareContext, "Only campus-wide posts can be shared outside UniVibe.", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                if (s.loadingMore) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
              }
            }
        }
    }
}

// ------------------------------------------------------------------ followers / following
@Composable
fun FollowListScreen(kind: FollowKind, onBack: () -> Unit, onOpenStudent: (String) -> Unit) {
    val viewModel = keyedViewModel("follow-${kind.name}") { FollowListViewModel(kind) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    SettingsPage(if (kind == FollowKind.Followers) "Followers" else "Following", onBack) {
        when {
            s.loading -> item { LoadingSkeleton(itemCount = 2) }
            s.error != null -> item { ErrorState(s.error!!, viewModel::load) }
            s.entries.isEmpty() -> item {
                EmptyState(
                    VibeIcons.Groups,
                    if (kind == FollowKind.Followers) "No followers yet" else "Not following anyone yet",
                    "Students you connect with will appear here.",
                )
            }
            else -> item {
                SettingsGroup {
                    s.entries.forEach { entry ->
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenStudent(entry.student.id) }
                                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StudentAvatar(entry.student.name, size = 48.dp, accent = entry.student.accent, online = entry.student.online, imagePath = entry.student.avatarUrl)
                            Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                                Text(entry.student.name, style = MaterialTheme.typography.titleSmall)
                                Text(entry.student.course.ifBlank { entry.student.handle }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FollowButton(entry.isFollowing, { viewModel.toggle(entry.student.id) }, compact = true)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ another student's profile
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudentProfileScreen(studentId: String, onBack: () -> Unit, onOpenChat: (String) -> Unit, onEditProfile: () -> Unit) {
    val viewModel = keyedViewModel("student-$studentId") { StudentProfileViewModel(studentId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.consumeNotice() } }

    Column(Modifier.fillMaxSize()) {
        VibeTopBar(
            title = s.student?.name ?: "Profile", compactTitle = true,
            navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) },
            actions = { if (s.student != null && !s.isMe) VibeIconButton(VibeIcons.More, "More options", { viewModel.openMenu(true) }) },
        )
        val student = s.student
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 1)
            s.notFound || student == null -> ErrorState("This profile isn't available.", onBack, title = "Not found")
            else -> LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(24.dp))
                                .background(Brush.linearGradient(accentGradient(student.accent + 1))),
                        )
                        Box(Modifier.offset(y = (-48).dp)) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.background, modifier = Modifier.size(104.dp)) {
                                Box(contentAlignment = Alignment.Center) { StudentAvatar(student.name, size = 92.dp, accent = student.accent, online = student.online, imagePath = student.avatarUrl) }
                            }
                        }
                        Column(Modifier.offset(y = (-40).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(student.name, style = MaterialTheme.typography.headlineSmall)
                                if (student.verified) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Filled.Verified, contentDescription = "Verified student", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                            Text(student.handle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "${student.courseLine}\n${student.university}".trim(),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.xs),
                            )
                        }
                    }
                }
                item {
                    Row(Modifier.offset(y = (-24).dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (s.isMe) {
                            VibeButton("Edit profile", onEditProfile, Modifier.weight(1f))
                        } else {
                            VibeButton(
                                if (s.following) "Following" else "Follow", viewModel::toggleFollow, Modifier.weight(1f),
                                style = if (s.following) VibeButtonStyle.Secondary else VibeButtonStyle.Primary,
                            )
                            VibeButton(
                                "Message", { viewModel.message(onOpenChat) }, Modifier.weight(1f),
                                style = VibeButtonStyle.Secondary, loading = s.opening, leadingIcon = VibeIcons.Comment,
                            )
                        }
                    }
                }
                if (student.interests.isNotEmpty()) {
                    item {
                        VibeCard {
                            Text("Interests", style = MaterialTheme.typography.titleMedium)
                            FlowRow(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                student.interests.forEach { InterestChip(it) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (s.menuOpen && s.student != null) {
        VibeBottomSheet(onDismiss = { viewModel.openMenu(false) }) {
            SheetRow(VibeIcons.Report, "Report ${s.student!!.name}", false, viewModel::report)
            SheetRow(VibeIcons.Block, "Block ${s.student!!.name}", true, viewModel::block)
        }
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, destructive: Boolean, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().height(56.dp).clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(Spacing.md))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
