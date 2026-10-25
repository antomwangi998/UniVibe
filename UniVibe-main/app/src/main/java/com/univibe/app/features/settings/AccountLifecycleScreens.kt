package com.univibe.app.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.BuildConfig
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.LabeledCheckbox
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibePasswordField
import com.univibe.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The two account actions that are neither a preference nor a deletion:
 *  - Deactivate: hide the account, keep everything, come back by logging in.
 *  - Download your data: a JSON copy of your own data, saved to a file you choose.
 */

@Composable
fun DeactivateAccountScreen(onBack: () -> Unit, viewModel: DeactivateAccountViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    SettingsPage("Deactivate account", onBack) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                MessageBanner(
                    "Your profile, posts and comments are hidden from other students, and you are logged out everywhere. " +
                        "Nothing is deleted, and messages you already sent stay in your chats.",
                    BannerKind.Info,
                )
                state.error?.let { MessageBanner(it, BannerKind.Error) }
                Text("To bring your account back, just log in again.", style = MaterialTheme.typography.bodyMedium)
                VibePasswordField(
                    value = state.password, onValueChange = viewModel::onPassword, label = "Confirm with your password",
                    imeAction = ImeAction.Done, keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
                LabeledCheckbox(state.understood, viewModel::onUnderstood) {
                    Text("I understand my account will be hidden until I log in again.", style = MaterialTheme.typography.bodyMedium)
                }
                VibeButton("Deactivate my account", viewModel::submit, Modifier.fillMaxWidth(), enabled = state.canSubmit, loading = state.submitting)
            }
        }
        item { SettingsNote("Want it gone for good? Delete account permanently removes your data and can't be undone.") }
    }
}

@Composable
fun DataExportScreen(onBack: () -> Unit, viewModel: DataExportViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        // Cancelling the picker leaves the copy ready, so the person can choose again.
        if (uri != null) viewModel.save { context.contentResolver.openOutputStream(uri) }
    }
    SettingsPage("Download your data", onBack) {
        if (BuildConfig.API_BASE_URL.isBlank()) {
            item {
                EmptyState(
                    icon = VibeIcons.Archive,
                    title = "Not available in this build",
                    message = "This build isn't connected to a UniVibe server, so there is no data to download.",
                )
            }
            return@SettingsPage
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "Get a copy of your own data as a file: your profile and settings, posts, comments, who you follow and who follows you, " +
                        "blocked students, communities, event sign-ups and messages you sent.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.saved) MessageBanner("Saved. The file is on your phone; keep it somewhere safe.", BannerKind.Info)
                state.error?.let { MessageBanner(it, BannerKind.Error) }
                if (state.ready) {
                    MessageBanner("Your copy is ready. Choose where to save it.", BannerKind.Info)
                    VibeButton(
                        "Choose where to save", { picker.launch(exportFileName()) }, Modifier.fillMaxWidth(),
                        loading = state.saving, leadingIcon = VibeIcons.Archive,
                    )
                } else {
                    VibeButton(
                        if (state.saved || state.error != null) "Create a new copy" else "Create a copy", viewModel::request,
                        Modifier.fillMaxWidth(), loading = state.working,
                        style = if (state.saved) VibeButtonStyle.Secondary else VibeButtonStyle.Primary,
                    )
                }
            }
        }
        item {
            SettingsNote(
                "The file is not encrypted. It never includes your password, login details or other people's messages. " +
                    "Very long lists stop at a limit, and the file says so when that happens. You can ask for a copy a few times an hour.",
            )
        }
    }
}

private fun exportFileName() = "univibe-data-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json"
