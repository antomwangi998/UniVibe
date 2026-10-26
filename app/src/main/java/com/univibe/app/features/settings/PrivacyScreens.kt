package com.univibe.app.features.settings

import com.univibe.app.ui.components.VibeIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.theme.Spacing
import androidx.compose.ui.unit.dp

/**
 * The Privacy & safety hub. It only links to the screens where each choice is made and shows each current value.
 * The values come from the local copy of the server settings (kept in step by PrivacyRepository after every load and
 * save), so the hub opens instantly and is up to date when you come back from a child screen.
 */
@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    onVisibility: () -> Unit,
    onMessages: () -> Unit,
    onActivityStatus: () -> Unit,
    onBlocked: () -> Unit,
    onSafety: () -> Unit,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val s by settingsViewModel.settings.collectAsStateWithLifecycle()
    SettingsPage("Privacy & safety", onBack) {
        item {
            SettingsGroup {
                SettingsNavRow(
                    VibeIcons.Visibility, "Visibility", onVisibility,
                    subtitle = if (s.discoverable) "Shown in Discover" else "Hidden from Discover",
                )
                RowDivider()
                SettingsNavRow(
                    VibeIcons.Chats, "Messages", onMessages,
                    subtitle = "Who can message you: ${s.messagePolicy.label}",
                )
                RowDivider()
                SettingsNavRow(
                    VibeIcons.Sensors, "Activity status", onActivityStatus,
                    subtitle = if (s.showOnline) "Online status is shown" else "Online status is hidden",
                )
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(
                    VibeIcons.Block, "Blocked students", onBlocked,
                    subtitle = if (s.blocked.isEmpty()) "No one blocked" else "${s.blocked.size} blocked",
                )
                RowDivider()
                SettingsNavRow(VibeIcons.Shield, "Safety tips", onSafety)
            }
        }
        item { SettingsNote("UniVibe never collects your precise location.") }
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
