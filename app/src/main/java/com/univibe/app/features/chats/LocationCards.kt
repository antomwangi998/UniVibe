package com.univibe.app.features.chats

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import com.univibe.app.data.model.SharedLocation
import com.univibe.app.ui.components.LocationFix
import com.univibe.app.ui.components.VenueMap
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.openInMaps
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.launch

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
fun ShareLocationSheet(recipient: String, onSend: (latitude: Double, longitude: Double) -> Unit, onDismiss: () -> Unit) {
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
                Text(
                    "$recipient will see this spot on a map. It's a one-time position that doesn't follow you, rounded to about 10 metres.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VibeButton("Send location", onClick = { onSend(p.latitude, p.longitude) }, modifier = Modifier.fillMaxWidth())
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
