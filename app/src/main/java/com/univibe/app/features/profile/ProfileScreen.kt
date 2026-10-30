package com.univibe.app.features.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.MyProfile
import com.univibe.app.navigation.rememberTabListState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibePullToRefresh
import com.univibe.app.ui.components.VibeTopBar
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
    val onOpenPromotions: () -> Unit = {},
    val onOpenBusinesses: () -> Unit = {},
    val unreadNotifications: Int = 0,
    val onOpenNotifications: () -> Unit = {},
    /** Opens one post (used by the saved-post preview). */
    val onOpenPost: (String) -> Unit = {},
)

@Composable
fun ProfileScreen(actions: ProfileActions, viewModel: ProfileViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val tokens = rememberProfileTokens()

    Column(Modifier.fillMaxSize().background(tokens.background)) {
        val profile = state.profile
        // The cover carries its own icons; the plain top bar is only for loading/error.
        val showCover = !state.loading && state.error == null && profile != null
        if (!showCover) VibeTopBar("Profile", actions = { VibeIconButton(VibeIcons.Settings, "Settings", actions.onOpenSettings) })
        when {
            state.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 1)
            state.error != null || profile == null -> ErrorState(state.error ?: "Something went wrong.", viewModel::load)
            else -> VibePullToRefresh(refreshing = state.refreshing, onRefresh = viewModel::pullRefresh) {
                // One vertical scroller for the whole page. The bottom padding keeps the last card clear of the floating bar.
                LazyColumn(
                    state = rememberTabListState("profile"),
                    contentPadding = PaddingValues(start = ProfileHorizontalPadding, end = ProfileHorizontalPadding, bottom = rememberBottomClearance() + Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "header") { ProfileHeader(profile, actions, state.look) }
                    item(key = "about") { AboutMeCard(profile.bio, actions.onEditProfile) }
                    item(key = "interests") { InterestsCard(profile.student.interests, actions.onEditProfile) }
                    item(key = "photos") { PhotosCard(profile.photos, actions.onOpenPhotos) }
                    item(key = "communities") { CommunitiesCard(state.groups, actions.onOpenCommunities, actions.onOpenCommunity) }
                    item(key = "saved") {
                        SavedPostsCard(state.latestSaved, state.savedLoaded, profile.savedPosts, actions.onOpenSaved, actions.onOpenPost)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(profile: MyProfile, actions: ProfileActions, look: com.univibe.app.data.model.ProfileLook?) {
    // About 32% of the screen width, kept between 108dp and 136dp so it suits small phones and tablets alike.
    val avatarOuter = (LocalConfiguration.current.screenWidthDp * 0.32f).coerceIn(108f, 136f).dp
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ProfileCover(
            accent = profile.student.accent + 1,
            coverPath = profile.coverPath,
            modifier = Modifier.fullBleed(ProfileHorizontalPadding),
            leading = { CoverBellButton(actions.unreadNotifications, actions.onOpenNotifications) },
            actions = { CoverIconButton(VibeIcons.Settings, "Settings", actions.onOpenSettings) },
            avatarSize = avatarOuter,
            look = look,
            avatar = { ProfileAvatar(profile, avatarOuter, onChangePhoto = actions.onEditProfile, look = look) },
        )
        Spacer(Modifier.height(avatarOuter / 2 + 8.dp))
        ProfileIdentity(profile.student, look)
        Spacer(Modifier.height(10.dp))
        ProfileStatsCard(profile, actions.onOpenFollowers, actions.onOpenFollowing)
        Spacer(Modifier.height(10.dp))
        ProfileActionRow(profile, actions)
    }
}
