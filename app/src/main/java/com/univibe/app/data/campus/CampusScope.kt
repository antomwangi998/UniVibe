package com.univibe.app.data.campus

import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.send
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.URLEncoder

/**
 * How far Home, Discover, Events and Search reach. Campuses stay separate communities: everyone and everything keeps its
 * own campus, shown as a badge. The scope only decides which campuses you are looking at right now.
 */
sealed interface CampusScope {
    /** Your own campus (plus people you follow elsewhere). The default. */
    data object Home : CampusScope

    /** Every campus. */
    data object All : CampusScope

    /** One specific other campus. */
    data class One(val id: String, val name: String) : CampusScope

    /** What the server expects in `campusScope=`. Null for Home, which is the server's default for posts and people. */
    val param: String?
        get() = when (this) {
            Home -> null
            All -> "all"
            is One -> id
        }
}

data class CampusInfo(val id: String, val name: String, val shortName: String, val members: Int)

/** The campuses a student can switch between, from the server. [homeQuiet] means their own campus is still small. */
data class CampusMenu(val home: CampusInfo, val others: List<CampusInfo>, val homeQuiet: Boolean)

/**
 * The app-wide campus filter. Every list that can cross campuses reads [scope] when it builds its request, and the
 * screens that show lists reload when it changes. It lives in memory only: each session starts at Home, except that a
 * quiet campus opens on All once (see [applyMenu]) so a brand-new campus never opens on an empty feed.
 */
object CampusScopeStore {
    private val _scope = MutableStateFlow<CampusScope>(CampusScope.Home)
    val scope: StateFlow<CampusScope> = _scope.asStateFlow()

    private val _menu = MutableStateFlow<CampusMenu?>(null)
    val menu: StateFlow<CampusMenu?> = _menu.asStateFlow()

    /** True when the app, not the student, switched to All because the home campus is quiet. Drives the explanatory note. */
    private val _autoOpened = MutableStateFlow(false)
    val autoOpened: StateFlow<Boolean> = _autoOpened.asStateFlow()

    private var chosenByStudent = false

    /** The student's own campus id, once the menu has loaded. Used to tell "your campus" from "another campus". */
    val homeId: String? get() = _menu.value?.home?.id

    fun select(scope: CampusScope) {
        chosenByStudent = true
        _autoOpened.value = false
        _scope.value = scope
    }

    /** Called with a freshly loaded menu. A quiet home campus opens on All once, unless the student already chose. */
    fun applyMenu(menu: CampusMenu) {
        val first = _menu.value == null
        _menu.value = menu
        if (first && menu.homeQuiet && !chosenByStudent && menu.others.isNotEmpty() && _scope.value == CampusScope.Home) {
            _autoOpened.value = true
            _scope.value = CampusScope.All
        }
        // A campus that has disappeared from the list falls back to Home rather than leaving the student on an empty view.
        val s = _scope.value
        if (s is CampusScope.One && menu.others.none { it.id == s.id }) _scope.value = CampusScope.Home
    }

    /** Back to the start (sign-out or account switch). */
    fun reset() {
        chosenByStudent = false
        _autoOpened.value = false
        _menu.value = null
        _scope.value = CampusScope.Home
    }

    fun dismissAutoNote() { _autoOpened.value = false }
}

/**
 * Adds `campusScope=` to a request path. Home adds nothing (the server default is Home), except where [explicitHome] is
 * set: events show public events from other campuses by default, so their Home filter must say so out loud.
 */
fun String.withCampusScope(scope: CampusScope = CampusScopeStore.scope.value, explicitHome: Boolean = false): String {
    val value = scope.param ?: (if (explicitHome) "home" else return this)
    return this + (if ('?' in this) "&" else "?") + "campusScope=" + URLEncoder.encode(value, "UTF-8")
}

/** Loads the campus menu. Offline or on a mock backend there simply is no menu, and the filter hides itself. */
class CampusScopeRepository(private val api: AuthedApi?) {
    suspend fun load(): CampusMenu? {
        val authed = api ?: return null
        val o = JSONObject(authed.send("GET", "/api/v1/campuses/scopes"))
        fun info(j: JSONObject) = CampusInfo(j.getString("id"), j.getString("name"), j.optString("shortName").ifBlank { j.getString("name") }, j.optInt("members"))
        val others = o.getJSONArray("others")
        return CampusMenu(
            home = info(o.getJSONObject("home")),
            others = (0 until others.length()).map { info(others.getJSONObject(it)) },
            homeQuiet = o.optBoolean("homeQuiet", false),
        )
    }
}
