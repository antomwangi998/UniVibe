package com.univibe.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.CommunityPrivacy
import com.univibe.app.ui.theme.Spacing

/** Why Discover suggests a community. Only reasons the server computed from real data; anything else shows nothing. */
fun communityReasonLabel(code: String?): String? = when (code) {
    "interests" -> "Matches your interests"
    "active" -> "Active recently"
    else -> null
}

/**
 * A community as a card: avatar, name, category, visibility, member count, description and the shared membership
 * control. Used by Discover and search, so a community looks and behaves the same wherever it appears.
 */
@Composable
fun CommunityCard(
    community: Community,
    modifier: Modifier = Modifier,
    reason: String? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Open community", onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            StudentAvatar(community.name, size = 44.dp, accent = community.accent, imagePath = community.avatarUrl)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(community.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val kind = if (community.privacy == CommunityPrivacy.Private) "Private" else "Public"
                val members = "${"%,d".format(community.members)} ${if (community.members == 1) "member" else "members"}"
                Text(
                    listOf(community.category, kind, members).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (community.description.isNotBlank()) {
                    Text(
                        community.description, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
                if (reason != null) {
                    Text(reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Spacing.xs))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.End) {
            CommunityMembershipButton(community.id, community.effectiveMembership, community.members, compact = true)
        }
    }
}
