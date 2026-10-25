package com.univibe.app.features.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.app.AppContainer
import com.univibe.app.data.repository.CommunityRepository
import com.univibe.app.data.repository.SuggestedCommunity
import com.univibe.app.ui.components.CommunityCard
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.communityReasonLabel
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverCommunitiesState(
    val loading: Boolean = true,
    val items: List<SuggestedCommunity> = emptyList(),
    /** Set only when there is nothing to show; a failed refresh over existing cards keeps them. */
    val error: String? = null,
)

/** Suggested communities for Discover. The ranking is the server's; this only loads and shows it. */
class DiscoverCommunitiesViewModel(
    private val repository: CommunityRepository = AppContainer.communityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverCommunitiesState())
    val state: StateFlow<DiscoverCommunitiesState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty(), error = null) }
            try {
                val items = repository.getSuggested()
                _state.update { it.copy(loading = false, items = items, error = null) }
            } catch (e: Exception) {
                _state.update { if (it.items.isEmpty()) it.copy(loading = false, error = "We couldn't load communities.") else it.copy(loading = false) }
            }
        }
    }
}

@Composable
internal fun DiscoverCommunities(
    onOpenCommunity: (String) -> Unit,
    onBrowseAll: () -> Unit,
    viewModel: DiscoverCommunitiesViewModel = viewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    when {
        s.loading -> Box(Modifier.padding(Spacing.md)) { LoadingSkeleton() }
        s.error != null -> Box(Modifier.padding(Spacing.md)) { ErrorState(s.error!!, onRetry = viewModel::load) }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.xl)) {
            if (s.items.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = VibeIcons.Groups, title = "No suggestions yet",
                        message = "Communities you can join will show up here as your campus creates them.",
                        actionLabel = "Browse all communities", onAction = onBrowseAll,
                    )
                }
            } else {
                item(key = "title") {
                    Text("Suggested for you", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs))
                }
                items(s.items, key = { it.community.id }) { sc ->
                    CommunityCard(sc.community, reason = communityReasonLabel(sc.reason), onClick = { onOpenCommunity(sc.community.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
                }
                item(key = "all") {
                    VibeButton("Browse all communities", onBrowseAll, Modifier.padding(Spacing.md), style = VibeButtonStyle.Secondary, compact = true)
                }
            }
        }
    }
}
