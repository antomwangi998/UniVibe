package com.univibe.app.features.home

import com.univibe.app.ui.components.VibeIcons
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.media.VideoStore
import com.univibe.app.data.model.PostLimits
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.sharePost
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.univibe.app.ui.components.rememberBottomClearance
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.LocalFloatingBarVisible
import com.univibe.app.ui.components.FloatingBarClearance
import com.univibe.app.data.model.Post
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Block
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.Student
import com.univibe.app.ui.components.CampusPostCard
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.NotificationBell
import com.univibe.app.ui.components.StoryRing
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.UniVibeLockup
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenEvents: () -> Unit = {},
    onOpenComments: (String) -> Unit = {},
    onOpenStudent: (String) -> Unit = {},
    onCreatePost: () -> Unit = {},
    unreadNotifications: Int = 0,
    onOpenNotifications: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val composerFocus = remember { FocusRequester() }
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            VibeTopBar(
                title = "UniVibe",
                titleContent = { UniVibeLockup() },
                actions = { NotificationBell(unreadNotifications, onOpenNotifications) },
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = rememberBottomClearance()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (state.visibleStories.isNotEmpty()) item(key = "stories") { StoriesRow(state.visibleStories) }
                item(key = "composer") {
                    Composer(
                        fullName = state.me?.name.orEmpty(),
                        avatarUrl = state.me?.avatarUrl,
                        supportsMedia = viewModel.supportsMedia,
                        publishing = state.publishing,
                        error = state.publishError,
                        postedCount = state.postedCount,
                        focusRequester = composerFocus,
                        onOpenEvents = onOpenEvents,
                        onPost = { text, media, imagePath, videoPath, poll -> viewModel.publish(text, media, imagePath, videoPath, poll) },
                    )
                }
                item(key = "filters") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FeedFilter.entries.forEach { filter ->
                            InterestChip(filter.name, selected = state.filter == filter, onClick = { viewModel.setFilter(filter) })
                        }
                    }
                }
                when {
                    state.loading -> item(key = "loading") { LoadingSkeleton() }
                    state.error != null -> item(key = "error") { ErrorState(state.error!!, onRetry = viewModel::load) }
                    state.visiblePosts.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.DynamicFeed,
                            title = "Nothing here yet",
                            message = "Follow more students and communities to fill your feed.",
                        )
                    }
                    else -> items(state.visiblePosts, key = { it.id }) { post ->
                        CampusPostCard(
                            post = post,
                            onLike = { viewModel.toggleLike(post.id) },
                            onComment = { onOpenComments(post.id) },
                            onAuthorClick = { onOpenStudent(post.author.id) },
                            onSave = { viewModel.toggleSave(post.id) },
                            onMore = { viewModel.openPostMenu(post) },
                            onShare = {
                                if (sharePost(context, post)) viewModel.noteShared(post.id)
                                else viewModel.explain("Only campus-wide posts can be shared outside UniVibe.")
                            },
                            onVote = { index -> viewModel.vote(post.id, index) },
                        )
                    }
                }
            }
        }

        // Opens the full create-post screen. Rides just above the floating bar and drops down when it hides.
        val barVisible = LocalFloatingBarVisible.current
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val fabBottom by animateDpAsState(
            targetValue = if (barVisible) FloatingBarClearance + navInset + 8.dp else navInset + Spacing.md,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
            label = "fabBottom",
        )
        FloatingActionButton(
            onClick = onCreatePost,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = Spacing.md, bottom = fabBottom),
        ) { Icon(VibeIcons.Edit, contentDescription = "Write a post") }
    }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeNotice()
        }
    }

    state.menuPost?.let { post ->
        PostActionsSheet(
            post = post,
            isOwn = post.author.id == state.me?.id,
            onDismiss = viewModel::closePostMenu,
            onBlock = viewModel::blockAuthor,
            onReport = viewModel::reportPost,
            onEdit = viewModel::startEdit,
            onDelete = viewModel::askDelete,
        )
    }
    state.editingPost?.let { post ->
        EditPostSheet(post, state.savingEdit, onDismiss = viewModel::cancelEdit, onSave = viewModel::saveEdit)
    }
    state.deletingPost?.let {
        ConfirmDialog(
            title = "Delete this post?",
            message = "It will be removed for everyone, along with its comments. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
            destructive = true,
        )
    }
}

private val ReportReasons = listOf("Spam", "Harassment or bullying", "Inappropriate content", "False information", "Something else")

@Composable
private fun PostActionsSheet(
    post: Post,
    isOwn: Boolean,
    onDismiss: () -> Unit,
    onBlock: () -> Unit,
    onReport: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var choosingReason by remember(post.id) { mutableStateOf(false) }
    VibeBottomSheet(onDismiss = onDismiss) {
        when {
            isOwn -> {
                SheetOption(VibeIcons.Edit, "Edit post", onClick = onEdit)
                SheetOption(VibeIcons.Delete, "Delete post", destructive = true, onClick = onDelete)
            }
            choosingReason -> {
                Text("Why are you reporting this?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.sm))
                ReportReasons.forEach { reason -> SheetOption(VibeIcons.Report, reason) { onReport(reason) } }
            }
            else -> {
                SheetOption(VibeIcons.Report, "Report post") { choosingReason = true }
                SheetOption(VibeIcons.Block, "Block ${post.author.name}", destructive = true, onClick = onBlock)
            }
        }
    }
}

@Composable
private fun SheetOption(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(Spacing.md))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/**
 * Inline composer. Typing, Post, and each shortcut are separate controls; the card itself is not a button.
 */
@Composable
private fun Composer(
    fullName: String,
    avatarUrl: String?,
    supportsMedia: Boolean,
    publishing: Boolean,
    error: String?,
    postedCount: Int,
    focusRequester: FocusRequester,
    onOpenEvents: () -> Unit,
    onPost: (text: String, hasMedia: Boolean, imagePath: String?, videoPath: String?, pollOptions: List<String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var attached by rememberSaveable { mutableStateOf<String?>(null) }
    var attachedPath by rememberSaveable { mutableStateOf<String?>(null) }
    // Poll options are kept as one newline-joined string so the draft survives rotation.
    var pollDraft by rememberSaveable { mutableStateOf("") }
    var buildingPoll by remember { mutableStateOf(false) }
    var seenPosts by rememberSaveable { mutableStateOf(postedCount) }
    val pollOptions = if (pollDraft.isEmpty()) emptyList() else pollDraft.split("\n")

    fun discardAttachment() {
        if (attached == "Video") VideoStore.delete(attachedPath) else PhotoStore.delete(attachedPath)
        attachedPath = null
        attached = null
    }

    // Clear the draft after a successful post.
    LaunchedEffect(postedCount) {
        if (postedCount != seenPosts) {
            text = ""
            attached = null
            attachedPath = null
            pollDraft = ""
            seenPosts = postedCount
        }
    }

    // System photo picker: no storage permission needed.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = PhotoStore.import(context, uri, "post")
            if (path != null) {
                discardAttachment()
                pollDraft = ""
                attached = "Photo"; attachedPath = path
            } else Toast.makeText(context, "Couldn't use that image.", Toast.LENGTH_SHORT).show()
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = VideoStore.import(context, uri, "post")
            if (path != null) {
                discardAttachment()
                pollDraft = ""
                attached = "Video"; attachedPath = path
            } else Toast.makeText(context, "Couldn't use that video. Videos can be up to 50 MB.", Toast.LENGTH_SHORT).show()
        }
    }

    val tooLong = text.length > MAX_POST_LENGTH
    val hasContent = text.isNotBlank() || attached != null || pollOptions.isNotEmpty()
    val needsQuestion = pollOptions.isNotEmpty() && text.isBlank()
    val firstName = fullName.substringBefore(' ')

    VibeCard {
        Row(verticalAlignment = Alignment.Top) {
            StudentAvatar(fullName, size = 40.dp, imagePath = avatarUrl, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.width(Spacing.sm + 4.dp))
            VibeTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = "What's on your mind, $firstName?",
                singleLine = false,
                isError = tooLong || error != null,
                supportingText = error ?: if (needsQuestion) "Add a question for your poll" else if (text.isNotEmpty()) "${text.length}/$MAX_POST_LENGTH" else null,
            )
        }

        attached?.let { kind ->
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = Spacing.sm),
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (kind == "Photo") VibeIcons.Photo else VibeIcons.Video, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("$kind attached", style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { discardAttachment() }, modifier = Modifier.size(40.dp)) {
                        Icon(VibeIcons.Close, contentDescription = "Remove $kind", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        attachedPath?.takeIf { attached == "Photo" }?.let { path ->
            Box(Modifier.padding(top = Spacing.sm).size(96.dp).clip(RoundedCornerShape(16.dp))) {
                PhotoImage(path, contentDescription = "Selected photo preview")
            }
        }

        AnimatedVisibility(visible = hasContent) {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.End) {
                VibeButton(
                    text = "Post",
                    onClick = {
                        onPost(text, attached != null, attachedPath.takeIf { attached == "Photo" }, attachedPath.takeIf { attached == "Video" }, pollOptions)
                    },
                    enabled = !tooLong && !needsQuestion,
                    loading = publishing,
                    compact = true,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (supportsMedia) {
                ComposerAction(VibeIcons.Photo, "Photo") {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                ComposerAction(VibeIcons.Video, "Video") {
                    videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }
            }
            ComposerAction(VibeIcons.Events, "Event", onOpenEvents)
            ComposerAction(Icons.Outlined.Poll, "Poll") { buildingPoll = true }
        }
    }

    if (buildingPoll) {
        PollBuilderSheet(
            initial = pollOptions,
            onDismiss = { buildingPoll = false },
            onDone = { options ->
                if (attached != null) discardAttachment()
                pollDraft = options.joinToString("\n")
                buildingPoll = false
            },
        )
    }
}

@Composable
private fun PollBuilderSheet(initial: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val options = remember { mutableStateListOf<String>().apply { addAll(if (initial.size >= PostLimits.MIN_POLL_OPTIONS) initial else listOf("", "")) } }
    val trimmed = options.map { it.trim() }
    val duplicate = trimmed.map { it.lowercase() }.toSet().size != trimmed.size
    val valid = trimmed.all { it.isNotEmpty() } && !duplicate
    VibeBottomSheet(onDismiss = onDismiss) {
        Text("Create a poll", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.sm))
        options.forEachIndexed { index, value ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Spacing.sm)) {
                VibeTextField(
                    value = value,
                    onValueChange = { options[index] = it.take(PostLimits.MAX_POLL_OPTION_LENGTH) },
                    modifier = Modifier.weight(1f),
                    placeholder = "Option ${index + 1}",
                    singleLine = true,
                )
                if (options.size > PostLimits.MIN_POLL_OPTIONS) {
                    IconButton(onClick = { options.removeAt(index) }) {
                        Icon(VibeIcons.Close, contentDescription = "Remove option ${index + 1}")
                    }
                }
            }
        }
        if (duplicate && trimmed.all { it.isNotEmpty() }) {
            Text("Options must be different.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (options.size < PostLimits.MAX_POLL_OPTIONS) {
            ComposerAction(VibeIcons.Add, "Add option") { options.add("") }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.md, bottom = Spacing.md), horizontalArrangement = Arrangement.End) {
            VibeButton(text = "Add poll", onClick = { onDone(trimmed) }, enabled = valid)
        }
    }
}

@Composable
private fun EditPostSheet(post: Post, saving: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(post.id) { mutableStateOf(post.text) }
    val tooLong = text.length > MAX_POST_LENGTH
    VibeBottomSheet(onDismiss = onDismiss) {
        Text("Edit post", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.sm))
        VibeTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "What's on your mind?",
            singleLine = false,
            isError = tooLong,
            supportingText = "${text.length}/$MAX_POST_LENGTH",
        )
        Row(Modifier.fillMaxWidth().padding(top = Spacing.md, bottom = Spacing.md), horizontalArrangement = Arrangement.End) {
            VibeButton(
                text = "Save",
                onClick = { onSave(text) },
                enabled = !tooLong && (text.isNotBlank() || post.hasAttachment),
                loading = saving,
            )
        }
    }
}

@Composable
private fun ComposerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StoriesRow(stories: List<Student>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        item(key = "you") {
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(72.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(58.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(VibeIcons.Add, contentDescription = "Add to your story", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                Text("Your story", style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
        }
        items(stories, key = { it.id }) { student ->
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StoryRing(ringSize = 72.dp) {
                    StudentAvatar(student.name, size = 58.dp, accent = student.accent, online = student.online, imagePath = student.avatarUrl)
                }
                Text(
                    student.name.substringBefore(' '),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
