package com.univibe.app.features.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.univibe.app.push.openAppDetailsSettings
import com.univibe.app.push.openAppNotificationSettings
import com.univibe.app.push.rememberNotificationsAllowed
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.theme.Spacing

private const val PUSH_PREFS = "push"
private const val KEY_ASKED = "asked_notification_permission" // the same flag the sign-in prompt uses

/**
 * Settings → Permissions. Lists only what UniVibe really uses: notifications, and the microphone for voice messages.
 * Photos go through Android's photo picker, which needs no permission. The microphone is only requested in a chat,
 * at the moment you start recording; this screen shows its state and opens Android settings, it doesn't ask.
 */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val allowed by rememberNotificationsAllowed()
    val micAllowed by rememberMicrophoneAllowed()
    val prefs = context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)
    val canAskHere = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !allowed && !prefs.getBoolean(KEY_ASKED, false)
    // The result is picked up by rememberNotificationsAllowed when the screen resumes.
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    SettingsPage("Permissions", onBack) {
        item {
            SettingsGroup {
                PermissionBlock(
                    title = "Notifications",
                    status = if (allowed) "Allowed" else "Blocked",
                    why = "Lets UniVibe alert you to new messages, activity on your posts and event updates.",
                    dependsOn = "Push notifications and message alerts.",
                ) {
                    if (canAskHere) {
                        VibeButton("Allow notifications", onClick = {
                            prefs.edit().putBoolean(KEY_ASKED, true).apply()
                            request.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }, style = VibeButtonStyle.Secondary)
                    } else {
                        VibeButton(
                            if (allowed) "Manage in Android settings" else "Turn on in Android settings",
                            onClick = { openAppNotificationSettings(context) }, style = VibeButtonStyle.Secondary,
                        )
                    }
                }
                RowDivider()
                PermissionBlock(
                    title = "Microphone",
                    status = if (micAllowed) "Allowed" else "Not allowed",
                    why = "Used only while you are recording a voice message. UniVibe asks the first time you tap to record, and never listens at any other time.",
                    dependsOn = "Voice messages in chats.",
                ) {
                    VibeButton(
                        if (micAllowed) "Manage in Android settings" else "Change in Android settings",
                        onClick = { openAppDetailsSettings(context) }, style = VibeButtonStyle.Secondary,
                    )
                }
                RowDivider()
                PermissionBlock(
                    title = "Photos and videos",
                    status = "No permission needed",
                    why = "When you add a photo, Android's photo picker opens and you choose each one. UniVibe only receives the photos you pick, and can't browse your gallery.",
                    dependsOn = "Profile photos and photos in posts and messages.",
                ) {}
                RowDivider()
                PermissionBlock(
                    title = "Location",
                    status = "Asked only when you use it",
                    why = "UniVibe reads your position only when you tap Send location or Use my location. While you share a live location, a notification with a Stop button stays visible until you stop.",
                    dependsOn = "Sending your location in messages and sharing a live location.",
                ) {}
            }
        }
        item {
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.md)) {
                    Text("Not used", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "UniVibe doesn't use your camera, contacts or calendar, and can't be given access to them.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
        }
        item {
            VibeButton("Open UniVibe's Android settings", onClick = { openAppDetailsSettings(context) },
                modifier = Modifier.fillMaxWidth(), style = VibeButtonStyle.Text)
        }
        item { SettingsNote("Android can change a permission at any time, including when you haven't used the app for a while. This screen shows the current state each time you open it.") }
    }
}

@Composable
private fun PermissionBlock(title: String, status: String, why: String, dependsOn: String, action: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
        Text("Used for: $dependsOn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
        Column(Modifier.padding(top = Spacing.sm)) { action() }
    }
}

/** Live microphone permission state; re-checked whenever the screen returns from Android settings. */
@Composable
private fun rememberMicrophoneAllowed(): State<Boolean> {
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val state = remember { mutableStateOf(granted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.value = granted() }
    return state
}
