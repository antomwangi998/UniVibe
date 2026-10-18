package com.univibe.app.features.communities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.CommunityCategories
import com.univibe.app.data.model.CommunityPrivacy
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.repository.CommunityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_RULES = 8
const val MAX_RULE_LENGTH = 120

data class CommunityFormUiState(
    val loading: Boolean = false,
    val notFound: Boolean = false,
    val editing: Boolean = false,
    val name: String = "",
    val description: String = "",
    val category: String = CommunityCategories.first(),
    val accent: Int = 0,
    val privacy: CommunityPrivacy = CommunityPrivacy.Public,
    val rules: List<String> = emptyList(),
    val submitting: Boolean = false,
    val notice: String? = null,
    val doneId: String? = null,
) {
    val nameOk get() = name.trim().length in 3..40
    val descriptionOk get() = description.trim().length in 10..300
    val rulesOk get() = rules.size <= MAX_RULES && rules.all { it.length <= MAX_RULE_LENGTH }
    val canSubmit get() = (editing || nameOk) && descriptionOk && rulesOk && !submitting && doneId == null
}

/** Create a community, or edit one you own ([editId] != null). The name can't change after creation. */
class CommunityFormViewModel(
    private val editId: String?,
    private val repository: CommunityRepository = AppContainer.communityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CommunityFormUiState(editing = editId != null, loading = editId != null))
    val state: StateFlow<CommunityFormUiState> = _state.asStateFlow()

    init {
        if (editId != null) viewModelScope.launch {
            val c = runCatching { repository.getCommunity(editId) }.getOrNull()
            if (c == null || c.role != CommunityRole.Owner) { _state.update { it.copy(loading = false, notFound = true) }; return@launch }
            _state.update {
                it.copy(loading = false, name = c.name, description = c.description, category = c.category, accent = c.accent, privacy = c.privacy, rules = c.rules)
            }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v) }
    fun setDescription(v: String) = _state.update { it.copy(description = v) }
    fun setCategory(v: String) = _state.update { it.copy(category = v) }
    fun setAccent(v: Int) = _state.update { it.copy(accent = v) }
    fun setPrivacy(v: CommunityPrivacy) = _state.update { it.copy(privacy = v) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun addRule() = _state.update { if (it.rules.size < MAX_RULES) it.copy(rules = it.rules + "") else it }
    fun setRule(i: Int, v: String) = _state.update { s -> s.copy(rules = s.rules.mapIndexed { j, r -> if (j == i) v else r }) }
    fun removeRule(i: Int) = _state.update { s -> s.copy(rules = s.rules.filterIndexed { j, _ -> j != i }) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true) }
            try {
                val rules = s.rules.map { it.trim() }.filter { it.isNotEmpty() }
                val id = if (editId != null) {
                    repository.updateCommunity(editId, s.description, s.category, s.accent, rules, s.privacy); editId
                } else {
                    repository.createCommunity(s.name, s.description, s.category, s.accent, rules, s.privacy)
                }
                _state.update { it.copy(submitting = false, doneId = id) }
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, notice = e.message?.takeIf { m -> m.length < 90 } ?: "Couldn't save. Try again.") }
            }
        }
    }
}
