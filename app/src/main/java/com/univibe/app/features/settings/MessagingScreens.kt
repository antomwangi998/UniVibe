package com.univibe.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Chat
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.repository.ChatRepository
import com.univibe.app.data.settings.PrivacyChange
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Settings → Messages. Every control here changes real behaviour:
 *  - Enter key sends and Message previews are device settings (the composer and the alert display read them).
 *  - Typing indicators is saved on the server, which then never tells others you are typing.
 * Who can message you and read receipts stay in Privacy & safety, with a link from here.
 */
@Composable
fun MessagingSettingsScreen(
    onBack: () -> Unit,
    onPrivacy: () -> Unit,
    onMuted: () -> Unit,
    onBlocked: () -> Unit,
    privacyViewModel: PrivacyViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val privacy by privacyViewModel.state.collectAsStateWithLifecycle()
    val device by settingsViewModel.settings.collectAsStateWithLifecycle()

    SettingsPage("Messages", onBack) {
        item {
            SettingsGroup {
                SettingsSectionTitle("Writing")
                SettingsSwitchRow(
                    "Enter key sends", device.enterToSend, { v -> settingsViewModel.update { it.copy(enterToSend = v) } },
                    subtitle = if (device.enterToSend) "The keyboard's Enter key sends your message." else "The Enter key starts a new line. Use the send button to send.",
                    icon = VibeIcons.SendFilled,
                )
            }
        }
        item {
            SettingsGroup {
                SettingsSectionTitle("Alerts")
                SettingsSwitchRow(
                    "Show message previews", device.showMessagePreview, { v -> settingsViewModel.update { it.copy(showMessagePreview = v) } },
                    subtitle = if (device.showMessagePreview) "Message alerts show the text of the message." else "Message alerts say \"New message\" without the text.",
                    icon = VibeIcons.Bell,
                )
            }
        }
        item {
            SettingsGroup {
                SettingsSectionTitle("Privacy in chats")
                when (val st = privacy) {
                    PrivacyState.Loading -> Row(Modifier.fillMaxWidth().padding(Spacing.lg), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                    is PrivacyState.Failed -> ErrorState(st.message, onRetry = privacyViewModel::load)
                    is PrivacyState.Ready -> {
                        st.error?.let { MessageBanner(it, BannerKind.Error) }
                        SettingsSwitchRow(
                            "Typing indicators", st.privacy.typingIndicators, { v -> privacyViewModel.change(PrivacyChange(typingIndicators = v)) },
                            subtitle = if (st.privacy.typingIndicators) "People you chat with can see when you're typing, and you can see when they are."
                            else "People can't see when you're typing, and you can't see when they are.",
                            icon = VibeIcons.Comment, enabled = !st.saving,
                        )
                        RowDivider()
                        SettingsNavRow(VibeIcons.DoubleCheck, "Who can message you, and read receipts", onPrivacy, subtitle = "In Privacy & safety")
                    }
                }
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(VibeIcons.BellOff, "Muted conversations", onMuted, subtitle = "Chats whose alerts you turned off")
                RowDivider()
                SettingsNavRow(VibeIcons.Block, "Blocked students", onBlocked)
            }
        }
        item { SettingsNote("Messages in UniVibe are not end-to-end encrypted. See the Terms of Service and Privacy Policy for how they are stored.") }
    }
}

sealed interface MutedState {
    data object Loading : MutedState
    data class Failed(val message: String) : MutedState
    data class Ready(val chats: List<Chat>, val working: String? = null, val error: String? = null) : MutedState
}

class MutedConversationsViewModel(
    private val repo: ChatRepository = AppContainer.chatRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<MutedState>(MutedState.Loading)
    val state: StateFlow<MutedState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = MutedState.Loading
        viewModelScope.launch {
            _state.value = try {
                MutedState.Ready(repo.getChats().filter { it.muted })
            } catch (e: IOException) {
                MutedState.Failed(describeFailure(e))
            } catch (e: ApiException) {
                MutedState.Failed(describeFailure(e))
            }
        }
    }

    fun unmute(chatId: String) {
        val before = _state.value as? MutedState.Ready ?: return
        if (before.working != null) return
        _state.value = before.copy(working = chatId, error = null)
        viewModelScope.launch {
            _state.value = try {
                repo.setMuted(chatId, false)
                MutedState.Ready(before.chats.filterNot { it.id == chatId })
            } catch (e: IOException) {
                before.copy(working = null, error = "Couldn't unmute. ${describeFailure(e)}")
            } catch (e: ApiException) {
                before.copy(working = null, error = "Couldn't unmute. ${describeFailure(e)}")
            }
        }
    }
}

@Composable
fun MutedConversationsScreen(onBack: () -> Unit, viewModel: MutedConversationsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsPage("Muted conversations", onBack) {
        when (val st = state) {
            MutedState.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            is MutedState.Failed -> item { ErrorState(st.message, onRetry = viewModel::load) }
            is MutedState.Ready -> {
                st.error?.let { item { MessageBanner(it, BannerKind.Error) } }
                if (st.chats.isEmpty()) item {
                    EmptyState(VibeIcons.BellOff, "No muted conversations", "When you mute a chat from the chat list or the chat's menu, it shows up here so you can turn its alerts back on.")
                } else item {
                    SettingsGroup {
                        st.chats.forEachIndexed { i, chat ->
                            if (i > 0) RowDivider()
                            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                Text(chat.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f))
                                TextButton(onClick = { viewModel.unmute(chat.id) }, enabled = st.working == null) { Text("Unmute") }
                            }
                        }
                    }
                }
            }
        }
    }
}
