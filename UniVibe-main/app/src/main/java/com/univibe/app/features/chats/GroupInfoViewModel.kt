package com.univibe.app.features.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.model.GroupInfo
import com.univibe.app.data.model.GroupMember
import com.univibe.app.data.model.GroupRole
import com.univibe.app.data.model.Student
import com.univibe.app.data.repository.ChatRepository
import com.univibe.app.data.repository.GroupRepository
import com.univibe.app.data.repository.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_GROUP_NAME = 40
const val MAX_GROUP_DESCRIPTION = 200

sealed interface GroupConfirm {
    data class RemoveMember(val member: GroupMember) : GroupConfirm
    data object Leave : GroupConfirm
    data object ClearChat : GroupConfirm
}

data class GroupInfoUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val group: GroupInfo? = null,
    /** Photos shared in the chat, newest first. */
    val photos: List<String> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
    val editing: Boolean = false,
    val memberMenu: GroupMember? = null,
    val confirm: GroupConfirm? = null,
    val reporting: Boolean = false,
    val adding: Boolean = false,
    val addable: List<Student> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val left: Boolean = false,
) {
    val addableVisible: List<Student>
        get() = if (query.isBlank()) addable else addable.filter { it.name.contains(query.trim(), true) || it.handle.contains(query.trim(), true) }
}

class GroupInfoViewModel(
    private val chatId: String,
    private val groups: GroupRepository = AppContainer.groupRepository,
    private val chats: ChatRepository = AppContainer.chatRepository,
    private val messages: MessageRepository = AppContainer.messageRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(GroupInfoUiState())
    val state: StateFlow<GroupInfoUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            messages.messages(chatId).collect { list ->
                _state.update { it.copy(photos = list.asReversed().mapNotNull { m -> m.imagePath }) }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val g = runCatching { groups.getGroup(chatId) }.getOrNull()
            _state.update { it.copy(loading = false, group = g, notFound = g == null) }
        }
    }

    /** Runs a change, shows the server's reason if it is refused, then reloads. */
    private fun change(success: String? = null, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
                _state.update { it.copy(notice = success) }
            } catch (e: Exception) {
                // A real backend should return user-safe messages; never show raw exception text from the network.
                _state.update { it.copy(notice = e.message?.takeIf { m -> m.length < 90 } ?: "That didn't work. Try again.") }
            }
            val g = runCatching { groups.getGroup(chatId) }.getOrNull()
            _state.update { it.copy(busy = false, group = g ?: it.group) }
        }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }
    fun edit(show: Boolean) = _state.update { it.copy(editing = show) }
    fun memberMenu(member: GroupMember?) = _state.update { it.copy(memberMenu = member) }
    fun confirm(c: GroupConfirm?) = _state.update { it.copy(confirm = c, memberMenu = null) }
    fun reporting(show: Boolean) = _state.update { it.copy(reporting = show) }

    fun saveInfo(name: String, description: String) {
        if (name.trim().isEmpty() || name.trim().length > MAX_GROUP_NAME || description.trim().length > MAX_GROUP_DESCRIPTION) return
        _state.update { it.copy(editing = false) }
        change("Group updated") { groups.updateInfo(chatId, name, description) }
    }

    /** [path] is a private, re-encoded copy of the picked image. */
    fun setPhoto(path: String?) {
        if (path == null) { _state.update { it.copy(notice = "Couldn't use that image.") }; return }
        change("Group photo updated") {
            try { groups.setPhoto(chatId, path) } catch (e: Exception) { PhotoStore.delete(path); throw e }
        }
    }

    fun removePhoto() {
        _state.update { it.copy(editing = false) }
        change("Group photo removed") { groups.setPhoto(chatId, null) }
    }

    fun toggleMute() {
        val g = _state.value.group ?: return
        change(if (g.muted) "Notifications on" else "Muted") { chats.setMuted(chatId, !g.muted) }
    }

    fun openAdd() {
        viewModelScope.launch {
            try {
                val list = groups.getAddable(chatId)
                _state.update { it.copy(adding = true, addable = list, selected = emptySet(), query = "") }
            } catch (e: Exception) {
                _state.update { it.copy(notice = e.message?.takeIf { m -> m.length < 90 } ?: "You can't add members.") }
            }
        }
    }

    fun closeAdd() = _state.update { it.copy(adding = false, selected = emptySet(), query = "") }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun toggleSelected(id: String) = _state.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }

    fun confirmAdd() {
        val ids = _state.value.selected.toList()
        if (ids.isEmpty()) return
        closeAdd()
        change(if (ids.size == 1) "1 member added" else "${ids.size} members added") { groups.addMembers(chatId, ids) }
    }

    /** Opens (or creates) the 1:1 chat with a member, then lets the screen navigate to it. */
    fun message(m: GroupMember, onReady: (String) -> Unit) {
        memberMenu(null)
        viewModelScope.launch {
            val id = runCatching { messages.openChatWith(m.student) }.getOrNull()
            if (id != null) onReady(id) else _state.update { it.copy(notice = "Couldn't open the chat. Try again.") }
        }
    }

    fun makeAdmin(m: GroupMember) { memberMenu(null); change("${m.student.name.substringBefore(' ')} is now an admin") { groups.setRole(chatId, m.student.id, GroupRole.Admin) } }
    fun removeAdmin(m: GroupMember) { memberMenu(null); change("Admin removed") { groups.setRole(chatId, m.student.id, GroupRole.Member) } }

    fun setPermissions(onlyAdminsMessage: Boolean? = null, onlyAdminsEdit: Boolean? = null, onlyAdminsAdd: Boolean? = null) {
        val g = _state.value.group ?: return
        change {
            groups.setPermissions(
                chatId,
                onlyAdminsMessage ?: g.onlyAdminsCanMessage,
                onlyAdminsEdit ?: g.onlyAdminsCanEditInfo,
                onlyAdminsAdd ?: g.onlyAdminsCanAddMembers,
            )
        }
    }

    fun doConfirmed() {
        when (val c = _state.value.confirm) {
            is GroupConfirm.RemoveMember -> { confirm(null); change("${c.member.student.name.substringBefore(' ')} removed") { groups.removeMember(chatId, c.member.student.id) } }
            GroupConfirm.Leave -> {
                confirm(null)
                viewModelScope.launch {
                    try { groups.leave(chatId); _state.update { it.copy(left = true) } }
                    catch (e: Exception) { _state.update { it.copy(notice = "Couldn't leave the group. Try again.") } }
                }
            }
            GroupConfirm.ClearChat -> { confirm(null); change("Chat cleared") { messages.clearChat(chatId) } }
            null -> Unit
        }
    }

    fun report(reason: String) {
        _state.update { it.copy(reporting = false) }
        change("Thanks. We'll review this group.") { groups.report(chatId, reason) }
    }
}
