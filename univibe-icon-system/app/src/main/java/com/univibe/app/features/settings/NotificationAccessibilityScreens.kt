package com.univibe.app.features.settings

import com.univibe.app.ui.components.VibeIcons
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.settings.TextScale
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
fun NotificationsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val on = s.notificationsEnabled
    SettingsPage("Notifications", onBack) {
        item {
            SettingsGroup {
                SettingsSwitchRow("Allow notifications", on, { v -> viewModel.update { it.copy(notificationsEnabled = v) } },
                    subtitle = "Turn everything off or choose below.", icon = VibeIcons.Bell)
            }
        }
        item {
            SettingsGroup {
                SettingsSectionTitle("Notify me about")
                SettingsSwitchRow("Likes", s.notifyLikes, { v -> viewModel.update { it.copy(notifyLikes = v) } }, enabled = on, icon = VibeIcons.Heart)
                RowDivider()
                SettingsSwitchRow("Comments", s.notifyComments, { v -> viewModel.update { it.copy(notifyComments = v) } }, enabled = on, icon = VibeIcons.Comment)
                RowDivider()
                SettingsSwitchRow("Messages", s.notifyMessages, { v -> viewModel.update { it.copy(notifyMessages = v) } }, enabled = on, icon = VibeIcons.Comment)
                RowDivider()
                SettingsSwitchRow("Events & reminders", s.notifyEvents, { v -> viewModel.update { it.copy(notifyEvents = v) } }, enabled = on, icon = VibeIcons.Events)
                RowDivider()
                SettingsSwitchRow("Communities", s.notifyCommunities, { v -> viewModel.update { it.copy(notifyCommunities = v) } }, enabled = on, icon = VibeIcons.Groups)
            }
        }
        item {
            SettingsGroup {
                SettingsSwitchRow("Quiet hours", s.quietHours, { v -> viewModel.update { it.copy(quietHours = v) } },
                    subtitle = "Pause alerts from 10 PM to 7 AM.", enabled = on, icon = Icons.Outlined.Bedtime)
                RowDivider()
                SettingsSwitchRow("Weekly email digest", s.emailDigest, { v -> viewModel.update { it.copy(emailDigest = v) } },
                    subtitle = "A summary of what you missed.", icon = VibeIcons.Mail)
            }
        }
        item {
            VibeButton(
                "Open system notification settings",
                onClick = { openSystem(context, Settings.ACTION_APP_NOTIFICATION_SETTINGS, withPackage = true) },
                modifier = Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary,
                leadingIcon = VibeIcons.Settings,
            )
        }
        item { SettingsNote("Your choices are saved now. Real push notifications arrive once UniVibe has a server to send them.") }
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
                    subtitle = "Calmer transitions and no looping animations.", icon = Icons.Outlined.Animation,
                )
            }
        }
        item {
            VibeButton(
                "Open system accessibility settings",
                onClick = { openSystem(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                modifier = Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary,
                leadingIcon = Icons.Outlined.Accessibility,
            )
        }
        item { SettingsNote("UniVibe also follows your phone's font size, display size and animation settings.") }
    }
}
