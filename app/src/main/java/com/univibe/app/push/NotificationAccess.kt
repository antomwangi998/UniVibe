package com.univibe.app.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Whether Android currently lets UniVibe show notifications (permission on Android 13+, and the app-level switch on all versions). */
fun notificationsAllowedBySystem(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

/** The live answer to [notificationsAllowedBySystem]. It re-checks every time the screen comes back, so a change made in Android settings shows at once. */
@Composable
fun rememberNotificationsAllowed(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(notificationsAllowedBySystem(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.value = notificationsAllowedBySystem(context) }
    return state
}

fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}

fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * Device-only rules applied when a push arrives. The server already respects the notification preferences; quiet hours
 * are a local choice, so the phone enforces them. Security alerts are never silenced.
 */
object PushPolicy {
    const val QUIET_START_HOUR = 22
    const val QUIET_END_HOUR = 7

    fun inQuietHours(hourOfDay: Int) = hourOfDay >= QUIET_START_HOUR || hourOfDay < QUIET_END_HOUR

    fun shouldShow(event: PushEvent, quietHoursOn: Boolean, hourOfDay: Int): Boolean =
        event.channel == PushChannel.Security || !(quietHoursOn && inQuietHours(hourOfDay))

    /** With message previews off, a message alert says only that a message arrived. Other alerts are unchanged. */
    fun forDisplay(event: PushEvent, showMessagePreview: Boolean): PushEvent {
        val isMessage = event.channel == PushChannel.DirectMessages || event.channel == PushChannel.GroupMessages
        return if (isMessage && !showMessagePreview) event.copy(body = "New message") else event
    }

    /** Previews on and the message text was fetched: show who sent it and what it says. */
    fun withPreview(event: PushEvent, preview: MessagePreview?): PushEvent =
        if (preview == null) event else event.copy(title = preview.title, body = preview.body)
}
