package com.univibe.app.features.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.model.Student
import com.univibe.app.data.repository.GroupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_GROUP_MEMBERS_ON_CREATE = 255

data class NewGroupUiState(
    val loading: Boolean = true,
    val contacts: List<Student> = emptyList(),
    val query: String = "",
    val selected: Set<String> = emptySet(),
    /** 1 = pick people, 2 = name and photo. */
    val step: Int = 1,
    val name: String = "",
    val description: String = "",
    val photoPath: String? = null,
    val importing: Boolean = false,
    val creating: Boolean = false,
    val notice: String? = null,
    val createdChatId: String? = null,
) {
    val visible: List<Student>
        get() = if (query.isBlank()) contacts else contacts.filter { it.name.contains(query.trim(), true) || it.handle.contains(query.trim(), true) }
    val selectedStudents: List<Student> get() = contacts.filter { it.id in selected }
    val canNext get() = selected.isNotEmpty()
    val nameOk get() = name.trim().isNotEmpty() && name.length <= MAX_GROUP_NAME
    val descriptionOk get() = description.length <= MAX_GROUP_DESCRIPTION
    val canCreate get() = nameOk && descriptionOk && selected.isNotEmpty() && !creating && !importing && createdChatId == null
}

class NewGroupViewModel(
    private val groups: GroupRepository = AppContainer.groupRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NewGroupUiState())
    val state: StateFlow<NewGroupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val list = runCatching { groups.getContacts() }.getOrDefault(emptyList())
            _state.update { it.copy(loading = false, contacts = list) }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun toggle(id: String) = _state.update { s ->
        when {
            id in s.selected -> s.copy(selected = s.selected - id)
            s.selected.size >= MAX_GROUP_MEMBERS_ON_CREATE -> s.copy(notice = "Groups can have up to ${MAX_GROUP_MEMBERS_ON_CREATE + 1} members.")
            else -> s.copy(selected = s.selected + id)
        }
    }

    fun next() = _state.update { if (it.canNext) it.copy(step = 2) else it }
    fun backStep() = _state.update { it.copy(step = 1) }
    fun setName(v: String) = _state.update { it.copy(name = v) }
    fun setDescription(v: String) = _state.update { it.copy(description = v) }
    fun setImporting(v: Boolean) = _state.update { it.copy(importing = v) }

    /** [path] is a private, re-encoded copy of the picked image (null means the pick failed). */
    fun setPhoto(path: String?) {
        if (path == null) { _state.update { it.copy(notice = "Couldn't use that image.") }; return }
        _state.value.photoPath?.let(PhotoStore::delete)
        _state.update { it.copy(photoPath = path) }
    }

    fun removePhoto() {
        _state.value.photoPath?.let(PhotoStore::delete)
        _state.update { it.copy(photoPath = null) }
    }

    fun create() {
        val s = _state.value
        if (!s.canCreate) return
        viewModelScope.launch {
            _state.update { it.copy(creating = true) }
            try {
                val id = groups.createGroup(s.name, s.description, s.photoPath, s.selected.toList())
                _state.update { it.copy(creating = false, createdChatId = id) }
            } catch (e: Exception) {
                _state.update { it.copy(creating = false, notice = e.message?.takeIf { m -> m.length < 90 } ?: "Couldn't create the group. Try again.") }
            }
        }
    }

    override fun onCleared() {
        // A photo picked for a group that was never created is a private file nothing references.
        if (_state.value.createdChatId == null) _state.value.photoPath?.let(PhotoStore::delete)
    }
}
