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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.settings.MessagePolicy
import com.univibe.app.data.settings.PrivacyChange
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.theme.Spacing

/**
 * The screens under Privacy & safety. Each one owns a single decision and says what it does, so a search result or a
 * deep link lands on the exact control. They share [PrivacyViewModel]'s rules: the new value shows at once, is saved to
 * the server, and is put back with a message if the save fails.
 */

/** Shared frame: loading, failure with retry, a save-error banner, then the screen's own rows. */
@Composable
private fun PrivacyPage(
    title: String,
    onBack: () -> Unit,
    viewModel: PrivacyViewModel,
    content: LazyListScope.(PrivacyState.Ready) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsPage(title, onBack) {
        when (val st = state) {
            PrivacyState.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            is PrivacyState.Failed -> item { ErrorState(st.message, onRetry = viewModel::load) }
            is PrivacyState.Ready -> {
                st.error?.let { item { MessageBanner(it, BannerKind.Error) } }
                content(this, st)
                item {
                    SettingsNote(
                        if (st.synced) "Saved to your account, so it applies on every device."
                        else "Preview build: saved on this device only.",
                    )
                }
            }
        }
    }
}

@Composable
fun PrivacyVisibilityScreen(
    onBack: () -> Unit,
    onDiscoverProfile: () -> Unit,
    viewModel: PrivacyViewModel = viewModel(),
) = PrivacyPage("Visibility", onBack, viewModel) { st ->
    item {
        SettingsGroup {
            SettingsSwitchRow(
                "Show me in Discover", st.privacy.discoverable, { v -> viewModel.change(PrivacyChange(discoverable = v)) },
                subtitle = if (st.privacy.discoverable) "Other students can find your profile in Discover."
                else "Your profile is hidden from Discover.",
                icon = VibeIcons.Visibility, enabled = !st.saving,
            )
            RowDivider()
            SettingsNavRow(VibeIcons.Photo, "Discover profile", onDiscoverProfile, subtitle = "Choose your card photo and preview it.")
        }
    }
}

@Composable
fun PrivacyMessagesScreen(
    onBack: () -> Unit,
    onWhoCanMessage: () -> Unit,
    onReadReceipts: () -> Unit,
    onBlocked: () -> Unit,
    viewModel: PrivacyViewModel = viewModel(),
) = PrivacyPage("Messages", onBack, viewModel) { st ->
    item {
        SettingsGroup {
            SettingsNavRow(VibeIcons.Chats, "Who can message you", onWhoCanMessage, subtitle = st.privacy.messagePolicy.label)
            RowDivider()
            SettingsNavRow(VibeIcons.DoubleCheck, "Read receipts", onReadReceipts, subtitle = if (st.privacy.readReceipts) "On" else "Off")
            RowDivider()
            SettingsSwitchRow(
                "Typing indicators", st.privacy.typingIndicators, { v -> viewModel.change(PrivacyChange(typingIndicators = v)) },
                subtitle = if (st.privacy.typingIndicators) "People you chat with can see when you're typing, and you can see when they are."
                else "People can't see when you're typing, and you can't see when they are.",
                icon = VibeIcons.Sensors, enabled = !st.saving,
            )
        }
    }
    item {
        SettingsGroup { SettingsNavRow(VibeIcons.Block, "Blocked students", onBlocked) }
    }
}

@Composable
fun MessagePolicyScreen(onBack: () -> Unit, viewModel: PrivacyViewModel = viewModel()) =
    PrivacyPage("Who can message you", onBack, viewModel) { st ->
        item {
            SettingsGroup {
                MessagePolicy.entries.forEach { policy ->
                    SettingsRadioRow(policy.label, policy.description, st.privacy.messagePolicy == policy) {
                        if (!st.saving && st.privacy.messagePolicy != policy) viewModel.change(PrivacyChange(messagePolicy = policy))
                    }
                }
            }
        }
        item { SettingsNote("Choose who can start a new conversation with you. Students you've blocked can never message you, whatever you choose here.") }
    }

@Composable
fun ReadReceiptsScreen(onBack: () -> Unit, viewModel: PrivacyViewModel = viewModel()) =
    PrivacyPage("Read receipts", onBack, viewModel) { st ->
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    "Read receipts", st.privacy.readReceipts, { v -> viewModel.change(PrivacyChange(readReceipts = v)) },
                    subtitle = if (st.privacy.readReceipts) "People can see when you've read their message."
                    else "People can't see when you've read their message, and you can't see when they've read yours.",
                    icon = VibeIcons.DoubleCheck, enabled = !st.saving,
                )
            }
        }
        item { SettingsNote("This applies to all your chats and groups.") }
    }

@Composable
fun ActivityStatusScreen(onBack: () -> Unit, viewModel: PrivacyViewModel = viewModel()) =
    PrivacyPage("Activity status", onBack, viewModel) { st ->
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    "Show online status", st.privacy.showOnline, { v -> viewModel.change(PrivacyChange(showOnline = v)) },
                    subtitle = if (st.privacy.showOnline) "Others can see when you're active."
                    else "Others can't see when you're active.",
                    icon = VibeIcons.Sensors, enabled = !st.saving,
                )
            }
        }
        item { SettingsNote("This controls whether other students are told you are online.") }
    }
