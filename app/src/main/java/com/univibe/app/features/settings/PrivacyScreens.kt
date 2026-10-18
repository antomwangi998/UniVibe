package com.univibe.app.features.settings

import com.univibe.app.ui.components.VibeIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.CircularProgressIndicator
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
import com.univibe.app.data.settings.PrivacyChange
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.MessageBanner
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
    viewModel: PrivacyViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s by settingsViewModel.settings.collectAsStateWithLifecycle()
    SettingsPage("Privacy & safety", onBack) {
        when (val st = state) {
            PrivacyState.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            is PrivacyState.Failed -> item { ErrorState(st.message, onRetry = viewModel::load) }
            is PrivacyState.Ready -> {
                val p = st.privacy
                st.error?.let { item { MessageBanner(it, BannerKind.Error) } }
                item {
                    SettingsGroup {
                        SettingsSectionTitle("Visibility")
                        SettingsSwitchRow("Show me in Discover", p.discoverable, { v -> viewModel.change(PrivacyChange(discoverable = v)) },
                            subtitle = "Other students can find your profile.", icon = VibeIcons.Visibility, enabled = !st.saving)
                        RowDivider()
                        SettingsNavRow(VibeIcons.Photo, "Discover profile", onDiscoverProfile, subtitle = "Choose your card photo and preview it.")
                        RowDivider()
                        SettingsSwitchRow("Show online status", p.showOnline, { v -> viewModel.change(PrivacyChange(showOnline = v)) },
                            subtitle = "Let others see when you're active.", icon = Icons.Outlined.Sensors, enabled = !st.saving)
                    }
                }
                item {
                    SettingsGroup {
                        SettingsSectionTitle("Who can message you")
                        MessagePolicy.entries.forEach { policy ->
                            SettingsRadioRow(policy.label, policy.description, p.messagePolicy == policy) {
                                if (!st.saving && p.messagePolicy != policy) viewModel.change(PrivacyChange(messagePolicy = policy))
                            }
                        }
                        RowDivider()
                        SettingsSwitchRow("Read receipts", p.readReceipts, { v -> viewModel.change(PrivacyChange(readReceipts = v)) },
                            subtitle = if (p.readReceipts) "People can see when you've read their message." else "People can't see when you've read their message, and you can't see when they've read yours.",
                            icon = VibeIcons.DoubleCheck, enabled = !st.saving)
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
                        if (st.synced) "These choices are saved to your account, so they apply on every device and to what other students can see. UniVibe never collects your precise location."
                        else "Preview build: these choices are saved on this device only. UniVibe never collects your precise location.",
                    )
                }
            }
        }
    }
}

@Composable
fun BlockedUsersScreen(onBack: () -> Unit, viewModel: BlockedUsersViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsPage("Blocked students", onBack) {
        when (val st = state) {
            BlockedState.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            is BlockedState.Failed -> item { ErrorState(st.message, onRetry = viewModel::load) }
            is BlockedState.Ready -> {
                st.error?.let { item { MessageBanner(it, BannerKind.Error) } }
                if (st.users.isEmpty()) {
                    item {
                        EmptyState(
                            icon = VibeIcons.Groups,
                            title = "No one is blocked",
                            message = "Blocked students can't see you, follow you or message you, and you won't see them. Block someone from the ⋯ menu on their post or profile.",
                        )
                    }
                } else {
                    item {
                        SettingsGroup {
                            st.users.forEachIndexed { index, user ->
                                if (index > 0) RowDivider()
                                Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                    StudentAvatar(user.name, size = 44.dp)
                                    Text(user.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(horizontal = Spacing.md))
                                    TextButton(onClick = { viewModel.unblock(user.id) }, enabled = st.working == null) { Text("Unblock") }
                                }
                            }
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
