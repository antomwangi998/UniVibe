package com.univibe.app.data.repository

import com.univibe.app.data.model.MembershipState
import com.univibe.app.data.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** What the server answered after a membership change. The server's answer is the truth. */
data class MembershipResult(val state: MembershipState, val memberCount: Int)

/** One viewer's membership in one community, as every screen shows it. */
data class MembershipSnapshot(
    val state: MembershipState,
    val memberCount: Int,
    /** A request is in flight: show a spinner and ignore further taps. */
    val busy: Boolean = false,
    /** The last attempt failed; [state] is the previous valid state, so a retry is just another tap. */
    val error: String? = null,
)

/** What the store asks the backend to do. Implemented by the real repository (and an adapter for the offline demo). */
interface MembershipActions {
    suspend fun join(id: String): MembershipResult
    /** Leaves a community, or withdraws a pending request. */
    suspend fun leave(id: String): MembershipResult
}

/**
 * The single place that holds membership state. The Home feed, Discover, search, the community list and the
 * community page all read the same snapshot, so joining anywhere updates everywhere without reloading a screen.
 *
 * No optimistic flip: a tap shows a spinner, and the state changes only when the server confirms. A failure puts
 * back the previous valid state with an error and a retry.
 */
class CommunityMembershipStore(
    private val actions: MembershipActions,
    private val scope: CoroutineScope,
) {
    private val _all = MutableStateFlow<Map<String, MembershipSnapshot>>(emptyMap())
    val all: StateFlow<Map<String, MembershipSnapshot>> = _all.asStateFlow()

    /** First sight of a community (for example on a post card). Never overwrites what is already known. */
    fun seed(id: String, state: MembershipState, memberCount: Int) {
        _all.update { if (id in it) it else it + (id to MembershipSnapshot(state, memberCount)) }
    }

    /** Fresh data from the server (a fetched page, a live event). Ignored while a tap is in flight; its own answer wins. */
    fun apply(id: String, state: MembershipState, memberCount: Int) {
        _all.update { cur -> if (cur[id]?.busy == true) cur else cur + (id to MembershipSnapshot(state, memberCount)) }
    }

    /** A tap on the button. Join/request/accept send a request; Pending withdraws it; Joined leaves (callers confirm first). */
    fun click(id: String) {
        val before = _all.value[id] ?: return
        val call: suspend () -> MembershipResult = when (before.state) {
            MembershipState.Join, MembershipState.Request, MembershipState.Invited -> ({ actions.join(id) })
            MembershipState.Pending, MembershipState.Joined -> ({ actions.leave(id) })
            MembershipState.Unavailable -> return
        }
        if (!markBusy(id)) return // a second tap while one is in flight does nothing
        scope.launch {
            try {
                val r = call()
                _all.update { it + (id to MembershipSnapshot(r.state, r.memberCount)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _all.update { it + (id to before.copy(busy = false, error = messageFor(e))) }
            }
        }
    }

    /** On sign-out: one student's membership must never be shown to the next. */
    fun clear() { _all.value = emptyMap() }

    fun dismissError(id: String) {
        _all.update { cur -> cur[id]?.let { cur + (id to it.copy(error = null)) } ?: cur }
    }

    private fun markBusy(id: String): Boolean {
        var started = false
        _all.update { cur ->
            started = false
            val s = cur[id]
            if (s == null || s.busy) cur else { started = true; cur + (id to s.copy(busy = true, error = null)) }
        }
        return started
    }

    private fun messageFor(e: Exception): String = when {
        e is IOException -> "No connection. Check your network and try again."
        e is ApiException && e.code == "invite_only" -> "This community is invitation-only."
        e is ApiException && e.code == "suspended_member" -> "You can't rejoin this community right now."
        e is ApiException && e.code == "community_unavailable" -> "This community isn't accepting members."
        e is ApiException && e.status == 404 -> "This community is no longer available."
        e is ApiException && e.status == 429 -> "Too many attempts. Try again in a bit."
        else -> "Couldn't update your membership. Try again."
    }
}

/**
 * Offline demo only: adapts the in-memory community repository, which has no membership state of its own.
 */
class RepositoryMembershipActions(private val repository: CommunityRepository) : MembershipActions {
    private suspend fun result(id: String): MembershipResult {
        val c = repository.getCommunity(id) ?: throw ApiException(404, "not_found", "Not found")
        return MembershipResult(c.effectiveMembership, c.members)
    }
    override suspend fun join(id: String): MembershipResult { repository.setMembership(id, true); return result(id) }
    override suspend fun leave(id: String): MembershipResult { repository.setMembership(id, false); return result(id) }
}
