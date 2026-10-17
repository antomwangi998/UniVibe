package com.univibe.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.domain.auth.AuthValidators
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.LabeledCheckbox
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.PasswordStrengthMeter
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibePasswordField
import com.univibe.app.ui.theme.Spacing

@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onDelete: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    SettingsPage("Account", onBack) {
        item {
            VibeCard {
                val u = user
                if (u == null) {
                    Text("You're signed out.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StudentAvatar(u.fullName, size = 56.dp, imagePath = u.avatarUrl)
                        Column(Modifier.padding(start = Spacing.md)) {
                            Text(u.fullName, style = MaterialTheme.typography.titleMedium)
                            Text(u.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        InfoLine("University", u.universityName)
                        InfoLine("Email", if (u.emailVerified) "Verified" else "Not verified")
                        InfoLine("Student status", if (u.studentVerified) "Verified student" else "Not verified yet")
                    }
                }
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(Icons.Outlined.LockReset, "Change password", onChangePassword)
                RowDivider()
                SettingsNavRow(Icons.Outlined.DeleteOutline, "Delete account", onDelete, destructive = true)
            }
        }
        item {
            SettingsNote("Student status is only shown as verified when the server matched your email to your university.")
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ChangePasswordScreen(onBack: () -> Unit, viewModel: ChangePasswordViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val next = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })

    SettingsPage("Change password", onBack) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                if (state.success) MessageBanner("Password updated.", BannerKind.Info)
                state.error?.let { MessageBanner(it, BannerKind.Error) }
                VibePasswordField(
                    value = state.current, onValueChange = viewModel::onCurrent, label = "Current password",
                    isError = state.currentError != null, supportingText = state.currentError, keyboardActions = next,
                )
                VibePasswordField(
                    value = state.new, onValueChange = viewModel::onNew, label = "New password",
                    isError = state.newError != null,
                    supportingText = state.newError ?: "At least ${AuthValidators.MIN_PASSWORD} characters with a letter and a number.",
                    keyboardActions = next,
                )
                PasswordStrengthMeter(AuthValidators.passwordStrength(state.new))
                VibePasswordField(
                    value = state.confirm, onValueChange = viewModel::onConfirm, label = "Confirm new password",
                    isError = state.confirmError != null, supportingText = state.confirmError,
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); viewModel.submit() }),
                )
                VibeButton("Update password", viewModel::submit, Modifier.fillMaxWidth(), loading = state.submitting)
            }
        }
    }
}

@Composable
fun DeleteAccountScreen(onBack: () -> Unit, viewModel: DeleteAccountViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    SettingsPage("Delete account", onBack) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                MessageBanner(
                    "Deleting your account removes your profile, posts and messages. This can't be undone.",
                    BannerKind.Error,
                )
                state.error?.let { MessageBanner(it, BannerKind.Error) }
                VibePasswordField(
                    value = state.password, onValueChange = viewModel::onPassword, label = "Confirm with your password",
                    imeAction = ImeAction.Done, keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
                LabeledCheckbox(state.understood, viewModel::onUnderstood) {
                    Text("I understand my account will be permanently deleted.", style = MaterialTheme.typography.bodyMedium)
                }
                VibeButton("Delete my account", viewModel::submit, Modifier.fillMaxWidth(), enabled = state.canSubmit, loading = state.submitting)
                SettingsNote("In this preview build the account only exists on this phone. A real backend must also erase server data and honour data-protection law.")
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}
