package com.univibe.app.features.settings

import com.univibe.app.ui.components.VibeIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.settings.MessagePolicy
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.theme.Spacing
import androidx.compose.ui.unit.dp

@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    onBlocked: () -> Unit,
    onSafety: () -> Unit,
    onDiscoverProfile: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage("Privacy & safety", onBack) {
        item {
            SettingsGroup {
                SettingsSectionTitle("Visibility")
                SettingsSwitchRow("Show me in Discover", s.discoverable, { v -> viewModel.update { it.copy(discoverable = v) } },
                    subtitle = "Other students can find your profile.", icon = VibeIcons.Visibility)
                RowDivider()
                SettingsNavRow(VibeIcons.Photo, "Discover profile", onDiscoverProfile, subtitle = "Choose your card photo and preview it.")
                RowDivider()
                SettingsSwitchRow("Show online status", s.showOnline, { v -> viewModel.update { it.copy(showOnline = v) } },
                    subtitle = "Let others see when you're active.", icon = Icons.Outlined.Sensors)
            }
        }
        item {
            SettingsGroup {
                SettingsSectionTitle("Who can message you")
                MessagePolicy.entries.forEach { policy ->
                    SettingsRadioRow(policy.label, policy.description, s.messagePolicy == policy) {
                        viewModel.update { it.copy(messagePolicy = policy) }
                    }
                }
                RowDivider()
                SettingsSwitchRow("Read receipts", s.showReadReceipts, { v -> viewModel.update { it.copy(showReadReceipts = v) } },
                    subtitle = "Show when you've seen a message.", icon = VibeIcons.DoubleCheck)
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(
                    VibeIcons.Block, "Blocked students", onBlocked,
                    subtitle = if (s.blocked.isEmpty()) "No one blocked" else "${s.blocked.size} blocked",
                )
                RowDivider()
                SettingsNavRow(Icons.Outlined.Security, "Safety tips", onSafety)
            }
        }
        item {
            SettingsNote(
                "UniVibe never collects your precise location. Choices here are saved on this device for now; " +
                    "the server must enforce them once it exists.",
            )
        }
    }
}

@Composable
fun BlockedUsersScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage("Blocked students", onBack) {
        if (s.blocked.isEmpty()) {
            item {
                EmptyState(
                    icon = VibeIcons.Groups,
                    title = "No one is blocked",
                    message = "Blocked students can't appear in your feed or discover you. Block someone from the ⋯ menu on their post.",
                )
            }
        } else {
            item {
                SettingsGroup {
                    s.blocked.forEachIndexed { index, user ->
                        if (index > 0) RowDivider()
                        Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            StudentAvatar(user.name, size = 44.dp)
                            Text(user.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(horizontal = Spacing.md))
                            TextButton(onClick = { viewModel.unblock(user.id) }) { Text("Unblock") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SafetyScreen(onBack: () -> Unit) {
    SettingsPage("Safety tips", onBack) {
        item {
            SettingsGroup {
                listOf(
                    "Meet in public" to "When meeting someone from UniVibe for the first time, choose a busy campus spot and tell a friend.",
                    "Protect your details" to "Don't share passwords, codes, your room number or your daily routine with people you've just met.",
                    "Trust your instincts" to "If a chat feels uncomfortable, stop replying. You can block and report any student.",
                    "Watch for scams" to "UniVibe staff will never ask for your password or a verification code.",
                    "Report problems" to "Use ⋯ on a post to report it. For urgent danger, contact campus security or local emergency services.",
                ).forEachIndexed { index, (title, body) ->
                    if (index > 0) RowDivider()
                    Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
                    }
                }
            }
        }
    }
}
