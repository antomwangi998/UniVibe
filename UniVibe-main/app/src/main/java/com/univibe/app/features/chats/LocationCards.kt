package com.univibe.app.features.chats

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import com.univibe.app.ui.components.InterestChip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Message
import com.univibe.app.data.model.SharedLocation
import com.univibe.app.ui.components.LocationFix
import com.univibe.app.ui.components.VenueMap
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.openInMaps
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** A shared place inside a chat bubble: a small map; a tap opens the person's maps app. */
@Composable
fun SharedLocationCard(location: SharedLocation, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val name = location.label?.takeIf { it.isNotBlank() } ?: "Shared location"
    Column(modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        VenueMap(location.latitude, location.longitude, name, onClick = { openInMaps(context, location.latitude, location.longitude, name) }, height = 140.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(VibeIcons.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private sealed interface Phase {
    data object Locating : Phase
    data class Ready(val latitude: Double, val longitude: Double) : Phase
    data object Denied : Phase
    data class Failed(val servicesOn: Boolean) : Phase
}

/**
 * "Send your current location": asks for permission (only now), finds one position, shows it on a map, and sends
 * only when the person taps Send. Nothing is shared before that and nothing keeps updating afterwards.
 */
@Composable
fun ShareLocationSheet(
    recipient: String,
    onSend: (latitude: Double, longitude: Double) -> Unit,
    onSendLive: (latitude: Double, longitude: Double, minutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var minutes by remember { mutableStateOf(0) } // 0 = just this spot
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.Locating) }
    fun locate() {
        phase = Phase.Locating
        scope.launch {
            val fix = LocationFix.current(context)
            phase = if (fix != null) Phase.Ready(fix.latitude, fix.longitude) else Phase.Failed(LocationFix.servicesOn(context))
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else phase = Phase.Denied
    }
    LaunchedEffect(Unit) { if (LocationFix.hasPermission(context)) locate() else permission.launch(LocationFix.permissions) }

    VibeBottomSheet(onDismiss = onDismiss) {
        Text("Send your location", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = Spacing.sm))
        when (val p = phase) {
            Phase.Locating -> Row(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("Finding you…", Modifier.padding(start = Spacing.md), style = MaterialTheme.typography.bodyMedium)
            }
            is Phase.Ready -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                VenueMap(p.latitude, p.longitude, "Your location", onClick = {}, height = 200.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf(0 to "Just this spot", 15 to "Live · 15 min", 60 to "Live · 1 hour", 480 to "Live · 8 hours").forEach { (m, label) ->
                        InterestChip(label, selected = minutes == m, onClick = { minutes = m })
                    }
                }
                Text(
                    if (minutes == 0) "$recipient will see this spot on a map. It's a one-time position that doesn't follow you, rounded to about 10 metres."
                    else "$recipient will see you move on the map until the time is up. You can stop any time, and Android shows a notification while it runs. Only people in this chat can see it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VibeButton(
                    if (minutes == 0) "Send location" else "Share live location",
                    onClick = { if (minutes == 0) onSend(p.latitude, p.longitude) else onSendLive(p.latitude, p.longitude, minutes) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Phase.Denied -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("UniVibe needs permission to see where you are. It only looks when you tap this, and never in the background.", style = MaterialTheme.typography.bodyMedium)
                VibeButton("Open settings", onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, style = VibeButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
            }
            is Phase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    if (p.servicesOn) "Couldn't find your location. Try again with a clearer view of the sky." else "Location is switched off on your phone. Turn it on and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                VibeButton("Try again", onClick = { locate() }, style = VibeButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * A place shared live: a map that moves while the share is on, and a plain "ended" line afterwards (the server wipes the
 * position when sharing stops, so there is nothing left to show). The sender gets a Stop button.
 */
@Composable
fun LiveLocationCard(message: Message, modifier: Modifier = Modifier) {
    val live = message.live ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Flips to "ended" on its own when the time runs out, even if no update arrives.
    val now by produceState(System.currentTimeMillis(), live.endsAtMillis) {
        val wait = live.endsAtMillis - value
        if (wait > 0) delay(wait)
        value = System.currentTimeMillis()
    }
    val location = message.location
    val active = live.active && now < live.endsAtMillis && location != null
    Column(modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (active && location != null) {
            VenueMap(location.latitude, location.longitude, "Live location", onClick = { openInMaps(context, location.latitude, location.longitude, "Live location") }, height = 140.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(VibeIcons.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Column {
                    Text("Live location", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(live.endsAtMillis))}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (message.isMine) {
                VibeButton("Stop sharing", onClick = {
                    scope.launch {
                        runCatching { AppContainer.messageRepository.stopLiveLocation(message.id) }
                        LiveLocationService.stop(context)
                    }
                }, style = VibeButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(VibeIcons.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Live location ended", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
