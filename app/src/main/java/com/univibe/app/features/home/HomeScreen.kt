package com.univibe.app.features.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.UniVibeLockup
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.BrandMint
import com.univibe.app.ui.theme.BrandTeal
import com.univibe.app.ui.theme.Spacing

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        VibeTopBar(title = "UniVibe", titleContent = { UniVibeLockup() })
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "stories") { StoriesRow(state.stories) }
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
                    CampusPostCard(post, onLike = { viewModel.toggleLike(post.id) }, onComment = { /* TODO: comments sheet */ })
                }
            }
        }
    }
}

@Composable
private fun StoriesRow(stories: List<Student>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        item(key = "you") {
            Column(Modifier.width(68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(64.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(54.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Add, contentDescription = "Add to your story", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                Text("You", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
        items(stories, key = { it.id }) { student ->
            Column(Modifier.width(68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(64.dp)
                        .border(2.dp, Brush.linearGradient(listOf(BrandTeal, BrandMint)), CircleShape)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    StudentAvatar(student.name, size = 54.dp, accent = student.accent)
                }
                Text(
                    student.name.substringBefore(' '),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
