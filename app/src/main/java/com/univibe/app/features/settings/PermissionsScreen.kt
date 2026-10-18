package com.univibe.app.features.settings

import android.Manifest
import android.content.Context
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
import androidx.compose.runtime.getValue
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
 * Settings → Permissions. Lists only what UniVibe really uses: it declares just the notification permission.
 * Photos go through Android's photo picker, which needs no permission, so nothing else is listed as "needed".
 */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val allowed by rememberNotificationsAllowed()
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
                    title = "Photos and videos",
                    status = "No permission needed",
                    why = "When you add a photo, Android's photo picker opens and you choose each one. UniVibe only receives the photos you pick, and can't browse your gallery.",
                    dependsOn = "Profile photos and photos in posts and messages.",
                ) {}
            }
        }
        item {
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.md)) {
                    Text("Not used", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "UniVibe doesn't use your camera, microphone, contacts, location or calendar, and can't be given access to them.",
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
