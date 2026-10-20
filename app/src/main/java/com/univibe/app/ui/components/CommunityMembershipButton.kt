package com.univibe.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.univibe.app.data.model.MembershipState
import com.univibe.app.data.repository.CommunityMembershipStore
import com.univibe.app.data.repository.MembershipSnapshot

/** The shared membership store. Null in previews and tests, where the button renders its initial state read-only. */
val LocalCommunityMembership = compositionLocalOf<CommunityMembershipStore?> { null }

/** Opens a community page. Provided by the app shell so any post card can link to its community. */
val LocalOpenCommunity = compositionLocalOf<(String) -> Unit> { {} }

/**
 * The one membership control used everywhere a community appears: Home posts, Discover posts, community cards,
 * search results and the community page. It owns no state of its own; it reads and drives [CommunityMembershipStore],
 * so a join made on any surface shows on all of them.
 *
 * [initial] is what the server said when the surrounding data was fetched; it only seeds the store the first time.
 */
@Composable
fun CommunityMembershipButton(
    communityId: String,
    initial: MembershipState,
    memberCount: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    /** When set, a Joined member can tap to leave (the caller shows its confirmation). Feed cards leave this null. */
    onLeaveRequested: (() -> Unit)? = null,
    /** Called after the server confirmed a change, so a screen can refresh its own details. */
    onChanged: (MembershipSnapshot) -> Unit = {},
) {
    val store = LocalCommunityMembership.current
    val snapshot: MembershipSnapshot = if (store == null) {
        MembershipSnapshot(initial, memberCount)
    } else {
        LaunchedEffect(communityId) { store.seed(communityId, initial, memberCount) }
        val all by store.all.collectAsState()
        all[communityId] ?: MembershipSnapshot(initial, memberCount)
    }
    val latestOnChanged by rememberUpdatedState(onChanged)
    var last by remember(communityId) { mutableStateOf<MembershipState?>(null) }
    LaunchedEffect(snapshot.state, snapshot.busy) {
        if (!snapshot.busy) {
            if (last != null && last != snapshot.state) latestOnChanged(snapshot)
            last = snapshot.state
        }
    }

    Column(modifier, horizontalAlignment = Alignment.End) {
        MembershipControl(snapshot, compact, store != null, onLeaveRequested) { store?.click(communityId) }
        snapshot.error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun MembershipControl(
    s: MembershipSnapshot,
    compact: Boolean,
    interactive: Boolean,
    onLeaveRequested: (() -> Unit)?,
    onClick: () -> Unit,
) {
    // A failed attempt keeps the previous valid state; the same tap retries, and the label says so.
    val retry = s.error != null
    when (s.state) {
        MembershipState.Unavailable -> Unit // never offer an action that cannot work
        MembershipState.Joined -> if (onLeaveRequested != null) {
            VibeButton("Joined", onLeaveRequested, style = VibeButtonStyle.Secondary, compact = compact, loading = s.busy, enabled = interactive)
        } else {
            Row { Text("Joined", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        }
        MembershipState.Join -> VibeButton(if (retry) "Retry" else "Join", onClick, style = VibeButtonStyle.Primary, compact = compact, loading = s.busy, enabled = interactive)
        MembershipState.Request -> VibeButton(if (retry) "Retry" else "Request to join", onClick, style = VibeButtonStyle.Primary, compact = compact, loading = s.busy, enabled = interactive)
        MembershipState.Invited -> VibeButton(if (retry) "Retry" else "Accept invite", onClick, style = VibeButtonStyle.Primary, compact = compact, loading = s.busy, enabled = interactive)
        // Tapping a pending request withdraws it; in compact feed cards it is a status only.
        MembershipState.Pending -> if (compact) {
            Text("Request pending", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            VibeButton("Withdraw request", onClick, style = VibeButtonStyle.Secondary, compact = false, loading = s.busy, enabled = interactive)
        }
    }
}
