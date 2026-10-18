package com.univibe.app.features.settings

import com.univibe.app.ui.components.VibeIcons
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.settings.NotificationPrefsChange
import com.univibe.app.data.settings.TextScale
import com.univibe.app.push.rememberNotificationsAllowed
import com.univibe.app.push.openAppNotificationSettings
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.theme.Spacing

private fun openSystem(context: Context, action: String, withPackage: Boolean = false) {
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (withPackage) intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}

@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    viewModel: NotificationPrefsViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val device by settingsViewModel.settings.collectAsStateWithLifecycle()
    val allowed by rememberNotificationsAllowed()
    val context = LocalContext.current
    SettingsPage("Notifications", onBack) {
        if (!allowed) item {
            MessageBanner("Android is blocking UniVibe's notifications, so no alert will appear on this phone whatever you choose below.", BannerKind.Info)
        }
        when (val st = state) {
            NotificationPrefsState.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            is NotificationPrefsState.Failed -> item { ErrorState(st.message, onRetry = viewModel::load) }
            is NotificationPrefsState.Ready -> {
                val p = st.prefs
                val busy = st.saving
                st.error?.let { item { MessageBanner(it, BannerKind.Error) } }
                item {
                    SettingsGroup {
                        SettingsSectionTitle("Alerts on your phone")
                        SettingsSwitchRow("Push notifications", p.pushEnabled, { v -> viewModel.change(NotificationPrefsChange(pushEnabled = v)) },
                            subtitle = "Alerts when UniVibe is closed. Account and security notices always come through.",
                            icon = VibeIcons.Bell, enabled = !busy)
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
                item {
                    SettingsGroup {
                        SettingsSectionTitle("Notification list")
                        SettingsSwitchRow("New followers", p.follows, { v -> viewModel.change(NotificationPrefsChange(follows = v)) }, enabled = !busy, icon = VibeIcons.Groups)
                        RowDivider()
                        SettingsSwitchRow("Likes", p.likes, { v -> viewModel.change(NotificationPrefsChange(likes = v)) }, enabled = !busy, icon = VibeIcons.Heart)
                        RowDivider()
                        SettingsSwitchRow("Comments and replies", p.comments, { v -> viewModel.change(NotificationPrefsChange(comments = v)) }, enabled = !busy, icon = VibeIcons.Comment)
                        RowDivider()
                        SettingsSwitchRow("Messages", p.messages, { v -> viewModel.change(NotificationPrefsChange(messages = v)) }, enabled = !busy, icon = VibeIcons.Comment)
                        RowDivider()
                        SettingsSwitchRow("Event updates", p.events, { v -> viewModel.change(NotificationPrefsChange(events = v)) }, enabled = !busy, icon = VibeIcons.Events)
                        RowDivider()
                        SettingsSwitchRow("Group invitations", p.groups, { v -> viewModel.change(NotificationPrefsChange(groups = v)) }, enabled = !busy, icon = VibeIcons.Groups)
                    }
                }
                item {
                    SettingsNote(
                        (if (st.synced) "Saved to your account. Turning something off in the notification list stops it being created at all; "
                        else "Preview build: these choices last for this session only. ") +
                            "the phone alerts above only decide what also pops up on your phone.",
                    )
                }
            }
        }
        item {
            SettingsGroup {
                SettingsSwitchRow("Quiet hours", device.quietHours, { v -> settingsViewModel.update { it.copy(quietHours = v) } },
                    subtitle = "On this phone, no alerts from 10 PM to 7 AM (security notices excepted).", icon = VibeIcons.Moon)
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
fun AccessibilityScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsPage("Accessibility", onBack) {
        item {
            SettingsGroup {
                SettingsSectionTitle("Text size")
                TextScale.entries.forEach { scale ->
                    SettingsRadioRow(scale.label, null, s.textScale == scale) { viewModel.update { it.copy(textScale = scale) } }
                }
            }
        }
        item {
            VibeCard {
                Text("Preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Great session today on AI & innovation!", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Spacing.xs))
            }
        }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    "Reduce motion", s.reduceMotion, { v -> viewModel.update { it.copy(reduceMotion = v) } },
                    subtitle = "Calmer transitions and no looping animations.", icon = VibeIcons.Sparkle,
                )
            }
        }
        item {
            VibeButton(
                "Open system accessibility settings",
                onClick = { openSystem(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                modifier = Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary,
                leadingIcon = VibeIcons.Accessibility,
            )
        }
        item { SettingsNote("UniVibe also follows your phone's font size, display size and animation settings.") }
    }
}
