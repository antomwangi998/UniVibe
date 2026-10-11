package com.univibe.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.RsvpState
import com.univibe.app.ui.theme.Spacing

@Composable
fun EventCard(
    event: CampusEvent,
    onRsvpClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    VibeCard(modifier = modifier.animateContentSize(), contentPadding = PaddingValues(0.dp), elevation = 2.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Brush.linearGradient(accentGradient(event.accent))),
        ) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(Spacing.md),
                shape = RoundedCornerShape(14.dp),
                color = colors.surface,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(event.month, style = MaterialTheme.typography.labelSmall, color = colors.primary)
                    Text(event.day, style = MaterialTheme.typography.titleLarge)
                }
            }
            val (tagBg, tagFg) = when (event.category) {
                "Party" -> colors.primaryContainer to colors.onPrimaryContainer
                "Sports" -> colors.tertiaryContainer to colors.onTertiaryContainer
                "Workshop" -> colors.secondaryContainer to colors.onSecondaryContainer
                else -> colors.surfaceVariant to colors.onSurfaceVariant
            }
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.md),
                shape = CircleShape, color = tagBg, contentColor = tagFg,
            ) {
                Text(event.category, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(event.title, style = MaterialTheme.typography.titleMedium)
            MetaRow(Icons.Outlined.Schedule, event.timeLabel)
            MetaRow(Icons.Outlined.Place, event.location)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaRow(Icons.Outlined.Groups, "${event.going} going · ${event.interested} interested", Modifier.weight(1f))
                Spacer(Modifier.width(Spacing.sm))
                when {
                    event.isPast -> Text("Ended", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    event.rsvp == RsvpState.Going ->
                        VibeButton("Going", onRsvpClick, style = VibeButtonStyle.Secondary, compact = true, leadingIcon = Icons.Outlined.Check)
                    event.rsvp == RsvpState.Interested ->
                        VibeButton("Interested", onRsvpClick, style = VibeButtonStyle.Secondary, compact = true, leadingIcon = Icons.Outlined.StarOutline)
                    else -> VibeButton("RSVP", onRsvpClick, compact = true)
                }
            }
        }
    }
}

@Composable
private fun MetaRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
