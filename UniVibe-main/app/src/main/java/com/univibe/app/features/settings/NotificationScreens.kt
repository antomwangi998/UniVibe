package com.univibe.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.EventNotificationPrefsChange
import com.univibe.app.data.settings.NotificationPrefs
import com.univibe.app.data.settings.NotificationPrefsChange
import com.univibe.app.push.openAppNotificationSettings
import com.univibe.app.push.rememberNotificationsAllowed
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.theme.Spacing

/**
 * Notifications, split by decision. The hub shows each current value; the screens under it own one decision each.
 *
 * Two layers, and the screens keep them apart:
 *  - "Activity in your list" (server): what is created in your notification list at all.
 *  - "Phone alerts" (server) and Android's own permission: which of those also pop up on this phone.
 */

/** How many of the notification-list switches are on, for the hub's summary line. */
internal fun NotificationPrefs.activityOn(): Int =
    listOf(follows, likes, comments, messages, events, groups, communities, storyReactions).count { it }

internal const val ACTIVITY_SWITCH_COUNT = 8

@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onPhoneAlerts: () -> Unit,
    onActivity: () -> Unit,
    onEventNotices: () -> Unit,
    onQuietHours: () -> Unit,
    viewModel: NotificationPrefsViewModel = viewModel(),
    eventViewModel: EventNotificationPrefsViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val events by eventViewModel.state.collectAsStateWithLifecycle()
    val device by settingsViewModel.settings.collectAsStateWithLifecycle()
    val allowed by rememberNotificationsAllowed()
    val context = LocalContext.current
    // A child screen may have changed something; read the server's values again when we come back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    SettingsPage("Notifications", onBack) {
        if (!allowed) item { AndroidBlockedBanner() }
        when (val st = state) {
            NotificationPrefsState.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            is NotificationPrefsState.Failed -> item { ErrorState(st.message, onRetry = viewModel::load) }
            is NotificationPrefsState.Ready -> {
                val p = st.prefs
                item {
                    SettingsGroup {
                        SettingsNavRow(
                            VibeIcons.Bell, "Phone alerts", onPhoneAlerts,
                            subtitle = when {
                                !allowed -> "Blocked in Android settings"
                                p.pushEnabled -> "On"
                                else -> "Off"
                            },
                        )
                        RowDivider()
                        SettingsNavRow(
                            VibeIcons.Heart, "Activity in your list", onActivity,
                            subtitle = "${p.activityOn()} of $ACTIVITY_SWITCH_COUNT kinds on",
                        )
                        if (events.prefs != null) {
                            RowDivider()
                            SettingsNavRow(VibeIcons.Events, "Event notices", onEventNotices, subtitle = "Confirmations, reminders and changes")
                        }
                    }
                }
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(
                    VibeIcons.Moon, "Quiet hours", onQuietHours,
                    subtitle = if (device.quietHours) "On, 10 PM to 7 AM" else "Off",
                )
            }
        }
        item {
            VibeButton(
                if (allowed) "Open Android notification settings" else "Turn on in Android settings",
                onClick = { openAppNotificationSettings(context) },
                modifier = Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary,
                leadingIcon = VibeIcons.Settings,
            )
        }
        item { SettingsNote("Sound, vibration and the look of each kind of alert are set per channel in Android's notification settings.") }
    }
}

@Composable
private fun AndroidBlockedBanner() {
    MessageBanner(
        "Android is blocking UniVibe's notifications, so no alert will appear on this phone whatever you choose in the app.",
        BannerKind.Info,
    )
}

/** Shared frame for the server-backed notification screens: loading, failure with retry, save-error banner, then the rows. */
@Composable
private fun NotificationPrefsPage(
    title: String,
    onBack: () -> Unit,
    viewModel: NotificationPrefsViewModel,
    content: LazyListScope.(NotificationPrefsState.Ready) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsPage(title, onBack) {
        when (val st = state) {
            NotificationPrefsState.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            is NotificationPrefsState.Failed -> item { ErrorState(st.message, onRetry = viewModel::load) }
            is NotificationPrefsState.Ready -> {
                st.error?.let { item { MessageBanner(it, BannerKind.Error) } }
                content(this, st)
                item {
                    SettingsNote(if (st.synced) "Saved to your account." else "Preview build: these choices last for this session only.")
                }
            }
        }
    }
}

@Composable
fun PhoneAlertsScreen(onBack: () -> Unit, viewModel: NotificationPrefsViewModel = viewModel()) {
    val allowed by rememberNotificationsAllowed()
    val context = LocalContext.current
    NotificationPrefsPage("Phone alerts", onBack, viewModel) { st ->
        val p = st.prefs
        val busy = st.saving
        if (!allowed) {
            item { AndroidBlockedBanner() }
            item {
                VibeButton(
                    "Turn on in Android settings", onClick = { openAppNotificationSettings(context) },
                    modifier = Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary, leadingIcon = VibeIcons.Settings,
                )
            }
        }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    "Push notifications", p.pushEnabled, { v -> viewModel.change(NotificationPrefsChange(pushEnabled = v)) },
                    subtitle = if (allowed) "Alerts when UniVibe is closed. Account and security notices always come through."
                    else "Your choice is saved, but nothing appears on this phone until Android allows notifications.",
                    icon = VibeIcons.Bell, enabled = !busy,
                )
                RowDivider()
                SettingsSwitchRow("Messages", p.pushMessages, { v -> viewModel.change(NotificationPrefsChange(pushMessages = v)) },
                    enabled = p.pushEnabled && !busy, icon = VibeIcons.Comment)
                RowDivider()
                SettingsSwitchRow("Social activity", p.pushSocial, { v -> viewModel.change(NotificationPrefsChange(pushSocial = v)) },
                    subtitle = "Followers, likes, comments and replies.", enabled = p.pushEnabled && !busy, icon = VibeIcons.Heart)
                RowDivider()
                SettingsSwitchRow("Events", p.pushEvents, { v -> viewModel.change(NotificationPrefsChange(pushEvents = v)) },
                    enabled = p.pushEnabled && !busy, icon = VibeIcons.Events)
            }
        }
        item { SettingsNote("These only decide what also pops up on your phone. What appears in your notification list is set under Activity in your list.") }
    }
}

@Composable
fun NotificationActivityScreen(onBack: () -> Unit, viewModel: NotificationPrefsViewModel = viewModel()) =
    NotificationPrefsPage("Activity in your list", onBack, viewModel) { st ->
        val p = st.prefs
        val busy = st.saving
        item {
            SettingsGroup {
                SettingsSwitchRow("New followers", p.follows, { v -> viewModel.change(NotificationPrefsChange(follows = v)) }, enabled = !busy, icon = VibeIcons.Groups)
                RowDivider()
                SettingsSwitchRow("Likes", p.likes, { v -> viewModel.change(NotificationPrefsChange(likes = v)) }, enabled = !busy, icon = VibeIcons.Heart)
                RowDivider()
                SettingsSwitchRow("Comments and replies", p.comments, { v -> viewModel.change(NotificationPrefsChange(comments = v)) }, enabled = !busy, icon = VibeIcons.Comment)
                RowDivider()
                SettingsSwitchRow("Story reactions", p.storyReactions, { v -> viewModel.change(NotificationPrefsChange(storyReactions = v)) }, enabled = !busy, icon = VibeIcons.Sparkle)
                RowDivider()
                SettingsSwitchRow("Messages", p.messages, { v -> viewModel.change(NotificationPrefsChange(messages = v)) }, enabled = !busy, icon = VibeIcons.Comment)
                RowDivider()
                SettingsSwitchRow("Group invitations", p.groups, { v -> viewModel.change(NotificationPrefsChange(groups = v)) }, enabled = !busy, icon = VibeIcons.Groups)
                RowDivider()
                SettingsSwitchRow(
                    "Communities", p.communities, { v -> viewModel.change(NotificationPrefsChange(communities = v)) },
                    subtitle = "Join requests, invitations and announcements. Role changes and removals always come through.",
                    enabled = !busy, icon = VibeIcons.Groups,
                )
                RowDivider()
                SettingsSwitchRow("Event updates", p.events, { v -> viewModel.change(NotificationPrefsChange(events = v)) }, enabled = !busy, icon = VibeIcons.Events)
            }
        }
        item { SettingsNote("Turning something off here stops it being created at all, so it won't appear in your list or reach your phone.") }
    }

@Composable
fun EventNoticesScreen(
    onBack: () -> Unit,
    viewModel: NotificationPrefsViewModel = viewModel(),
    eventViewModel: EventNotificationPrefsViewModel = viewModel(),
) {
    val general by viewModel.state.collectAsStateWithLifecycle()
    val st by eventViewModel.state.collectAsStateWithLifecycle()
    val p = st.prefs
    val busy = st.saving
    // The finer switches apply on top of the general Events switch, never instead of it.
    val eventsOn = (general as? NotificationPrefsState.Ready)?.prefs?.events ?: true
    SettingsPage("Event notices", onBack) {
        if (p == null) {
            item {
                EmptyState(
                    icon = VibeIcons.Events,
                    title = "Event notices aren't available",
                    message = "These choices can't be loaded right now. Try again later.",
                )
            }
            return@SettingsPage
        }
        if (!eventsOn) item { MessageBanner("Event updates are off under Activity in your list, so none of these will reach you.", BannerKind.Info) }
        item {
            SettingsGroup {
                val on = eventsOn && !busy
                SettingsSwitchRow("Registration confirmations", p.confirmations, { v -> eventViewModel.change(EventNotificationPrefsChange(confirmations = v)) }, enabled = on, icon = VibeIcons.Events)
                RowDivider()
                SettingsSwitchRow("Reminders", p.reminders, { v -> eventViewModel.change(EventNotificationPrefsChange(reminders = v)) },
                    subtitle = "For events you are going to. You can also silence one event on its page.", enabled = on, icon = VibeIcons.Events)
                RowDivider()
                SettingsSwitchRow("24 hours before", 1440 in p.reminderOffsets, { eventViewModel.toggleOffset(1440) }, enabled = on && p.reminders)
                RowDivider()
                SettingsSwitchRow("1 hour before", 60 in p.reminderOffsets, { eventViewModel.toggleOffset(60) }, enabled = on && p.reminders)
                RowDivider()
                SettingsSwitchRow("Time and place changes", p.changes, { v -> eventViewModel.change(EventNotificationPrefsChange(changes = v)) }, enabled = on, icon = VibeIcons.Events)
                RowDivider()
                SettingsSwitchRow("Cancellations", p.cancellations, { v -> eventViewModel.change(EventNotificationPrefsChange(cancellations = v)) }, enabled = on, icon = VibeIcons.Events)
                RowDivider()
                SettingsSwitchRow("New events in my communities", p.community, { v -> eventViewModel.change(EventNotificationPrefsChange(community = v)) },
                    subtitle = "In the app only, and only for communities where you chose more than mentions.", enabled = on, icon = VibeIcons.Groups)
                RowDivider()
                SettingsSwitchRow("Registrations for my events", p.organizerUpdates, { v -> eventViewModel.change(EventNotificationPrefsChange(organizerUpdates = v)) },
                    subtitle = "When you get your first, at a few milestones, and when an event fills.", enabled = on, icon = VibeIcons.Events)
            }
        }
        st.error?.let { item { SettingsNote(it) } }
    }
}

@Composable
fun QuietHoursScreen(onBack: () -> Unit, settingsViewModel: SettingsViewModel = viewModel()) {
    val device by settingsViewModel.settings.collectAsStateWithLifecycle()
    SettingsPage("Quiet hours", onBack) {
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    "Quiet hours", device.quietHours, { v -> settingsViewModel.update { it.copy(quietHours = v) } },
                    subtitle = "No alerts on this phone from 10 PM to 7 AM.", icon = VibeIcons.Moon,
                )
            }
        }
        item {
            SettingsNote("Security notices still come through. Quiet hours only affect alerts on this phone, not your notification list, and the times follow this phone's clock and can't be changed yet.")
        }
    }
}
