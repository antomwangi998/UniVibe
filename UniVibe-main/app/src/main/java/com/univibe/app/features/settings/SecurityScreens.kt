package com.univibe.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.settings.LoginSession
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.theme.Spacing

@Composable
fun SecurityScreen(onBack: () -> Unit, onPassword: () -> Unit, onLoginActivity: () -> Unit) {
    SettingsPage("Security", onBack) {
        item {
            SettingsGroup {
                SettingsNavRow(VibeIcons.Lock, "Password", onPassword, subtitle = "Changing it logs out your other devices")
                RowDivider()
                SettingsNavRow(VibeIcons.Devices, "Login activity", onLoginActivity, subtitle = "Where you're logged in")
            }
        }
        item { SettingsNote("Two-factor authentication isn't available yet.") }
    }
}

@Composable
fun LoginActivityScreen(onBack: () -> Unit, viewModel: LoginActivityViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmOthers by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<LoginSession?>(null) }

    SettingsPage("Login activity", onBack) {
        when (val s = state) {
            LoginActivityState.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            LoginActivityState.Unavailable -> item {
                EmptyState(VibeIcons.Info, "Not available in the preview build", "Login activity comes from the UniVibe server, which this build isn't connected to.")
            }
            is LoginActivityState.Failed -> item { ErrorState(s.message, onRetry = viewModel::load) }
            is LoginActivityState.Ready -> {
                s.notice?.let { item { MessageBanner(it, BannerKind.Info) } }
                s.error?.let { item { MessageBanner(it, BannerKind.Error) } }
                if (!s.currentKnown) item {
                    MessageBanner("Log out and log in again on this device to tell it apart from the others and manage them.", BannerKind.Info)
                }
                item {
                    SettingsGroup {
                        s.sessions.forEachIndexed { i, session ->
                            if (i > 0) RowDivider()
                            SessionRow(session, enabled = !s.working, onEnd = { confirmOne = session })
                        }
                    }
                }
                if (s.currentKnown && s.others.isNotEmpty()) item {
                    VibeButton(
                        "Log out of other devices", onClick = { confirmOthers = true },
                        modifier = Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary, enabled = !s.working, loading = s.working,
                    )
                }
                item { SettingsNote("Shows the devices UniVibe can tell apart, with when each logged in and last refreshed. UniVibe doesn't record where a login came from.") }
            }
        }
    }

    if (confirmOthers) {
        ConfirmDialog(
            title = "Log out of other devices?",
            message = "Every other device will be logged out right away. This device stays logged in.",
            confirmLabel = "Log out others", destructive = true,
            onConfirm = { confirmOthers = false; viewModel.endOthers() },
            onDismiss = { confirmOthers = false },
        )
    }
    confirmOne?.let { session ->
        ConfirmDialog(
            title = "Log out ${session.deviceLabel.ifEmpty { "this device" }}?",
            message = "It will be logged out right away.",
            confirmLabel = "Log out", destructive = true,
            onConfirm = { confirmOne = null; viewModel.endSession(session.id) },
            onDismiss = { confirmOne = null },
        )
    }
}

@Composable
private fun SessionRow(session: LoginSession, enabled: Boolean, onEnd: () -> Unit) {
    val name = session.deviceLabel.ifEmpty { "Unnamed device" }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (session.current) "$name (this device)" else name, style = MaterialTheme.typography.bodyLarge)
            val started = formatServerTime(session.startedAt)
            val active = formatServerTime(session.lastActiveAt)
            if (started.isNotEmpty()) Text("Logged in $started", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (active.isNotEmpty()) Text("Last active $active", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!session.current) {
            VibeButton("Log out", onClick = onEnd, style = VibeButtonStyle.Text, compact = true, enabled = enabled)
        }
    }
}

@Composable
fun AccountStatusScreen(onBack: () -> Unit, onGuidelines: () -> Unit, viewModel: AccountStatusViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsPage("Account status", onBack) {
        when (val s = state) {
            AccountStatusState.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            AccountStatusState.Unavailable -> item {
                EmptyState(VibeIcons.Info, "Not available in the preview build", "Account status comes from the UniVibe server, which this build isn't connected to.")
            }
            is AccountStatusState.Failed -> item { ErrorState(s.message, onRetry = viewModel::load) }
            is AccountStatusState.Ready -> {
                val st = s.status
                item {
                    VibeCard {
                        Text(if (st.suspended) "Suspended" else "In good standing", style = MaterialTheme.typography.titleMedium)
                        val until = st.suspendedUntil?.let(::formatServerTime).orEmpty()
                        Text(
                            if (st.suspended) (if (until.isNotEmpty()) "Your account is suspended until $until." else "Your account is suspended.")
                            else "No restrictions are on your account.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xs),
                        )
                    }
                }
                item {
                    SettingsGroup {
                        StatusValueRow("Campus verification", campusVerificationLabel(st.campusVerification))
                        RowDivider()
                        StatusValueRow("Removed posts", st.removedPosts.toString())
                        RowDivider()
                        StatusValueRow("Removed comments", st.removedComments.toString())
                    }
                }
                item { SettingsSectionTitle("Notices from moderators") }
                if (st.notices.isEmpty()) item { SettingsNote("You have no notices.") } else item {
                    SettingsGroup {
                        st.notices.forEachIndexed { i, n ->
                            if (i > 0) RowDivider()
                            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                                Text(noticeActionLabel(n.action), style = MaterialTheme.typography.bodyLarge)
                                if (n.message.isNotEmpty()) Text(n.message, style = MaterialTheme.typography.bodyMedium)
                                val whenText = formatServerTime(n.createdAt)
                                if (whenText.isNotEmpty()) Text(whenText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    SettingsGroup { SettingsNavRow(VibeIcons.Info, "Community guidelines", onGuidelines) }
                }
                if (!st.appealsSupported) item { SettingsNote("Appeals aren't available in the app yet. If you think a decision was a mistake, use Help & support to contact us.") }
            }
        }
    }
}

@Composable
private fun StatusValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
