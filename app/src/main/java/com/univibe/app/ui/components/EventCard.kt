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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.ui.theme.Spacing

@Composable
fun EventCard(
    event: CampusEvent,
    onRsvpClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VibeCard(modifier = modifier.animateContentSize(), contentPadding = PaddingValues(0.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Brush.linearGradient(accentGradient(event.accent))),
        ) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(Spacing.md),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(event.month, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(event.day, style = MaterialTheme.typography.titleLarge)
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.md),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                contentColor = Color.White,
            ) {
                Text(event.category, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(event.title, style = MaterialTheme.typography.titleMedium)
            MetaRow({ Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp)) }, event.timeLabel)
            MetaRow({ Icon(Icons.Outlined.Place, null, Modifier.size(16.dp)) }, event.location)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaRow({ Icon(Icons.Outlined.Groups, null, Modifier.size(16.dp)) }, "${event.going} going")
                Spacer(Modifier.weight(1f))
                if (event.rsvped) {
                    VibeButton("Going", onRsvpClick, style = VibeButtonStyle.Secondary, compact = true, leadingIcon = Icons.Outlined.Check)
                } else {
                    VibeButton("I'm going", onRsvpClick, compact = true)
                }
            }
        }
    }
}

@Composable
private fun MetaRow(icon: @Composable () -> Unit, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
        ) { icon() }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
