package com.univibe.app

import com.univibe.app.data.model.Note
import com.univibe.app.data.model.NoteAudience
import com.univibe.app.data.model.NoteDraftCheck
import com.univibe.app.data.model.NotesSnapshot
import com.univibe.app.data.model.activeLabel
import com.univibe.app.data.model.checkNoteDraft
import com.univibe.app.data.model.noteLength
import com.univibe.app.data.net.ChatEvent
import com.univibe.app.data.repository.NoteRepository
import com.univibe.app.data.repository.parseNotesSnapshot
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.AuthUser
import com.univibe.app.features.chats.notes.NotesSheet
import com.univibe.app.features.chats.notes.NotesViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

private const val T0 = 1_800_000_000_000L // an arbitrary "now" in epoch ms
private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()

private fun noteJson(id: String, author: String, text: String, createdMs: Long, expiresMs: Long) = JSONObject()
    .put("id", id).put("text", text).put("audience", "campus").put("version", 1)
    .put("createdAt", iso(createdMs)).put("expiresAt", iso(expiresMs))
    .put("author", JSONObject().put("id", author).put("fullName", "Name $author").put("handle", "h$author").put("avatarUrl", JSONObject.NULL))

private fun note(author: String, text: String, expiresMs: Long, createdMs: Long = T0) = Note(
    id = "n-$author", authorId = author, authorName = "Name $author", authorHandle = "h$author", avatarUrl = null,
    text = text, audience = NoteAudience.Campus, version = 1, createdAtMs = createdMs, expiresAtMs = expiresMs,
)

class NoteRulesTest {
    @Test fun emojiCountsAsOneCharacter() {
        assertEquals(3, noteLength("😀😀a"))
        assertEquals(60, noteLength("😀".repeat(60)))
    }

    @Test fun blankAndWhitespaceOnlyAreRefused() {
        assertEquals(NoteDraftCheck.Empty, checkNoteDraft("", 60))
        assertEquals(NoteDraftCheck.Empty, checkNoteDraft("  \n ", 60))
    }

    @Test fun limitIsEnforcedOnTrimmedTextAndNeverTruncates() {
        assertEquals(NoteDraftCheck.Ok, checkNoteDraft("  " + "a".repeat(60) + "  ", 60))
        assertEquals(NoteDraftCheck.TooLong(61, 60), checkNoteDraft("a".repeat(61), 60))
    }

    @Test fun parsesNoteChangedEvent() {
        val updated = ChatEvent.parse(JSONObject("""{"type":"note:changed","userId":"u1","kind":"updated"}"""))
        val deleted = ChatEvent.parse(JSONObject("""{"type":"note:changed","userId":"u1","kind":"deleted"}"""))
        assertEquals(ChatEvent.NoteChanged("u1", deleted = false), updated)
        assertEquals(ChatEvent.NoteChanged("u1", deleted = true), deleted)
    }
}

class NoteParsingTest {
    private val body = JSONObject()
        .put("serverTime", iso(T0 + 60_000)) // the server's clock is one minute ahead of the phone
        .put("limits", JSONObject().put("maxChars", 60).put("ttlHours", 24))
        .put("mine", JSONObject.NULL)
        .put("items", org.json.JSONArray().put(noteJson("1", "a", "hello 😀", T0, T0 + 3_600_000)))
        .toString()

    @Test fun freshAnswerMeasuresTheServersClockOffset() {
        val snap = parseNotesSnapshot(body, deviceNowMs = T0, trustServerClock = true)
        assertEquals(60_000L, snap.clockOffsetMs)
        assertNull(snap.mine)
        assertEquals("hello 😀", snap.items.single().text)
        assertEquals(60, snap.maxChars)
    }

    @Test fun aSavedCopyUsesThePhonesClockInstead() {
        // A cached answer's server time is old, so trusting it would keep expired Notes on screen.
        val snap = parseNotesSnapshot(body, deviceNowMs = T0, trustServerClock = false)
        assertEquals(0L, snap.clockOffsetMs)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {
    private lateinit var dispatcher: kotlinx.coroutines.test.TestDispatcher

    private class FakeNotes(var snapshot: NotesSnapshot) : NoteRepository {
        val published = mutableListOf<Pair<String, NoteAudience>>()
        var deletes = 0
        var loads = 0
        var failNext = false
        var gate: CompletableDeferred<Unit>? = null
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override suspend fun getNotes(): NotesSnapshot { loads++; return snapshot }
        override suspend fun publish(text: String, audience: NoteAudience): Note {
            published += text to audience
            gate?.await()
            if (failNext) throw IOException("nope")
            val saved = note("me", text, T0 + 24 * 3_600_000L)
            snapshot = snapshot.copy(mine = saved)
            return saved
        }
        override suspend fun deleteMine() { deletes++; if (failNext) throw IOException("nope"); snapshot = snapshot.copy(mine = null) }
        override suspend fun setMuted(userId: String, muted: Boolean) {}
        override fun changes(): Flow<Unit> = events
    }

    private fun snapshot(mine: Note? = null, items: List<Note> = emptyList()) =
        NotesSnapshot(mine = mine, items = items, maxChars = 60, ttlHours = 24, clockOffsetMs = 0)

    private val me = AuthUser(
        id = "me", fullName = "Me Myself", email = "me@x.ac", universityId = "u", universityName = "U",
        emailVerified = true, studentVerified = true, avatarUrl = "https://img/me.webp",
    )

    @Before fun setUp() { dispatcher = UnconfinedTestDispatcher(); Dispatchers.setMain(dispatcher) }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun clock() = T0 + dispatcher.scheduler.currentTime
    private fun vm(repo: FakeNotes) = NotesViewModel(repo, flowOf(AuthState.Authenticated(me)), ::clock)

    @Test fun showsMyRealAvatarAndOthersNotes() {
        val vm = vm(FakeNotes(snapshot(items = listOf(note("a", "hi", T0 + 1_000_000)))))
        val s = vm.state.value
        assertEquals("https://img/me.webp", s.me?.avatarUrl)
        assertEquals(listOf("a"), s.visibleOthers.map { it.authorId })
        assertFalse(s.loading)
    }

    @Test fun anExpiredNoteDisappearsWithoutAReload() {
        val repo = FakeNotes(snapshot(items = listOf(note("a", "soon", T0 + 10_000))))
        val vm = vm(repo)
        assertEquals(1, vm.state.value.visibleOthers.size)
        dispatcher.scheduler.advanceTimeBy(11_000)
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.visibleOthers.isEmpty())
        assertEquals(1, repo.loads) // removed by the expiry timer, not by asking the server again
    }

    @Test fun publishRejectsBlankAndOverLimitDraftsWithoutCallingTheServer() {
        val repo = FakeNotes(snapshot())
        val vm = vm(repo)
        vm.onAddNoteClick()
        vm.setDraft("   ")
        vm.publish()
        vm.setDraft("x".repeat(61))
        vm.publish()
        assertTrue(repo.published.isEmpty())
        assertEquals(NotesSheet.Compose, vm.state.value.sheet)
    }

    @Test fun publishShowsTheNoteAtOnceAndClosesTheSheet() {
        val repo = FakeNotes(snapshot())
        val vm = vm(repo)
        vm.onAddNoteClick()
        vm.setDraft("  Busy today  ")
        vm.publish()
        assertEquals(listOf("Busy today" to NoteAudience.Campus), repo.published) // trimmed, not otherwise changed
        assertEquals("Busy today", vm.state.value.myActive?.text)
        assertEquals(NotesSheet.None, vm.state.value.sheet)
    }

    @Test fun aDoubleTapPublishesOnce() {
        val repo = FakeNotes(snapshot()).also { it.gate = CompletableDeferred() }
        val vm = vm(repo)
        vm.onAddNoteClick()
        vm.setDraft("once")
        vm.publish()
        vm.publish()
        assertEquals(1, repo.published.size)
        repo.gate!!.complete(Unit)
    }

    @Test fun aFailedPublishKeepsTheDraftAndSaysWhy() {
        val repo = FakeNotes(snapshot()).also { it.failNext = true }
        val vm = vm(repo)
        vm.onAddNoteClick()
        vm.setDraft("hello")
        vm.publish()
        val s = vm.state.value
        assertEquals("hello", s.draft)
        assertEquals(NotesSheet.Compose, s.sheet)
        assertTrue(s.publishError != null)
        assertFalse(s.publishing)
    }

    @Test fun aFailedDeleteRestoresTheNote() {
        val mine = note("me", "keep me", T0 + 1_000_000)
        val repo = FakeNotes(snapshot(mine = mine)).also { it.failNext = true }
        val vm = vm(repo)
        vm.deleteMine()
        assertEquals("keep me", vm.state.value.mine?.text)
        assertTrue(vm.state.value.notice != null)
    }

    @Test fun aServerChangeEventOrReconnectReloadsFromTheServer() {
        val repo = FakeNotes(snapshot())
        val vm = vm(repo)
        val before = repo.loads
        repo.snapshot = snapshot(items = listOf(note("b", "new", T0 + 1_000_000)))
        repo.events.tryEmit(Unit)
        dispatcher.scheduler.advanceTimeBy(500)
        dispatcher.scheduler.runCurrent()
        assertTrue(repo.loads > before)
        assertEquals(listOf("b"), vm.state.value.visibleOthers.map { it.authorId })
    }
}

class ActiveLabelTest {
    private val min = 60_000L
    @Test fun onlineIsActiveNowWhateverTheTime() = assertEquals("Active now", activeLabel(true, null, T0))
    @Test fun recentAndOlderTimesReadNaturally() {
        assertEquals("Active now", activeLabel(false, T0 - 20_000, T0))
        assertEquals("Active 5m ago", activeLabel(false, T0 - 5 * min, T0))
        assertEquals("Active 3h ago", activeLabel(false, T0 - 180 * min, T0))
        assertEquals("Active yesterday", activeLabel(false, T0 - 30 * 60 * min, T0))
        assertEquals("Active 4d ago", activeLabel(false, T0 - 4 * 24 * 60 * min, T0))
    }
    @Test fun privateUnknownOrVeryOldShowsNothingNeverOffline() {
        assertNull(activeLabel(false, null, T0))
        assertNull(activeLabel(false, T0 - 40L * 24 * 60 * min, T0))
    }
}

class NotePresenceParsingTest {
    private fun body(presence: Any?) = JSONObject()
        .put("serverTime", iso(T0)).put("limits", JSONObject().put("maxChars", 60).put("ttlHours", 24)).put("mine", JSONObject.NULL)
        .put("items", org.json.JSONArray().put(noteJson("1", "a", "hi", T0, T0 + 3_600_000).put("presence", presence)))
        .toString()

    @Test fun readsOnlineAndLastActive() {
        val p = JSONObject().put("online", true).put("lastActiveAt", iso(T0 - 1000))
        val n = parseNotesSnapshot(body(p), T0, true).items.single()
        assertTrue(n.online)
        assertEquals(T0 - 1000, n.lastActiveMs)
    }

    @Test fun aPrivatePersonHasNoDotAndNoTime() {
        val n = parseNotesSnapshot(body(JSONObject.NULL), T0, true).items.single()
        assertFalse(n.online)
        assertNull(n.lastActiveMs)
    }
}
