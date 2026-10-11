package com.univibe.app.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.univibe.app.ui.components.StoryRing
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.UniVibeLockup
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            VibeTopBar(title = "UniVibe", titleContent = { UniVibeLockup() })
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(key = "stories") { StoriesRow(state.me, state.stories) }
                item(key = "composer") { Composer(state.me?.name ?: "Aisha Njoki", viewModel::openComposer) }
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
                    state.posts.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.DynamicFeed,
                            title = "Nothing here yet",
                            message = "Follow more students and communities to fill your feed.",
                        )
                    }
                    else -> items(state.posts, key = { it.id }) { post ->
                        CampusPostCard(
                            post = post,
                            onLike = { viewModel.toggleLike(post.id) },
                            onComment = { /* TODO: comments sheet */ },
                            onSave = { viewModel.toggleSave(post.id) },
                        )
                    }
                }
            }
        }

        // Sits inside the NavHost area, so it is always above the floating bottom bar.
        FloatingActionButton(
            onClick = viewModel::openComposer,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.md),
        ) { Icon(Icons.Outlined.Add, contentDescription = "Create post") }
    }

    if (state.composing) {
        CreatePostSheet(
            publishing = state.publishing,
            error = state.publishError,
            onDismiss = viewModel::closeComposer,
            onPost = viewModel::publish,
        )
    }
}

@Composable
private fun Composer(fullName: String, onOpen: () -> Unit) {
    val firstName = fullName.substringBefore(' ')
    VibeCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StudentAvatar(fullName, size = 40.dp)
            Spacer(Modifier.width(Spacing.sm + 4.dp))
            Text(
                "What's on your mind, $firstName?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ComposerAction(Icons.Outlined.Image, "Photo")
            ComposerAction(Icons.Outlined.Videocam, "Video")
            ComposerAction(Icons.Outlined.Event, "Event")
            ComposerAction(Icons.Outlined.Poll, "Poll")
        }
    }
}

@Composable
private fun ComposerAction(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StoriesRow(me: Student?, stories: List<Student>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        item(key = "you") {
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(72.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(58.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Add, contentDescription = "Add to your story", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                Text("Your story", style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
        }
        items(stories, key = { it.id }) { student ->
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StoryRing(ringSize = 72.dp) {
                    StudentAvatar(student.name, size = 58.dp, accent = student.accent, online = student.online)
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

@Composable
private fun CreatePostSheet(
    publishing: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onPost: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val tooLong = text.length > MAX_POST_LENGTH
    val valid = text.isNotBlank() && !tooLong
    VibeBottomSheet(onDismiss = onDismiss) {
        Text("New post", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(Spacing.md))
        VibeTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = "What's on your mind?",
            singleLine = false,
            minLines = 4,
            isError = tooLong || error != null,
            supportingText = error ?: "${text.length}/$MAX_POST_LENGTH",
        )
        Spacer(Modifier.size(Spacing.md))
        VibeButton(
            text = "Post",
            onClick = { onPost(text) },
            enabled = valid,
            loading = publishing,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
