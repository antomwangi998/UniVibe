package com.univibe.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.data.net.accentFor
import com.univibe.app.data.model.PostCommunity
import com.univibe.app.ui.theme.Spacing

/**
 * Where a post was published, kept apart from who wrote it: community identity and membership state on one compact
 * line above the author row. The author row below is untouched. Used by Home, Discover, profiles and search.
 *
 * A private community's post is only ever shown to members, so there the state reads "Joined" rather than offering
 * Join; the button never appears when joining isn't possible.
 */
@Composable
fun PostCommunityHeader(community: PostCommunity, announcement: Boolean, modifier: Modifier = Modifier) {
    val open = LocalOpenCommunity.current
    Row(
        modifier.fillMaxWidth().padding(bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Open community") { open(community.id) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StudentAvatar(community.name, size = 28.dp, accent = accentFor(community.id), imagePath = community.avatarUrl)
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(community.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val kind = if (community.isPrivate) "Private community" else "Public community"
                val members = "${"%,d".format(community.memberCount)} ${if (community.memberCount == 1) "member" else "members"}"
                Text(
                    listOfNotNull(kind, members, if (announcement) "Announcement" else null).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CommunityMembershipButton(community.id, community.membership, community.memberCount, compact = true)
    }
}
