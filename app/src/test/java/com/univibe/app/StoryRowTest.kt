package com.univibe.app

import com.univibe.app.data.model.Note
import com.univibe.app.data.model.NoteAudience
import com.univibe.app.data.model.NotesSnapshot
import com.univibe.app.data.model.StoryTrayEntry
import com.univibe.app.data.repository.NoteRepository
import com.univibe.app.data.repository.StoryRepository
import com.univibe.app.data.repository.parseStoryTray
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.AuthUser
import com.univibe.app.features.chats.notes.NotesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

private const val S0 = 1_800_000_000_000L
private fun isoS(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()

class StoryTrayParsingTest {
    @Test fun readsEntriesAndFlags() {
        val body = """{"serverTime":"${isoS(S0)}","items":[
          {"author":{"id":"a","handle":"ha","fullName":"Ann Lee","avatarUrl":null,"studentVerified":true},"count":2,"latestAt":"${isoS(S0 - 1000)}","hasUnviewed":true,"isMine":false},
          {"author":{"id":"me","handle":"hm","fullName":"Me","avatarUrl":"https://img/me.webp","studentVerified":true},"count":1,"latestAt":"${isoS(S0)}","hasUnviewed":false,"isMine":true}
        ]}"""
        val tray = parseStoryTray(body)
        assertEquals(listOf("a", "me"), tray.map { it.authorId })
        assertTrue(tray[0].hasUnviewed); assertFalse(tray[0].isMine); assertNull(tray[0].avatarUrl)
        assertEquals(2, tray[0].count)
        assertTrue(tray[1].isMine); assertEquals("https://img/me.webp", tray[1].avatarUrl)
    }

    @Test fun emptyTrayIsEmpty() {
        assertEquals(0, parseStoryTray("""{"serverTime":"${isoS(S0)}","items":[]}""").size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StoryRowViewModelTest {
    private lateinit var dispatcher: kotlinx.coroutines.test.TestDispatcher

    private class FakeNotes(var items: List<Note> = emptyList()) : NoteRepository {
        override suspend fun getNotes() = NotesSnapshot(mine = null, items = items, maxChars = 60, ttlHours = 24, clockOffsetMs = 0)
        override suspend fun publish(text: String, audience: NoteAudience): Note = throw IOException()
        override suspend fun deleteMine() {}
        override suspend fun setMuted(userId: String, muted: Boolean) {}
    }

    private class FakeStories(var tray: List<StoryTrayEntry> = emptyList()) : StoryRepository {
        var fail = false
        var loads = 0
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override suspend fun tray(): List<StoryTrayEntry> { loads++; if (fail) throw IOException("down"); return tray }
        override fun changes(): Flow<Unit> = events
    }

    private val me = AuthUser(
        id = "me", fullName = "Me Myself", email = "me@x.ac", universityId = "u", universityName = "U",
        emailVerified = true, studentVerified = true, avatarUrl = "https://img/me.webp",
    )

    @Before fun setUp() { dispatcher = UnconfinedTestDispatcher(); Dispatchers.setMain(dispatcher) }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun clock() = S0 + dispatcher.scheduler.currentTime
    private fun vm(notes: FakeNotes, stories: FakeStories) =
        NotesViewModel(notes, flowOf(AuthState.Authenticated(me)), ::clock, storyRepository = stories)

    private fun note(author: String, createdMs: Long = S0) = Note(
        id = "n-$author", authorId = author, authorName = "Name $author", authorHandle = "h$author", avatarUrl = null,
        text = "hi $author", audience = NoteAudience.Campus, version = 1, createdAtMs = createdMs, expiresAtMs = S0 + 3_600_000L,
    )

    private fun entry(author: String, unviewed: Boolean, latest: Long = S0, mine: Boolean = false) = StoryTrayEntry(
        authorId = author, authorName = "Name $author", authorHandle = "h$author", avatarUrl = null,
        count = 1, latestAtMs = latest, hasUnviewed = unviewed, isMine = mine,
    )

    @Test fun allFourStatesAreRepresented() {
        val v = vm(
            FakeNotes(listOf(note("both"), note("noteOnly"))),
            FakeStories(listOf(entry("both", true), entry("storyOnly", false))),
        )
        val people = v.state.value.people(withStories = true).associateBy { it.authorId }
        assertEquals(setOf("both", "noteOnly", "storyOnly"), people.keys) // "neither" is simply not listed
        assertNotNull(people.getValue("both").note); assertNotNull(people.getValue("both").story)
        assertNotNull(people.getValue("noteOnly").note); assertNull(people.getValue("noteOnly").story)
        assertNull(people.getValue("storyOnly").note); assertNotNull(people.getValue("storyOnly").story)
    }

    @Test fun unseenStoriesComeFirstThenMostRecent() {
        val v = vm(
            FakeNotes(listOf(note("n1", createdMs = S0 - 10_000))),
            FakeStories(listOf(entry("seen", false, latest = S0), entry("fresh", true, latest = S0 - 50_000))),
        )
        assertEquals(listOf("fresh", "seen", "n1"), v.state.value.people(true).map { it.authorId })
    }

    @Test fun withoutTheViewerNoRingsAndNoStoryOnlyPeople() {
        val v = vm(FakeNotes(listOf(note("a"))), FakeStories(listOf(entry("a", true), entry("b", true))))
        val people = v.state.value.people(withStories = false)
        assertEquals(listOf("a"), people.map { it.authorId })
        assertNull(people[0].story)
    }

    @Test fun myOwnStoryIsNeverListedAsSomeoneElse() {
        val v = vm(FakeNotes(), FakeStories(listOf(entry("me", true, mine = true))))
        assertNotNull(v.state.value.myStory)
        assertTrue(v.state.value.people(true).isEmpty())
    }

    @Test fun aFailedStoriesLoadKeepsNotesAndTheLastRings() {
        val stories = FakeStories(listOf(entry("a", true)))
        val v = vm(FakeNotes(listOf(note("a"))), stories)
        assertEquals(1, v.state.value.stories.size)
        stories.fail = true
        v.refresh()
        assertEquals(1, v.state.value.stories.size) // rings stay
        assertEquals(1, v.state.value.visibleOthers.size) // Notes untouched
        assertNull(v.state.value.error)
    }

    @Test fun aFailedNotesLoadDoesNotHideStories() {
        val notes = object : NoteRepository {
            override suspend fun getNotes(): NotesSnapshot = throw IOException("down")
            override suspend fun publish(text: String, audience: NoteAudience): Note = throw IOException()
            override suspend fun deleteMine() {}
            override suspend fun setMuted(userId: String, muted: Boolean) {}
        }
        val v = NotesViewModel(notes, flowOf(AuthState.Authenticated(me)), ::clock, storyRepository = FakeStories(listOf(entry("a", true))))
        assertEquals(listOf("a"), v.state.value.people(true).map { it.authorId })
    }

    @Test fun aStoryEventReloadsTheTrayAfterAShortPause() {
        val stories = FakeStories(listOf(entry("a", true)))
        val v = vm(FakeNotes(), stories)
        val before = stories.loads
        stories.tray = listOf(entry("a", false))
        stories.events.tryEmit(Unit)
        dispatcher.scheduler.advanceTimeBy(400)
        assertTrue(stories.loads > before)
        assertFalse(v.state.value.stories.single().hasUnviewed) // ring turns muted without leaving the screen
    }
}
