package com.univibe.app

import com.univibe.app.data.model.Channel
import com.univibe.app.data.model.ChannelAccess
import com.univibe.app.data.model.ChannelDirectory
import com.univibe.app.data.model.ChannelDraft
import com.univibe.app.data.model.ChannelGroup
import com.univibe.app.data.model.ChannelMessage
import com.univibe.app.data.model.ChannelType
import com.univibe.app.data.model.ReplyPreview
import com.univibe.app.data.model.latestSeq
import com.univibe.app.data.model.tombstoned
import com.univibe.app.data.model.upserted
import com.univibe.app.data.model.upsertedAll
import com.univibe.app.data.model.withReaction
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.ChatEvent
import com.univibe.app.data.repository.ChannelRepository
import com.univibe.app.data.repository.ReactionState
import com.univibe.app.data.repository.SearchFilters
import com.univibe.app.data.repository.ThreadPage
import com.univibe.app.data.repository.parseChannelMessage
import com.univibe.app.data.model.MessagePage
import com.univibe.app.features.communities.ChannelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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

private fun msg(
    id: String, seq: Long, content: String? = "hi", replyTo: String? = null, deleted: Boolean = false,
    reactions: Map<String, Int> = emptyMap(), mine: Set<String> = emptySet(), replyCount: Int = 0,
) = ChannelMessage(
    id = id, channelId = "ch", seq = seq, author = null, authorRole = null, title = null, content = content, deleted = deleted,
    removedByModerators = false, createdAt = "2026-01-01T10:00:00Z", edited = false, pinned = false, replyCount = replyCount,
    replyTo = replyTo?.let { ReplyPreview(it, "x", false) }, acceptedAnswerId = null, isAcceptedAnswer = false, attachments = emptyList(),
    reactions = reactions, myReactions = mine,
)

class ChannelModelsTest {
    @Test fun `the same message arriving twice is one row, ordered by message number`() {
        val list = listOf(msg("a", 1), msg("c", 3)).upserted(msg("b", 2)).upserted(msg("b", 2, content = "edited"))
        assertEquals(listOf("a", "b", "c"), list.map { it.id })
        assertEquals("edited", list[1].content)
    }

    @Test fun `a socket update keeps my own reactions because the payload is viewer-neutral`() {
        val mine = listOf(msg("a", 1, reactions = mapOf("heart" to 1), mine = setOf("heart")))
        val updated = mine.upserted(msg("a", 1, reactions = mapOf("heart" to 2)), keepMine = true)
        assertEquals(setOf("heart"), updated[0].myReactions)
        assertEquals(2, updated[0].reactions["heart"])
        assertEquals(emptySet<String>(), mine.upserted(msg("a", 1), keepMine = false)[0].myReactions)
    }

    @Test fun `a deleted message keeps its row and loses its words`() {
        val list = listOf(msg("a", 1, "secret"), msg("b", 2, replyTo = "a")).tombstoned("a")
        assertTrue(list[0].deleted)
        assertNull(list[0].content)
        assertEquals("a", list[1].replyTo?.id) // the reply still points at it
    }

    @Test fun `a reaction event updates counts for everyone and my reactions only when it is mine`() {
        val base = listOf(msg("a", 1))
        val theirs = base.withReaction("a", "other", "fire", true, mapOf("fire" to 1), myId = "me")
        assertEquals(1, theirs[0].reactions["fire"]); assertTrue(theirs[0].myReactions.isEmpty())
        val mine = theirs.withReaction("a", "me", "fire", true, mapOf("fire" to 2), myId = "me")
        assertEquals(setOf("fire"), mine[0].myReactions)
        val removed = mine.withReaction("a", "me", "fire", false, mapOf("fire" to 1), myId = "me")
        assertTrue(removed[0].myReactions.isEmpty())
    }

    @Test fun `latest message number is the highest held`() {
        assertEquals(0L, emptyList<ChannelMessage>().latestSeq())
        assertEquals(7L, listOf(msg("a", 3), msg("b", 7)).latestSeq())
    }

    @Test fun `channels are listed by group with loose ones first`() {
        fun ch(id: String, group: String?) = Channel(id, group, id, "", ChannelType.Text, false, false, 0, 0, ChannelAccess())
        val d = ChannelDirectory(listOf(ChannelGroup("g1", "Academics"), ChannelGroup("g2", "Empty")), listOf(ch("general", null), ch("help", "g1"), ch("old", "gone")), canManage = false, canDelete = false)
        assertEquals(listOf(null, "g1"), d.sections.map { it.first?.id })
        assertEquals(listOf("general", "old"), d.sections[0].second.map { it.id }) // a channel whose group is missing is not lost
    }

    @Test fun `channel types decide titles and timelines`() {
        assertTrue(ChannelType.Qa.needsTitle && ChannelType.Announcement.needsTitle)
        assertFalse(ChannelType.Text.needsTitle)
        assertTrue(ChannelType.Qa.threadedTimeline && !ChannelType.Media.threadedTimeline)
        assertEquals(ChannelType.Text, ChannelType.parse("unknown"))
    }

    @Test fun `a server message is parsed, including deleted tombstones and reply previews`() {
        val m = parseChannelMessage(JSONObject("""{"id":"m1","channelId":"c1","seq":5,"author":{"id":"u1","fullName":"Ada","handle":"ada","avatarUrl":null,"studentVerified":true},
            "authorRole":"moderator","title":null,"content":"hello","deleted":false,"removedByModerators":false,"createdAt":"2026-01-01T10:00:00Z","editedAt":"2026-01-01T10:05:00Z",
            "pinned":true,"replyCount":2,"replyTo":{"id":"m0","authorId":"u2","preview":"earlier","deleted":false},"acceptedAnswerId":null,"isAcceptedAnswer":false,
            "media":[{"id":"f1","kind":"document","contentType":"application/pdf","url":"https://x/f","width":null,"height":null,"fileName":"notes.pdf","sizeBytes":1024}],
            "reactions":{"heart":2},"myReactions":["heart"]}"""))
        assertEquals(5L, m.seq); assertEquals("Ada", m.author?.name); assertEquals("@ada", m.author?.handle)
        assertTrue(m.edited && m.pinned && m.isStaffAuthor)
        assertEquals("earlier", m.replyTo?.preview)
        assertEquals("notes.pdf", m.attachments[0].fileName); assertFalse(m.attachments[0].isImage)
        assertEquals(setOf("heart"), m.myReactions)
        val gone = parseChannelMessage(JSONObject("""{"id":"m2","channelId":"c1","seq":6,"author":null,"authorRole":null,"title":null,"content":null,"deleted":true,"removedByModerators":true,
            "createdAt":"2026-01-01T10:00:00Z","editedAt":null,"pinned":false,"replyCount":0,"replyTo":null,"media":[],"reactions":{}}"""))
        assertTrue(gone.deleted && gone.removedByModerators); assertNull(gone.content); assertNull(gone.author)
    }

    @Test fun `channel events from the socket are understood`() {
        val m = """{"id":"m1","channelId":"c1","seq":1,"createdAt":"2026-01-01T10:00:00Z","content":"x","deleted":false,"reactions":{}}"""
        val n = ChatEvent.parse(JSONObject("""{"type":"channel:message","communityId":"k","channelId":"c1","message":$m}""")) as ChatEvent.ChannelMessageNew
        assertEquals("c1", n.channelId)
        assertTrue(ChatEvent.parse(JSONObject("""{"type":"channel:message:updated","communityId":"k","channelId":"c1","message":$m}""")) is ChatEvent.ChannelMessageUpdated)
        val d = ChatEvent.parse(JSONObject("""{"type":"channel:message:deleted","communityId":"k","channelId":"c1","messageId":"m1"}""")) as ChatEvent.ChannelMessageDeleted
        assertEquals("m1", d.messageId)
        val r = ChatEvent.parse(JSONObject("""{"type":"channel:reaction","communityId":"k","channelId":"c1","messageId":"m1","userId":"u","reaction":"heart","active":true,"reactions":{"heart":1}}""")) as ChatEvent.ChannelReaction
        assertTrue(r.active); assertEquals(1, r.reactions.getInt("heart"))
        val c = ChatEvent.parse(JSONObject("""{"type":"channel:changed","communityId":"k","channelId":null,"kind":"reordered"}""")) as ChatEvent.ChannelChanged
        assertNull(c.channelId)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModelTest {
    private class FakeChannels : ChannelRepository {
        val events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 16)
        override val live = events
        override val myId: String? = "me"
        var type = ChannelType.Text
        var access = ChannelAccess(send = true, reply = true, react = true, attach = true)
        var channelVisible = true
        var history = listOf<ChannelMessage>()
        var sendError: Throwable? = null
        val sent = mutableListOf<ChannelDraft>()
        val catchUps = mutableListOf<Long>()
        val readUpTo = mutableListOf<Long>()
        private var nextSeq = 100L

        private fun channel() = Channel("ch", null, "general", "", type, false, false, 0, 0, access)
        override suspend fun directory(communityId: String) = ChannelDirectory(emptyList(), if (channelVisible) listOf(channel()) else emptyList(), false, false)
        override suspend fun history(communityId: String, channelId: String, cursor: String?) = MessagePage(history.sortedByDescending { it.seq }, null, history.latestSeq())
        override suspend fun catchUp(communityId: String, channelId: String, afterSeq: Long): List<ChannelMessage> { catchUps += afterSeq; return history.filter { it.seq > afterSeq } }
        override suspend fun send(communityId: String, channelId: String, draft: ChannelDraft): ChannelMessage {
            sent += draft
            sendError?.let { throw it }
            return msg("sent-${draft.clientId.take(4)}-${sent.size}", nextSeq++, draft.content, replyTo = draft.replyToId)
        }
        override suspend fun edit(communityId: String, channelId: String, messageId: String, title: String?, content: String) = msg(messageId, 1, content)
        override suspend fun delete(communityId: String, channelId: String, messageId: String) = Unit
        override suspend fun react(communityId: String, channelId: String, messageId: String, reaction: String, on: Boolean) = ReactionState(mapOf(reaction to if (on) 1 else 0), if (on) setOf(reaction) else emptySet())
        override suspend fun pin(communityId: String, channelId: String, messageId: String, on: Boolean) = msg(messageId, 1)
        override suspend fun pins(communityId: String, channelId: String) = emptyList<ChannelMessage>()
        override suspend fun thread(communityId: String, channelId: String, messageId: String, cursor: String?) = ThreadPage(msg(messageId, 1), emptyList(), null)
        override suspend fun markRead(communityId: String, channelId: String, upToSeq: Long) { readUpTo += upToSeq }
        override suspend fun search(communityId: String, channelId: String, filters: SearchFilters, cursor: String?) = MessagePage(emptyList(), null)
        override suspend fun acceptAnswer(communityId: String, channelId: String, questionId: String, answerId: String?) = msg(questionId, 1)
        override suspend fun createChannel(communityId: String, name: String, description: String, type: ChannelType) = "new"
        override suspend fun updateChannel(communityId: String, channelId: String, locked: Boolean?, archived: Boolean?) = Unit
        override suspend fun report(messageId: String, reason: String) = Unit
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun json(m: ChannelMessage): JSONObject {
        val o = JSONObject().put("id", m.id).put("channelId", "ch").put("seq", m.seq).put("createdAt", "2026-01-01T10:00:00Z")
            .put("content", m.content).put("deleted", false).put("reactions", JSONObject()).put("replyCount", 0)
        m.replyTo?.let { o.put("replyTo", JSONObject().put("id", it.id).put("preview", "x").put("deleted", false)) }
        return o
    }

    @Test fun `opening a channel shows the history oldest first and marks it read`() {
        val repo = FakeChannels().apply { history = listOf(msg("b", 2), msg("a", 1)) }
        val vm = ChannelViewModel("k", "ch", repo)
        assertEquals(listOf("a", "b"), vm.state.value.messages.map { it.id })
        assertFalse(vm.state.value.loading)
        assertEquals(listOf(2L), repo.readUpTo)
    }

    @Test fun `sending shows a pending row, then the server message replaces it`() {
        val repo = FakeChannels()
        val vm = ChannelViewModel("k", "ch", repo)
        vm.setText("hello")
        vm.send()
        assertTrue(vm.state.value.pending.isEmpty())
        assertEquals(1, vm.state.value.messages.size)
        assertEquals("hello", vm.state.value.messages[0].content)
        assertEquals("", vm.state.value.text)
    }

    @Test fun `a failed send stays visible and retrying reuses the same client id`() {
        val repo = FakeChannels().apply { sendError = IOException("offline") }
        val vm = ChannelViewModel("k", "ch", repo)
        vm.setText("hello"); vm.send()
        val p = vm.state.value.pending.single()
        assertTrue(p.failed)
        assertTrue(vm.state.value.messages.isEmpty())
        repo.sendError = null
        vm.retry(p.draft.clientId)
        assertTrue(vm.state.value.pending.isEmpty())
        assertEquals(2, repo.sent.size)
        assertEquals(repo.sent[0].clientId, repo.sent[1].clientId) // the server de-duplicates on this
    }

    @Test fun `a message that arrives over the socket and again in a fetch appears once`() {
        val repo = FakeChannels()
        val vm = ChannelViewModel("k", "ch", repo)
        val m = msg("x", 5, "live")
        repeat(2) { repo.events.tryEmit(ChatEvent.ChannelMessageNew("k", "ch", json(m))) }
        assertEquals(1, vm.state.value.messages.size)
        repo.history = listOf(m)
        repo.events.tryEmit(ChatEvent.Connected)
        assertEquals(1, vm.state.value.messages.size)
    }

    @Test fun `after a reconnect the missed messages are fetched from the last one held`() {
        val repo = FakeChannels().apply { history = listOf(msg("a", 1)) }
        val vm = ChannelViewModel("k", "ch", repo)
        repo.history = listOf(msg("a", 1), msg("b", 2), msg("c", 3))
        repo.events.tryEmit(ChatEvent.Connected)
        assertEquals(listOf(1L), repo.catchUps)
        assertEquals(listOf("a", "b", "c"), vm.state.value.messages.map { it.id })
    }

    @Test fun `events for another channel are ignored`() {
        val repo = FakeChannels()
        val vm = ChannelViewModel("k", "ch", repo)
        repo.events.tryEmit(ChatEvent.ChannelMessageNew("k", "other", JSONObject("""{"id":"z","channelId":"other","seq":1,"createdAt":"2026-01-01T10:00:00Z","content":"no","deleted":false,"reactions":{}}""")))
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test fun `losing access stops the screen at once, however it is noticed`() {
        // The server stops listing the channel.
        val a = FakeChannels(); val vmA = ChannelViewModel("k", "ch", a)
        a.channelVisible = false; a.events.tryEmit(ChatEvent.ChannelChanged("k", "ch", "updated"))
        assertTrue(vmA.state.value.accessLost)
        // Membership ends while the screen is open.
        val b = FakeChannels(); val vmB = ChannelViewModel("k", "ch", b)
        b.events.tryEmit(ChatEvent.CommunityMembership("k", "join", 10))
        assertTrue(vmB.state.value.accessLost)
        // A send is refused as "not found".
        val c = FakeChannels(); val vmC = ChannelViewModel("k", "ch", c)
        c.sendError = ApiException(404, "not_found", "That channel is not available.")
        vmC.setText("hi"); vmC.send()
        assertTrue(vmC.state.value.accessLost)
    }

    @Test fun `a locked channel refusal is a notice, not a lost channel`() {
        val repo = FakeChannels()
        val vm = ChannelViewModel("k", "ch", repo)
        repo.sendError = ApiException(403, "channel_locked", "This channel is locked.")
        repo.access = ChannelAccess(react = true, readOnlyReason = "channel_locked")
        vm.setText("hi"); vm.send()
        assertFalse(vm.state.value.accessLost)
        assertTrue(vm.state.value.pending.single().failed)
        assertFalse(vm.state.value.canSendNow) // the composer now explains instead of accepting text
    }

    @Test fun `in a question channel answers are counted, not listed in the timeline`() {
        val repo = FakeChannels().apply { type = ChannelType.Qa; history = listOf(msg("q", 1, "question")) }
        val vm = ChannelViewModel("k", "ch", repo)
        repo.events.tryEmit(ChatEvent.ChannelMessageNew("k", "ch", json(msg("a", 2, "answer", replyTo = "q"))))
        assertEquals(listOf("q"), vm.state.value.timeline.map { it.id })
        assertEquals(1, vm.state.value.messages.first { it.id == "q" }.replyCount)
    }

    @Test fun `deletions and reactions from others update the open conversation`() {
        val repo = FakeChannels().apply { history = listOf(msg("a", 1, "bye")) }
        val vm = ChannelViewModel("k", "ch", repo)
        repo.events.tryEmit(ChatEvent.ChannelReaction("k", "ch", "a", "other", "fire", true, JSONObject("""{"fire":1}""")))
        assertEquals(1, vm.state.value.messages[0].reactions["fire"])
        assertTrue(vm.state.value.messages[0].myReactions.isEmpty())
        repo.events.tryEmit(ChatEvent.ChannelMessageDeleted("k", "ch", "a"))
        assertTrue(vm.state.value.messages[0].deleted)
    }
}
