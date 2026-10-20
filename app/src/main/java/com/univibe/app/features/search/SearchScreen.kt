package com.univibe.app.features.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.CommunityCard
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenStudent: (String) -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openCommunity = com.univibe.app.ui.components.LocalOpenCommunity.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().imePadding()) {
        VibeTopBar("Search", navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        VibeTextField(
            value = state.query,
            onValueChange = viewModel::onQuery,
            placeholder = "Search students, communities, posts and events",
            leadingIcon = VibeIcons.Search,
            trailingIcon = if (state.query.isNotEmpty()) ({ VibeIconButton(VibeIcons.Close, "Clear search", viewModel::clear) }) else null,
            modifier = Modifier.padding(horizontal = Spacing.md).focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.remember() }),
        )
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = Spacing.sm)) {
            val results = state.results
            when {
                state.tooShort -> RecentList(state.recents, viewModel::useRecent, viewModel::forget, viewModel::forgetAll)
                state.error != null && results == null -> Box(Modifier.padding(Spacing.md)) { ErrorState(state.error!!, onRetry = { viewModel.retry() }) }
                results == null -> Box(Modifier.padding(Spacing.md)) { LoadingSkeleton() }
                results.isEmpty && !state.loading -> EmptyState(
                    icon = VibeIcons.Search, title = "No results for \"${state.resultsFor}\"",
                    message = "Check the spelling, or try fewer words. You can only find people and posts from your own campus.",
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xl)) {
                    if (state.loading) item(key = "updating") {
                        Text("Updating…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.md))
                    }
                    if (results.people.isNotEmpty()) {
                        item(key = "h-people") { SectionTitle("Students") }
                        items(results.people, key = { "u-" + it.id }) { s ->
                            ResultRow(s.name, "${s.handle}${if (s.course.isNotBlank()) " · ${s.course}" else ""}",
                                lead = { StudentAvatar(s.name, size = 40.dp, accent = s.accent, imagePath = s.avatarUrl) }) {
                                viewModel.remember(); onOpenStudent(s.id)
                            }
                        }
                    }
                    if (results.communities.isNotEmpty()) {
                        item(key = "h-communities") { SectionTitle("Communities") }
                        items(results.communities, key = { "c-" + it.id }) { c ->
                            CommunityCard(c, onClick = { viewModel.remember(); openCommunity(c.id) })
                        }
                    }
                    if (results.posts.isNotEmpty()) {
                        item(key = "h-posts") { SectionTitle("Posts") }
                        items(results.posts, key = { "p-" + it.id }) { p ->
                            ResultRow(p.snippet.ifBlank { "Photo or video post" }, "by ${p.author.name}",
                                lead = { StudentAvatar(p.author.name, size = 40.dp, accent = p.author.accent, imagePath = p.author.avatarUrl) }) {
                                viewModel.remember(); onOpenPost(p.id)
                            }
                        }
                    }
                    if (results.events.isNotEmpty()) {
                        item(key = "h-events") { SectionTitle("Events") }
                        items(results.events, key = { "e-" + it.id }) { e ->
                            ResultRow(e.title, listOf(e.venue, e.category.replaceFirstChar { it.uppercase() }).filter { it.isNotBlank() }.joinToString(" · "),
                                lead = { Icon(VibeIcons.Events, contentDescription = null, modifier = Modifier.size(24.dp)) }) {
                                viewModel.remember(); onOpenEvent(e.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, top = Spacing.md, bottom = Spacing.xs))
}

@Composable
private fun ResultRow(title: String, subtitle: String, lead: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        lead()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun RecentList(recents: List<String>, onPick: (String) -> Unit, onForget: (String) -> Unit, onForgetAll: () -> Unit) {
    if (recents.isEmpty()) {
        EmptyState(
            icon = VibeIcons.Search, title = "Search UniVibe",
            message = "Type at least 2 letters to find students, communities, posts and events on your campus.",
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xl)) {
        item(key = "recent-head") {
            Row(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent searches", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = onForgetAll) { Text("Clear all") }
            }
        }
        items(recents, key = { "r-$it" }) { q ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(q) }.padding(start = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(VibeIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(q, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = Spacing.md))
                VibeIconButton(VibeIcons.Close, "Remove $q from recent searches", { onForget(q) })
            }
        }
    }
}
