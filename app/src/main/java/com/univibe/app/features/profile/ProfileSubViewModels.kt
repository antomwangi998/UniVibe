package com.univibe.app.features.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.model.FollowEntry
import com.univibe.app.data.model.MyProfile
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.ProfileUpdate
import com.univibe.app.data.model.Student
import com.univibe.app.data.repository.FeedRepository
import com.univibe.app.data.repository.MessageRepository
import com.univibe.app.data.repository.ProfileSaveException
import com.univibe.app.data.repository.ProfileRepository
import com.univibe.app.data.settings.BlockedUser
import com.univibe.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_PROFILE_BIO = 160
val StudyYears = listOf("1st Year", "2nd Year", "3rd Year", "4th Year", "5th Year+")

// ------------------------------------------------------------------ edit profile
data class EditProfileUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val original: MyProfile? = null,
    val name: String = "",
    val bio: String = "",
    val course: String = "",
    val year: String = "",
    val interests: Set<String> = emptySet(),
    val avatarPath: String? = null,
    val coverPath: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val nameError get() = when {
        name.trim().length < 2 -> "Enter your name."
        name.trim().length > 60 -> "Keep your name under 60 characters."
        else -> null
    }
    val bioError get() = if (bio.length > MAX_PROFILE_BIO) "Keep your bio under $MAX_PROFILE_BIO characters." else null
    val courseError get() = if (course.length > 60) "Keep this under 60 characters." else null
    val valid get() = nameError == null && bioError == null && courseError == null
    val dirty: Boolean
        get() {
            val o = original ?: return false
            return name != o.student.name || bio != o.bio || course != o.student.course || year != o.student.year ||
                interests != o.student.interests.toSet() || avatarPath != o.avatarPath || coverPath != o.coverPath
        }
}

class EditProfileViewModel(
    private val repository: ProfileRepository = AppContainer.profileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(EditProfileUiState())
    val state: StateFlow<EditProfileUiState> = _state.asStateFlow()
    private val importedThisSession = mutableSetOf<String>()

    init { load() }

    fun load() {
        viewModelScope.launch {
            try {
                val p = repository.getMyProfile()
                _state.update {
                    it.copy(
                        loading = false, error = null, original = p, name = p.student.name, bio = p.bio, course = p.student.course,
                        year = p.student.year, interests = p.student.interests.toSet(), avatarPath = p.avatarPath, coverPath = p.coverPath,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "We couldn't load your profile.") }
            }
        }
    }

    fun onName(v: String) = _state.update { it.copy(name = v) }
    fun onBio(v: String) = _state.update { it.copy(bio = v) }
    fun onCourse(v: String) = _state.update { it.copy(course = v) }
    fun onYear(v: String) = _state.update { it.copy(year = v) }
    fun toggleInterest(name: String) = _state.update { s ->
        s.copy(interests = if (name in s.interests) s.interests - name else s.interests + name)
    }

    fun setAvatar(path: String) {
        cleanupIfTemporary(_state.value.avatarPath)
        importedThisSession += path
        _state.update { it.copy(avatarPath = path) }
    }
    fun setCover(path: String) {
        cleanupIfTemporary(_state.value.coverPath)
        importedThisSession += path
        _state.update { it.copy(coverPath = path) }
    }
    fun removeAvatar() { cleanupIfTemporary(_state.value.avatarPath); _state.update { it.copy(avatarPath = null) } }
    fun removeCover() { cleanupIfTemporary(_state.value.coverPath); _state.update { it.copy(coverPath = null) } }

    private fun cleanupIfTemporary(path: String?) {
        if (path != null && importedThisSession.remove(path)) PhotoStore.delete(path)
    }

    fun save() {
        val s = _state.value
        if (s.saving || !s.valid) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                repository.updateProfile(ProfileUpdate(s.name, s.bio, s.course, s.year, s.interests.toList(), s.avatarPath, s.coverPath))
                importedThisSession.clear() // now owned by the saved profile
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: ProfileSaveException) {
                _state.update { it.copy(saving = false, error = e.message ?: "Couldn't save. Please try again.") }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = "Couldn't save. Please try again.") }
            }
        }
    }

    /** Throws away photos picked in this session that were never saved. */
    fun discard() { importedThisSession.forEach { PhotoStore.delete(it) }; importedThisSession.clear() }

    override fun onCleared() { discard() }
}

// ------------------------------------------------------------------ photos
data class PhotosUiState(val loading: Boolean = true, val error: String? = null, val photos: List<String> = emptyList())

class PhotosViewModel(
    private val repository: ProfileRepository = AppContainer.profileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PhotosUiState())
    val state: StateFlow<PhotosUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            try { _state.update { it.copy(loading = false, error = null, photos = repository.getMyProfile().photos) } }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = "We couldn't load your photos.") } }
        }
    }

    fun add(path: String) { viewModelScope.launch { repository.addPhoto(path); load() } }
    fun remove(path: String) { viewModelScope.launch { repository.removePhoto(path); load() } }
}

// ------------------------------------------------------------------ saved posts
data class SavedUiState(val loading: Boolean = true, val error: String? = null, val posts: List<Post> = emptyList())

class SavedPostsViewModel(
    private val repository: FeedRepository = AppContainer.feedRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SavedUiState())
    val state: StateFlow<SavedUiState> = _state.asStateFlow()

    init { load() }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            try { _state.update { it.copy(loading = false, error = null, posts = repository.getSavedPosts()) } }
            catch (e: Exception) { if (!silent) _state.update { it.copy(loading = false, error = "We couldn't load your saved posts.") } }
        }
    }

    fun unsave(id: String) {
        _state.update { s -> s.copy(posts = s.posts.filterNot { it.id == id }) }
        viewModelScope.launch { runCatching { repository.setSaved(id, false) } }
    }

    fun toggleLike(id: String) {
        val post = _state.value.posts.firstOrNull { it.id == id } ?: return
        val liked = !post.likedByMe
        _state.update { s -> s.copy(posts = s.posts.map { if (it.id == id) it.copy(likedByMe = liked, likeCount = it.likeCount + if (liked) 1 else -1) else it }) }
        viewModelScope.launch { runCatching { repository.setLiked(id, liked) } }
    }

    fun noteShared(id: String) {
        _state.update { s -> s.copy(posts = s.posts.map { if (it.id == id) it.copy(shareCount = it.shareCount + 1) else it }) }
        viewModelScope.launch { runCatching { repository.recordShare(id) } }
    }
}

// ------------------------------------------------------------------ followers / following
enum class FollowKind { Followers, Following }

data class FollowListUiState(val loading: Boolean = true, val error: String? = null, val entries: List<FollowEntry> = emptyList())

class FollowListViewModel(
    private val kind: FollowKind,
    private val repository: ProfileRepository = AppContainer.profileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(FollowListUiState())
    val state: StateFlow<FollowListUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            try {
                val list = if (kind == FollowKind.Followers) repository.getFollowers() else repository.getFollowing()
                _state.update { it.copy(loading = false, error = null, entries = list) }
            } catch (e: Exception) { _state.update { it.copy(loading = false, error = "We couldn't load this list.") } }
        }
    }

    fun toggle(studentId: String) {
        val entry = _state.value.entries.firstOrNull { it.student.id == studentId } ?: return
        val now = !entry.isFollowing
        _state.update { s -> s.copy(entries = s.entries.map { if (it.student.id == studentId) it.copy(isFollowing = now) else it }) }
        viewModelScope.launch { runCatching { repository.setFollowing(studentId, now) } }
    }
}

// ------------------------------------------------------------------ another student's profile
data class StudentUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val student: Student? = null,
    val isMe: Boolean = false,
    val following: Boolean = false,
    val opening: Boolean = false,
    val menuOpen: Boolean = false,
    val notice: String? = null,
)

class StudentProfileViewModel(
    private val studentId: String,
    private val repository: ProfileRepository = AppContainer.profileRepository,
    private val messages: MessageRepository = AppContainer.messageRepository,
    private val settings: SettingsRepository = AppContainer.settingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(StudentUiState())
    val state: StateFlow<StudentUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val student = repository.getStudent(studentId)
                if (student == null) { _state.update { it.copy(loading = false, notFound = true) }; return@launch }
                val me = repository.getMyProfile().student.id == studentId
                val following = repository.getFollowing().any { it.student.id == studentId }
                _state.update { it.copy(loading = false, student = student, isMe = me, following = following) }
            } catch (e: Exception) { _state.update { it.copy(loading = false, notFound = true) } }
        }
    }

    fun toggleFollow() {
        val now = !_state.value.following
        _state.update { it.copy(following = now) }
        viewModelScope.launch { runCatching { repository.setFollowing(studentId, now) } }
    }

    fun message(onReady: (String) -> Unit) {
        val student = _state.value.student ?: return
        if (_state.value.opening) return
        viewModelScope.launch {
            _state.update { it.copy(opening = true) }
            val id = runCatching { messages.openChatWith(student) }.getOrNull()
            _state.update { it.copy(opening = false) }
            if (id != null) onReady(id) else _state.update { it.copy(notice = "Couldn't open the chat. Try again.") }
        }
    }

    fun openMenu(open: Boolean) = _state.update { it.copy(menuOpen = open) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun block() {
        val student = _state.value.student ?: return
        viewModelScope.launch {
            settings.update { s -> if (s.blocked.any { it.id == student.id }) s else s.copy(blocked = s.blocked + BlockedUser(student.id, student.name)) }
        }
        _state.update { it.copy(menuOpen = false, notice = "${student.name} blocked. You can undo this in Settings.") }
    }

    fun report() = _state.update { it.copy(menuOpen = false, notice = "Thanks. We'll review this profile.") }
}
