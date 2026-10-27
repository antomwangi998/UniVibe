package com.univibe.app.features.post

import com.univibe.app.ui.components.VibeIcons
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.model.PostAudience
import com.univibe.app.features.home.MAX_POST_LENGTH
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.PhotoEditDialog
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.isTrimmedInFeed
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun CreatePostScreen(onBack: () -> Unit) {
    val viewModel = keyedViewModel("create-post") { CreatePostViewModel() }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var showAudience by rememberSaveable { mutableStateOf(false) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    // The photo being cropped, if any, and each photo's width / height (read from the file header) for the "trimmed in the feed" hint.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val aspects = remember { mutableStateMapOf<String, Float>() }
    LaunchedEffect(s.photos) {
        s.photos.forEach { p -> if (p !in aspects) PhotoStore.aspectOf(p)?.let { aspects[p] = it } }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(s.published) {
        if (s.published) {
            Toast.makeText(context, "Posted", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.consumeNotice() } }

    // System photo picker: no storage permission. Several images at once, capped at the room left.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_POST_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            viewModel.setImporting(true)
            val room = (MAX_POST_PHOTOS - viewModel.state.value.photos.size).coerceAtLeast(0)
            val chosen = uris.take(room)
            val paths = chosen.mapNotNull { PhotoStore.import(context, it, "post") }
            viewModel.addPhotos(paths, failed = chosen.size - paths.size)
            viewModel.setImporting(false)
        }
    }

    val requestClose = { if (s.isDirty && !s.publishing) showDiscard = true else onBack() }
    BackHandler(enabled = s.isDirty && !s.publishing) { showDiscard = true }

    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().imePadding()) {
        VibeTopBar(
            "New post", compactTitle = true,
            navigationIcon = { VibeIconButton(VibeIcons.Close, "Close", requestClose) },
            actions = { VibeButton("Post", viewModel::publish, enabled = s.canPost, loading = s.publishing, compact = true) },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StudentAvatar(s.me?.name ?: "You", size = 44.dp, accent = s.me?.accent ?: 0, imagePath = s.me?.avatarUrl)
                Spacer(Modifier.width(Spacing.sm + 4.dp))
                Column {
                    Text(s.me?.name ?: "You", style = MaterialTheme.typography.titleSmall)
                    AudiencePill(s.audience) { showAudience = true }
                }
            }
            VibeTextField(
                value = s.text,
                onValueChange = viewModel::setText,
                modifier = Modifier.focusRequester(focus),
                placeholder = "What's on your mind?",
                singleLine = false,
                minLines = 5,
                isError = s.tooLong || s.error != null,
                supportingText = when {
                    s.error != null -> s.error
                    s.tooLong -> "Posts can be up to $MAX_POST_LENGTH characters. Remove ${-s.charsLeft}."
                    else -> null
                },
                enabled = !s.publishing,
            )
            if (s.photos.isNotEmpty()) {
                Text("Photos ${s.photos.size}/$MAX_POST_PHOTOS", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                if (s.photos.any { p -> aspects[p]?.let(::isTrimmedInFeed) == true }) {
                    Text(
                        "Very tall or wide photos are trimmed in the feed. Tap a photo to crop it.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    s.photos.forEachIndexed { index, path ->
                        Box(
                            Modifier.size(112.dp).clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = !s.publishing, role = Role.Button, onClickLabel = "Crop photo ${index + 1}") { editing = path },
                        ) {
                            PhotoImage(path, contentDescription = "Photo ${index + 1} preview", modifier = Modifier.fillMaxSize())
                            val trimmed = aspects[path]?.let(::isTrimmedInFeed) == true
                            Surface(
                                Modifier.align(Alignment.BottomStart).padding(6.dp), shape = CircleShape,
                                color = if (trimmed) colors.primary else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                                contentColor = if (trimmed) colors.onPrimary else androidx.compose.ui.graphics.Color.White,
                            ) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(VibeIcons.Crop, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Crop", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Surface(
                                Modifier.align(Alignment.TopEnd).padding(6.dp), shape = CircleShape,
                                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                            ) {
                                IconButton(onClick = { viewModel.removePhoto(path) }, enabled = !s.publishing, modifier = Modifier.size(32.dp)) {
                                    Icon(VibeIcons.Close, contentDescription = "Remove photo ${index + 1}", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        // Toolbar rides above the keyboard.
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val add = s.canAddPhoto
            if (viewModel.supportsMedia) Row(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = add, role = Role.Button, onClickLabel = "Add photos") {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }.padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(VibeIcons.Photo, contentDescription = null, tint = if (add) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (s.importing) "Adding…" else if (s.photos.size >= MAX_POST_PHOTOS) "Photo limit reached" else "Add photos",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (add) colors.onSurface else colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${s.charsLeft}",
                style = MaterialTheme.typography.labelLarge,
                color = if (s.tooLong) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.padding(end = Spacing.sm).semantics { contentDescription = "${s.charsLeft} characters left" },
            )
        }
    }

    if (showAudience) {
        VibeBottomSheet(onDismiss = { showAudience = false }) {
            Text("Who can see this?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm))
            AudienceOption(VibeIcons.Public, "Campus", "Everyone at your university", s.audience == PostAudience.Campus) {
                viewModel.selectAudience(PostAudience.Campus); showAudience = false
            }
            AudienceOption(VibeIcons.Groups, "Followers", "Only people who follow you", s.audience == PostAudience.Followers) {
                viewModel.selectAudience(PostAudience.Followers); showAudience = false
            }
            s.communities.forEach { c ->
                val option = PostAudience.Community(c.id, c.name)
                AudienceOption(VibeIcons.Groups, c.name, "Members of ${c.name}", s.audience == option) {
                    viewModel.selectAudience(option); showAudience = false
                }
            }
        }
    }

    if (showDiscard) {
        ConfirmDialog(
            title = "Discard this post?",
            message = "Your text and photos will be deleted.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            destructive = true,
            onConfirm = { showDiscard = false; viewModel.discard(); onBack() },
            onDismiss = { showDiscard = false },
        )
    }

    editing?.let { path ->
        PhotoEditDialog(
            path,
            onDismiss = { editing = null },
            onSaved = { saved -> viewModel.replacePhoto(path, saved); editing = null },
        )
    }
}

private fun PostAudience.label(): String = when (this) {
    PostAudience.Campus -> "Campus"
    PostAudience.Followers -> "Followers"
    is PostAudience.Community -> name
}

@Composable
private fun AudiencePill(audience: PostAudience, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
        modifier = Modifier.heightIn(min = 32.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Change audience", onClick = onClick)
            .semantics { contentDescription = "Audience: ${audience.label()}. Double tap to change." },
    ) {
        Row(Modifier.padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(audience.label(), style = MaterialTheme.typography.labelMedium)
            Icon(VibeIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AudienceOption(icon: ImageVector, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (selected) Icon(VibeIcons.Check, contentDescription = "Selected", tint = colors.primary)
    }
}
