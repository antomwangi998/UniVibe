package com.univibe.app

import com.univibe.app.data.model.NewEvent
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.HttpResponse
import com.univibe.app.data.repository.InMemoryRecentSearches
import com.univibe.app.data.repository.RemoteDiscoverContentRepository
import com.univibe.app.data.repository.RemoteEventRepository
import com.univibe.app.data.repository.SearchOverview
import com.univibe.app.data.repository.SearchRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.features.search.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

private class SearchCall(val method: String, val path: String, val body: JSONObject?)

private class ScriptedApi(private val handler: (SearchCall) -> AuthResult<HttpResponse>) : AuthedApi {
    val calls = mutableListOf<SearchCall>()
    override suspend fun authedCall(method: String, path: String, body: JSONObject?): AuthResult<HttpResponse> {
        val c = SearchCall(method, path, body)
        calls += c
        return handler(c)
    }
}

private fun reply(body: String, status: Int = 200) = AuthResult.Success(HttpResponse(status, body))
private fun refusal(status: Int, code: String, message: String) = reply("""{"error":"$code","message":"$message"}""", status)

private fun organizer() =
    """{"id":"22222222-2222-4222-8222-222222222222","fullName":"Brian Otieno","handle":"brianotieno","course":"Law","yearOfStudy":"","interests":[],"studentVerified":true,"universityName":"CUK","avatarUrl":null}"""

private fun eventJson(id: String = "e1", category: String = "career", mine: String = "\"registered\"", status: String = "published") =
    """{"id":"$id","organizer":${organizer()},"title":"Career fair","description":"Meet employers","category":"$category","venueName":"Main hall",
       "startsAt":"2030-01-01T10:00:00.000Z","endsAt":"2030-01-01T12:00:00.000Z","capacity":10,"registeredCount":3,"spotsLeft":7,
       "visibility":"university","attendeesVisible":true,"status":"$status","coverUrl":null,"myRegistration":$mine,"isOrganizer":false}"""

private fun page(vararg items: String, next: String? = null) =
    reply("""{"items":[${items.joinToString(",")}],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}""")

class RemoteEventRepositoryTest {
    @Test fun readsServerEventsAndMapsTheFields() = runTest {
        val api = ScriptedApi { page(eventJson()) } // the same event comes back for upcoming and past: it is listed once
        val repo = RemoteEventRepository(api)
        val events = repo.getEvents()
        assertEquals(1, events.size)
        val e = events.single()
        assertEquals("Career", e.category)
        assertEquals(RsvpState.Going, e.rsvp)
        assertEquals(3, e.going)
        assertEquals("Brian Otieno", e.hostName)
        assertFalse(e.cancelled)
        assertTrue(api.calls.any { it.path.contains("scope=upcoming") } && api.calls.any { it.path.contains("scope=past") })
    }

    @Test fun followsPagesUntilTheServerRunsOut() = runTest {
        val api = ScriptedApi { c ->
            if (c.path.contains("scope=past")) page()
            else if (c.path.contains("cursor=c1")) page(eventJson("e2")) else page(eventJson("e1"), next = "c1")
        }
        assertEquals(listOf("e1", "e2"), RemoteEventRepository(api).getEvents().map { it.id })
    }

    @Test fun unknownEventIsNullAndNotAnError() = runTest {
        assertNull(RemoteEventRepository(ScriptedApi { refusal(404, "not_found", "Not found.") }).getEvent("nope"))
    }

    @Test fun theWholeResponseIsSetInOneCall_soNobodyIsBothInterestedAndGoing() = runTest {
        val api = ScriptedApi { reply("{}") }
        val repo = RemoteEventRepository(api)
        repo.setRsvp("e1", RsvpState.Going)
        repo.setRsvp("e1", RsvpState.Interested)
        repo.setRsvp("e1", RsvpState.None)
        assertEquals(List(3) { "PUT /api/v1/events/e1/rsvp" }, api.calls.map { "${it.method} ${it.path}" })
        assertEquals(listOf("going", "interested", "none"), api.calls.map { it.body!!.getString("state") })
    }

    @Test fun interestedIsOfferedAndMapsFromTheServersResponse() = runTest {
        val repo = RemoteEventRepository(ScriptedApi { page(eventJson(mine = "null").replace("\"isOrganizer\":false", "\"isOrganizer\":false,\"myRsvp\":\"interested\",\"interestedCount\":4")) })
        assertTrue(repo.supportsInterested)
        val e = repo.getEvents().single()
        assertEquals(RsvpState.Interested, e.rsvp)
        assertEquals(4, e.interested)
    }

    @Test fun aFullEventSurfacesTheServersReasonAsAStateProblem() = runTest {
        val repo = RemoteEventRepository(ScriptedApi { refusal(409, "event_full", "This event is full.") })
        try { repo.setRsvp("e1", RsvpState.Going); fail() } catch (e: IllegalStateException) { assertEquals("This event is full.", e.message) }
    }

    @Test fun creatingSendsServerCategoryAndIsoTimes_andRefusesCommunityHosting() = runTest {
        val api = ScriptedApi { reply(eventJson("new1"), 201) }
        val repo = RemoteEventRepository(api)
        val draft = NewEvent("Career fair", "Meet employers", "Career", 1_900_000_000_000L, 1_900_003_600_000L, "Main hall", null, null, 50, 0, null)
        assertEquals("new1", repo.createEvent(draft))
        val sent = api.calls.single().body!!
        assertEquals("career", sent.getString("category"))
        assertEquals("Main hall", sent.getString("venueName"))
        assertTrue(sent.getString("startsAt").endsWith("Z"))
        try { repo.createEvent(draft.copy(hostCommunityId = "c1")); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, api.calls.size) // the refused draft never reached the server
    }
}

class RemoteEventDiscoveryTest {
    private fun withReason(id: String, reason: String) = eventJson(id = id).replace("\"isOrganizer\":false", "\"isOrganizer\":false,\"reason\":\"$reason\"")

    @Test fun sectionsKeepTheServersOrderTitlesAndReasons() = runTest {
        val api = ScriptedApi {
            reply("""{"sections":[
              {"key":"communities","title":"From your communities","items":[${withReason("a", "From a community you joined")}]},
              {"key":"online","title":"Online events","items":[${eventJson(id = "b")}]}]}""")
        }
        val repo = RemoteEventRepository(api)
        assertTrue(repo.supportsDiscover)
        val sections = repo.discoverSections()
        assertEquals("GET /api/v1/discover/events/sections", api.calls.single().let { "${it.method} ${it.path}" })
        assertEquals(listOf("communities", "online"), sections.map { it.key })
        assertEquals("From your communities", sections[0].title)
        assertEquals("From a community you joined", sections[0].events.single().reason)
        assertNull(sections[1].events.single().reason)
    }

    @Test fun everyFilterIsSentToTheServer() = runTest {
        val api = ScriptedApi { page(eventJson(id = "a"), next = "abc") }
        val repo = RemoteEventRepository(api)
        val result = repo.discoverEvents(
            com.univibe.app.data.model.EventDiscoverFilter(
                categories = setOf("music", "technology"), onlineOnly = true, registrationOpen = true, savedOnly = true, toMillis = 1_900_000_000_000L,
            ),
            cursor = "c1",
        )
        val path = api.calls.single().path
        assertTrue(path, path.startsWith("/api/v1/discover/events?"))
        for (part in listOf("category=music,technology", "format=online,hybrid", "registrationOpen=true", "saved=true", "cursor=c1")) assertTrue("$part in $path", part in path)
        assertTrue(path, "to=2030-" in path.replace("%3A", ":"))
        assertEquals("abc", result.nextCursor)
        assertEquals(listOf("a"), result.events.map { it.id })
    }

    @Test fun anUnfilteredQueryAsksForNothingExtra() = runTest {
        val api = ScriptedApi { page() }
        RemoteEventRepository(api).discoverEvents(com.univibe.app.data.model.EventDiscoverFilter())
        assertEquals("/api/v1/discover/events?limit=20", api.calls.single().path)
    }

    @Test fun categoriesComeFromTheServer() = runTest {
        val repo = RemoteEventRepository(ScriptedApi { reply("""{"items":[{"code":"study_groups","label":"Study Groups"},{"code":"other","label":"Other"}]}""") })
        assertEquals(listOf("study_groups" to "Study Groups", "other" to "Other"), repo.eventCategories().map { it.code to it.label })
    }

    @Test fun notInterestedAndUndoUseTheRightCalls() = runTest {
        val api = ScriptedApi { reply("", 204) }
        val repo = RemoteEventRepository(api)
        repo.setNotInterested("e1", true)
        repo.setNotInterested("e1", false)
        assertEquals(listOf("PUT /api/v1/discover/events/e1/not-interested", "DELETE /api/v1/discover/events/e1/not-interested"), api.calls.map { "${it.method} ${it.path}" })
    }

    @Test fun theFilterKnowsWhenItIsOn() {
        val none = com.univibe.app.data.model.EventDiscoverFilter()
        assertFalse(none.isActive)
        assertTrue(none.copy(savedOnly = true).isActive)
        assertTrue(none.copy(categories = setOf("music")).isActive)
        assertTrue(none.copy(toMillis = 1L).isActive)
    }
}

class RemoteDiscoverContentTest {
    @Test fun parsesReasonsAndTheNextCursor() = runTest {
        val post = """{"id":"p1","author":${organizer()},"ageSeconds":60,"text":"hi","audience":"campus","likeCount":0,"commentCount":0,"shareCount":0,
            "likedByMe":false,"savedByMe":false,"edited":false,"media":[],"imagePaths":[],"poll":null,"reason":"following"}"""
        val api = ScriptedApi { page(post, next = "abc") }
        val result = RemoteDiscoverContentRepository(api).forYou(null)
        assertEquals("following", result.posts.single().discoverReason)
        assertEquals("abc", result.nextCursor)
        RemoteDiscoverContentRepository(ScriptedApi { page(post.replace("\"following\"", "null")) }).forYou("abc").let {
            assertNull(it.posts.single().discoverReason)
            assertNull(it.nextCursor)
        }
    }

    @Test fun feedbackGoesToTheServerWithTheRightKind() = runTest {
        val api = ScriptedApi { reply("", 204) }
        val repo = RemoteDiscoverContentRepository(api)
        repo.notInterested("p1"); repo.hideAuthor("u1"); repo.muteTopic("exams")
        assertEquals(listOf("not_interested", "hide_author", "mute_topic"), api.calls.map { it.body!!.getString("kind") })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    @Before fun setUp() { dispatcher = StandardTestDispatcher(); Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeSearch(val latency: (String) -> Long = { 0 }) : SearchRepository {
        val asked = mutableListOf<String>()
        var failing = false
        override suspend fun search(query: String): SearchOverview {
            asked += query
            delay(latency(query))
            if (failing) throw IOException("down")
            return SearchOverview(emptyList(), emptyList(), emptyList())
        }
    }

    private fun vm(repo: SearchRepository, recents: InMemoryRecentSearches = InMemoryRecentSearches()) = SearchViewModel(repo, recents, debounceMs = 300)

    @Test fun waitsForAPauseInTypingThenAsksOnce() {
        val repo = FakeSearch()
        val vm = vm(repo)
        vm.onQuery("ch"); dispatcher.scheduler.advanceTimeBy(100)
        vm.onQuery("che"); dispatcher.scheduler.advanceTimeBy(100)
        vm.onQuery("chem"); dispatcher.scheduler.advanceTimeBy(299); dispatcher.scheduler.runCurrent()
        assertTrue(repo.asked.isEmpty())
        dispatcher.scheduler.advanceTimeBy(2); dispatcher.scheduler.runCurrent()
        assertEquals(listOf("chem"), repo.asked)
    }

    @Test fun tooShortQueriesNeverReachTheServerAndClearOldResults() {
        val repo = FakeSearch()
        val vm = vm(repo)
        vm.onQuery("chem"); dispatcher.scheduler.advanceTimeBy(400); dispatcher.scheduler.runCurrent()
        assertNotNull(vm.state.value.results)
        vm.onQuery("c"); dispatcher.scheduler.advanceTimeBy(400); dispatcher.scheduler.runCurrent()
        assertEquals(listOf("chem"), repo.asked)
        assertNull(vm.state.value.results)
        assertTrue(vm.state.value.tooShort)
    }

    @Test fun aSlowAnswerForAnOlderQueryNeverOverwritesTheNewerOne() {
        val repo = FakeSearch(latency = { if (it == "old") 5_000 else 10 })
        val vm = vm(repo)
        vm.onQuery("old"); dispatcher.scheduler.advanceTimeBy(350) // "old" is now in flight and slow
        vm.onQuery("new"); dispatcher.scheduler.advanceTimeBy(400); dispatcher.scheduler.runCurrent()
        assertEquals("new", vm.state.value.resultsFor)
        dispatcher.scheduler.advanceTimeBy(10_000); dispatcher.scheduler.runCurrent()
        assertEquals("new", vm.state.value.resultsFor) // the abandoned request finished late and was ignored
    }

    @Test fun failuresShowAMessageAndRetryRecovers() {
        val repo = FakeSearch().apply { failing = true }
        val vm = vm(repo)
        vm.onQuery("chem"); dispatcher.scheduler.advanceTimeBy(400); dispatcher.scheduler.runCurrent()
        assertNotNull(vm.state.value.error)
        repo.failing = false
        vm.retry(); dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.error)
        assertNotNull(vm.state.value.results)
    }

    @Test fun onlySearchesThatLedSomewhereAreRemembered() {
        val recents = InMemoryRecentSearches()
        val vm = vm(FakeSearch(), recents)
        vm.onQuery("chemistry"); dispatcher.scheduler.advanceTimeBy(400); dispatcher.scheduler.runCurrent()
        assertTrue(recents.all().isEmpty())
        vm.remember()
        assertEquals(listOf("chemistry"), vm.state.value.recents)
        vm.forget("chemistry")
        assertTrue(vm.state.value.recents.isEmpty())
    }
}
