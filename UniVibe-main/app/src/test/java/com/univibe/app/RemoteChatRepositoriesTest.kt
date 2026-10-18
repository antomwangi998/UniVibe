package com.univibe.app

import com.univibe.app.data.model.MessageStatus
import com.univibe.app.data.model.Reaction
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.ChatEvent
import com.univibe.app.data.net.FileUploader
import com.univibe.app.data.net.MediaUploader
import com.univibe.app.data.net.HttpResponse
import com.univibe.app.data.net.LiveConnection
import com.univibe.app.data.repository.RemoteChatRepository
import com.univibe.app.data.repository.RemoteNotificationRepository
import com.univibe.app.data.repository.RemoteMessageRepository
import com.univibe.app.domain.auth.AuthResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/** The real chat repositories against a fake server: they must map what the backend sends and keep sends honest. */
class RemoteChatRepositoriesTest {
    private class FakeApi(private val handler: (method: String, path: String, body: JSONObject?) -> HttpResponse) : AuthedApi {
        val calls = mutableListOf<Triple<String, String, JSONObject?>>()
        override suspend fun authedCall(method: String, path: String, body: JSONObject?): AuthResult<HttpResponse> {
            calls += Triple(method, path, body)
            return AuthResult.Success(handler(method, path, body))
        }
    }

    private class PhotoFiles(private val fail: Boolean = false) : FileUploader {
        val sent = mutableListOf<String>()
        override suspend fun put(url: String, contentType: String, file: File) {
            if (fail) throw IOException("connection lost")
            sent += url
        }
    }

    private fun photoFile(): String = File.createTempFile("chat", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }.absolutePath

    /** A server that accepts one photo upload and then the message that points at it. */
    private fun photoServer(sent: String) = FakeApi { method, path, _ ->
        when {
            method == "POST" && path == "/media/uploads" -> ok("""{"mediaId":"md1","uploadUrl":"https://files.test/up"}""", 201)
            method == "POST" && path == "/media/md1/complete" -> ok("{}")
            method == "POST" && path.endsWith("/messages") -> ok(sent, 201)
            path.contains("/messages") -> ok("""{"items":[],"nextBefore":null,"pinnedMessageId":null}""")
            else -> ok(membersJson)
        }
    }

    private class FakeLive(connected: Boolean = true) : LiveConnection {
        val bus = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 16)
        override val events: SharedFlow<ChatEvent> = bus
        val up = MutableStateFlow(connected)
        override val connected: StateFlow<Boolean> = up
        val sent = mutableListOf<JSONObject>()
        override fun send(frame: JSONObject): Boolean { sent += frame; return true }
    }

    /** Conversation detail with read positions: ours (ignored) and the other person's. */
    private fun membersWith(otherRead: Int) = """{"id":"c1","kind":"direct","title":null,"mutedUntil":null,"pinned":false,"members":[
        {"id":"me","fullName":"Me","handle":"me","lastReadSeq":3},{$card,"online":true,"lastReadSeq":$otherRead}]}"""

    private fun mineServer(otherRead: Int) = FakeApi { _, path, _ ->
        if (path.contains("/messages")) ok("""{"items":[${message("m1", 1, me, "hello")}],"nextBefore":null,"pinnedMessageId":null}""")
        else ok(membersWith(otherRead))
    }

    private fun ok(json: String, status: Int = 200) = HttpResponse(status, json)
    private val me = "me"
    private val card = """"id":"u2","fullName":"Amina Otieno","handle":"amina","course":"Law","yearOfStudy":"2","universityName":"CUK","interests":[],"studentVerified":true,"avatarUrl":null"""
    private val membersJson = """{"id":"c1","kind":"direct","title":null,"mutedUntil":null,"pinned":false,"members":[
        {"id":"me","fullName":"Me","handle":"me"},{$card,"online":true}]}"""

    private fun message(
        id: String, seq: Int, sender: String, text: String?,
        reactions: String = "{}", myReaction: String? = null, editedAt: String? = null, mediaUrl: String? = null,
    ) = JSONObject()
        .put("id", id).put("seq", seq).put("conversationId", "c1").put("senderId", sender)
        .put("content", text ?: JSONObject.NULL).put("deleted", text == null)
        .put("createdAt", "2026-10-17T10:00:00.000Z").put("editedAt", editedAt ?: JSONObject.NULL)
        .put("replyTo", JSONObject.NULL)
        .put("media", org.json.JSONArray().apply {
            if (mediaUrl != null) put(JSONObject().put("id", "md1").put("kind", "image").put("contentType", "image/jpeg").put("url", mediaUrl))
        })
        .put("reactions", JSONObject(reactions)).put("myReaction", myReaction ?: JSONObject.NULL)
        .toString()

    // ---- chat list

    @Test fun chatListMapsDirectAndGroupChats() = runBlocking {
        val api = FakeApi { _, _, _ ->
            ok("""{"items":[
              {"id":"c1","kind":"direct","title":null,"lastMessageAt":"2026-10-17T09:00:00.000Z","unreadCount":2,"mutedUntil":"2099-01-01T00:00:00.000Z","archived":false,"pinned":true,"markedUnread":false,
               "with":{$card},"lastMessage":{"id":"m1","senderId":"u2","content":"see you at 5","deleted":false,"createdAt":"2026-10-17T09:00:00.000Z"}},
              {"id":"c2","kind":"group","title":"Law 2026","lastMessageAt":"2026-10-17T08:00:00.000Z","unreadCount":0,"mutedUntil":null,"archived":false,"pinned":false,"markedUnread":false,
               "with":null,"lastMessage":{"id":"m2","senderId":"u2","content":null,"deleted":true,"createdAt":"2026-10-17T08:00:00.000Z"}}
            ],"nextCursor":null}""")
        }
        val chats = RemoteChatRepository(api) { me }.getChats()
        assertEquals(2, chats.size)
        val direct = chats[0]
        assertEquals("Amina Otieno", direct.title)
        assertEquals("see you at 5", direct.lastMessage)
        assertEquals(2, direct.unread)
        assertTrue(direct.pinned)
        assertTrue(direct.muted)
        assertEquals("u2", direct.studentId)
        assertFalse(direct.isGroup)
        val group = chats[1]
        assertEquals("Law 2026", group.title)
        assertTrue(group.isGroup)
        assertEquals("Message deleted", group.lastMessage)
        assertFalse(group.muted)
        assertNull(group.studentId)
    }

    @Test fun chatListReadsPhotoSenderAndManualUnread() = runBlocking {
        val api = FakeApi { _, _, _ ->
            ok("""{"items":[
              {"id":"g1","kind":"group","title":"Law 2026","lastMessageAt":"2026-10-17T09:00:00.000Z","unreadCount":0,"mutedUntil":null,"archived":false,"pinned":false,"markedUnread":true,
               "with":null,"lastMessage":{"id":"m1","senderId":"u2","senderName":"Jane Doe","content":null,"deleted":false,"mediaCount":3,"createdAt":"2026-10-17T09:00:00.000Z"}},
              {"id":"g2","kind":"group","title":"Mine","lastMessageAt":"2026-10-17T08:00:00.000Z","unreadCount":0,"mutedUntil":null,"archived":false,"pinned":false,"markedUnread":false,
               "with":null,"lastMessage":{"id":"m2","senderId":"$me","senderName":"Me","content":"hi","deleted":false,"mediaCount":0,"createdAt":"2026-10-17T08:00:00.000Z"}}
            ],"nextCursor":null}""")
        }
        val (photo, mine) = RemoteChatRepository(api) { me }.getChats()
        assertEquals(com.univibe.app.data.model.LastKind.Photo, photo.lastKind)
        assertEquals(3, photo.photoCount)
        assertEquals("3 photos", photo.lastMessage)
        assertEquals("Jane Doe", photo.lastSenderName)
        assertFalse(photo.lastFromMe)
        // Marked unread by hand: shows as unread, but it is flagged as having no real unread messages.
        assertTrue(photo.hasUnread); assertTrue(photo.markedOnly)
        assertEquals(com.univibe.app.data.model.LastKind.Text, mine.lastKind)
        assertTrue(mine.lastFromMe)
        assertFalse(mine.hasUnread)
    }

    @Test fun chatListReadsGroupMembersDeliveryAndUntitledGroups() = runBlocking {
        val api = FakeApi { _, _, _ ->
            ok("""{"items":[
              {"id":"g1","kind":"group","title":null,"lastMessageAt":"2026-10-17T09:00:00.000Z","unreadCount":0,"mutedUntil":null,"archived":false,"pinned":false,"markedUnread":false,
               "memberCount":4,"members":[{"id":"u2","name":"Jane Doe"},{"id":"u3","name":"Sam Ong"},{"id":"u4","name":"Lee Wu"}],"with":null,
               "lastMessage":{"id":"m1","senderId":"$me","senderName":"Me","content":"yo","deleted":false,"mediaCount":0,"delivery":"delivered","createdAt":"2026-10-17T09:00:00.000Z"}}
            ],"nextCursor":null}""")
        }
        val g = RemoteChatRepository(api) { me }.getChats().single()
        assertEquals("Jane, Sam and 1 other", g.title)
        assertEquals(4, g.memberCount)
        assertEquals(listOf("u2", "u3", "u4"), g.members.map { it.id })
        assertEquals(com.univibe.app.data.model.Delivery.Delivered, g.lastDelivery)
    }

    @Test fun archivedChatsAreAskedForAndUnarchivedWithTheSettingsCall() = runBlocking {
        val api = FakeApi { _, _, _ -> ok("""{"items":[],"nextCursor":null}""") }
        val repo = RemoteChatRepository(api) { me }
        repo.getArchivedChats()
        assertTrue(api.calls[0].second.contains("archived=true"))
        repo.unarchive("c1")
        assertEquals(false, api.calls[1].third!!.getBoolean("archived"))
    }

    @Test fun pinMarkUnreadAndMarkReadCallTheServer() = runBlocking {
        val api = FakeApi { _, _, _ -> HttpResponse(204, "") }
        val repo = RemoteChatRepository(api) { me }
        repo.setPinned("c1", true)
        repo.markUnread("c1")
        repo.markRead("c1")
        assertEquals("PUT", api.calls[0].first)
        assertEquals("/api/v1/conversations/c1/settings", api.calls[0].second)
        assertEquals(true, api.calls[0].third!!.getBoolean("pinned"))
        assertEquals("POST", api.calls[1].first)
        assertEquals("/api/v1/conversations/c1/mark-unread", api.calls[1].second)
        assertEquals("/api/v1/conversations/c1/read", api.calls[2].second)
    }

    @Test fun aChatYouAreNotInIsNull() = runBlocking {
        val api = FakeApi { _, _, _ -> HttpResponse(404, """{"error":"not_found","message":"Not found."}""") }
        assertNull(RemoteChatRepository(api) { me }.getChat("nope"))
    }

    @Test fun getChatNamesTheOtherPersonAndReadsYourSettings() = runBlocking {
        val api = FakeApi { _, _, _ -> ok(membersJson) }
        val chat = RemoteChatRepository(api) { me }.getChat("c1")!!
        assertEquals("Amina Otieno", chat.title)
        assertEquals("u2", chat.studentId)
        assertTrue(chat.online)
    }

    @Test fun muteAndUnmuteSendTheRightSettings() = runBlocking {
        val api = FakeApi { _, _, _ -> ok("{}") }
        val repo = RemoteChatRepository(api) { me }
        repo.setMuted("c1", true)
        repo.setMuted("c1", false)
        val (method, path, mute) = api.calls[0]
        assertEquals("PUT", method)
        assertEquals("/api/v1/conversations/c1/settings", path)
        assertEquals("2099-01-01T00:00:00.000Z", mute!!.getString("mutedUntil"))
        assertTrue(api.calls[1].third!!.isNull("mutedUntil"))
    }

    // ---- messages

    private fun serverFor(sendResult: HttpResponse? = null, window: String = "[]") = FakeApi { method, path, _ ->
        when {
            method == "POST" && path.endsWith("/messages") -> sendResult ?: ok(message("m1", 1, me, "hello"), 201)
            path.contains("/messages") -> ok("""{"items":$window,"nextBefore":null}""")
            else -> ok(membersJson)
        }
    }

    @Test fun aSentMessageReplacesItsPlaceholderAndIsMine() = runBlocking {
        val api = serverFor()
        val repo = RemoteMessageRepository(api) { me }
        repo.send("c1", "  hello  ", null, null)
        val list = withTimeout(5_000) { repo.messages("c1").first { l -> l.any { it.id == "m1" } } }
        assertEquals(1, list.size)
        assertEquals("hello", list[0].text)
        assertTrue(list[0].isMine)
        assertEquals(MessageStatus.Sent, list[0].status)
        val post = api.calls.first { it.first == "POST" }
        assertEquals("hello", post.third!!.getString("content"))
        assertTrue(post.third!!.getString("clientId").isNotBlank())
    }

    @Test fun aRefusedSendLeavesNoMessageBehindAndSaysWhy() = runBlocking {
        val refused = HttpResponse(403, """{"error":"cannot_message","message":"You cannot message this person."}""")
        val repo = RemoteMessageRepository(serverFor(sendResult = refused)) { me }
        try {
            repo.send("c1", "hi", null, null)
            fail("send should have failed")
        } catch (e: ApiException) {
            assertEquals("You cannot message this person.", e.message)
        }
        assertTrue(repo.messages("c1").first().isEmpty())
    }

    @Test fun aMessageWithSeveralPhotosKeepsAllOfThemAndTheServersPhotoShape() = runBlocking {
        val json = JSONObject(message("m9", 9, "u2", "")).apply {
            put("media", org.json.JSONArray()
                .put(JSONObject().put("id", "a").put("kind", "image").put("contentType", "image/jpeg").put("url", "https://cdn.test/a.jpg").put("width", 800).put("height", 400))
                .put(JSONObject().put("id", "b").put("kind", "image").put("contentType", "image/jpeg").put("url", "https://cdn.test/b.jpg").put("width", 400).put("height", 800)))
        }
        val repo = RemoteMessageRepository(serverFor(window = "[$json]")) { me }
        val m = withTimeout(5_000) { repo.messages("c1").first { l -> l.any { it.id == "m9" } } }.single()
        assertEquals(listOf("https://cdn.test/a.jpg", "https://cdn.test/b.jpg"), m.photos)
        assertEquals(2.0f, m.imageRatio!!, 0.001f) // the first photo's shape, known before anything loads
    }

    @Test fun withoutAnUploaderPhotosFailClearly() = runBlocking {
        val repo = RemoteMessageRepository(serverFor()) { me }
        try {
            repo.send("c1", "", photoFile(), null)
            fail("photos need an uploader")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Photos"))
        }
        assertTrue(repo.messages("c1").first().isEmpty())
    }

    @Test fun aSentPhotoIsUploadedThenAttachedAndOpensFromTheLocalCopy() = runBlocking {
        val path = photoFile()
        val files = PhotoFiles()
        val api = photoServer(message("m1", 1, me, "", mediaUrl = "https://cdn.test/m1.jpg?sig=abc"))
        val repo = RemoteMessageRepository(api, MediaUploader(api, files)) { me }
        repo.send("c1", "", path, null)
        assertEquals(listOf("https://files.test/up"), files.sent)
        val post = api.calls.first { it.first == "POST" && it.second.endsWith("/messages") }
        assertEquals("md1", post.third!!.getJSONArray("mediaIds").getString(0))
        val list = withTimeout(5_000) { repo.messages("c1").first { l -> l.any { it.id == "m1" } } }
        assertEquals(path, list.single().imagePath) // our own photo: the local file, instant and offline-proof
        assertEquals("", list.single().text)        // no "Photo" caption under the picture
        assertEquals(MessageStatus.Sent, list.single().status)
    }

    @Test fun aPhotoFromSomeoneElseOpensFromTheServerLink() = runBlocking {
        val window = "[" + message("m1", 1, "u2", "look", mediaUrl = "https://cdn.test/m1.jpg?sig=abc") + "]"
        val repo = RemoteMessageRepository(serverFor(window = window)) { me }
        val list = withTimeout(5_000) { repo.messages("c1").first { it.isNotEmpty() } }
        assertEquals("https://cdn.test/m1.jpg?sig=abc", list.single().imagePath)
        assertEquals("look", list.single().text)
    }

    @Test fun aPhotoWithoutALinkFallsBackToTheWordPhoto() = runBlocking {
        val bare = JSONObject(message("m1", 1, "u2", "", mediaUrl = "x")).apply {
            getJSONArray("media").getJSONObject(0).put("url", JSONObject.NULL)
        }.toString()
        val repo = RemoteMessageRepository(serverFor(window = "[$bare]")) { me }
        val list = withTimeout(5_000) { repo.messages("c1").first { it.isNotEmpty() } }
        assertEquals("Photo", list.single().text)
        assertNull(list.single().imagePath)
    }

    @Test fun aFailedUploadLeavesNoPlaceholderBehind() = runBlocking {
        val api = photoServer(message("m1", 1, me, ""))
        val repo = RemoteMessageRepository(api, MediaUploader(api, PhotoFiles(fail = true))) { me }
        try {
            repo.send("c1", "", photoFile(), null)
            fail("the upload failed, so the send should fail")
        } catch (e: IOException) {
            assertEquals("connection lost", e.message)
        }
        assertTrue(repo.messages("c1").first().isEmpty())
        assertFalse(api.calls.any { it.second.endsWith("/messages") && it.first == "POST" })
    }

    @Test fun historyShowsNamesReactionsAndHidesUnsentMessages() = runBlocking {
        // The server lists newest first; the app shows oldest first and drops unsent messages.
        val window = "[" + message("m3", 3, "u2", null) + "," +
            message("m2", 2, me, "second", reactions = """{"heart":2}""", myReaction = "heart") + "," +
            message("m1", 1, "u2", "first") + "]"
        val repo = RemoteMessageRepository(serverFor(window = window)) { me }
        val list = withTimeout(5_000) { repo.messages("c1").first { it.isNotEmpty() } }
        assertEquals(listOf("m1", "m2"), list.map { it.id })
        assertEquals("Amina Otieno", list[0].senderName)
        assertFalse(list[0].isMine)
        assertEquals("You", list[1].senderName)
        assertEquals(mapOf(Reaction.Heart to 2), list[1].reactions)
        assertEquals(Reaction.Heart, list[1].myReaction)
    }

    @Test fun editingUsesTheMessageEndpointAndMarksItEdited() = runBlocking {
        val edited = message("m1", 1, me, "fixed", editedAt = "2026-10-17T10:01:00.000Z")
        val api = FakeApi { method, path, _ ->
            when {
                method == "POST" -> ok(message("m1", 1, me, "typo"), 201)
                method == "PUT" && path == "/api/v1/messages/m1" -> ok(edited)
                path.contains("/messages") -> ok("""{"items":[],"nextBefore":null}""")
                else -> ok(membersJson)
            }
        }
        val repo = RemoteMessageRepository(api) { me }
        repo.send("c1", "typo", null, null)
        repo.editMessage("c1", "m1", "fixed")
        val list = repo.messages("c1").first { it.any { m -> m.edited } }
        assertEquals("fixed", list.single().text)
    }

    @Test fun forwardingPostsToTheForwardEndpoint() = runBlocking {
        val api = FakeApi { _, _, _ -> ok("""{"forwarded":2}""") }
        RemoteMessageRepository(api) { me }.forward("c1", "m1", listOf("c2", "c3"))
        val (method, path, body) = api.calls.single()
        assertEquals("POST", method)
        assertEquals("/api/v1/messages/m1/forward", path)
        assertEquals(2, body!!.getJSONArray("conversationIds").length())
    }

    @Test fun pinAndUnpinUseThePinEndpoint() = runBlocking {
        val api = FakeApi { _, _, _ -> HttpResponse(204, "") }
        val repo = RemoteMessageRepository(api) { me }
        repo.pin("c1", "m1")
        assertEquals("m1", repo.pinnedMessageId("c1").first())
        repo.pin("c1", null)
        assertNull(repo.pinnedMessageId("c1").first())
        assertEquals(listOf("PUT", "DELETE"), api.calls.map { it.first })
        assertEquals("m1", api.calls[0].third!!.getString("messageId"))
    }

    @Test fun theServersPinShowsUp() = runBlocking {
        val api = FakeApi { _, path, _ ->
            if (path.contains("/messages")) ok("""{"items":[${message("m1", 1, "u2", "rules")}],"nextBefore":null,"pinnedMessageId":"m1"}""") else ok(membersJson)
        }
        val repo = RemoteMessageRepository(api) { me }
        val job = launch { repo.messages("c1").collect { } }
        val pinned = withTimeout(5_000) { repo.pinnedMessageId("c1").first { it != null } }
        job.cancel()
        assertEquals("m1", pinned)
    }

    @Test fun clearChatEmptiesTheConversationOnThisPhone() = runBlocking {
        val api = FakeApi { method, path, _ ->
            when {
                method == "POST" && path.endsWith("/messages") -> ok(message("m1", 1, me, "hi"), 201)
                method == "POST" && path.endsWith("/clear") -> HttpResponse(204, "")
                path.contains("/messages") -> ok("""{"items":[],"nextBefore":null,"pinnedMessageId":null}""")
                else -> ok(membersJson)
            }
        }
        val repo = RemoteMessageRepository(api) { me }
        repo.send("c1", "hi", null, null)
        assertEquals(1, repo.messages("c1").first { it.isNotEmpty() }.size)
        repo.clearChat("c1")
        assertTrue(repo.messages("c1").first().isEmpty())
        assertTrue(api.calls.any { it.second == "/api/v1/conversations/c1/clear" })
    }

    @Test fun earlierMessagesLoadInPagesAndStopWhenThereAreNone() = runBlocking {
        val api = FakeApi { _, path, _ ->
            when {
                path.contains("before=2") -> ok("""{"items":[${message("m1", 1, "u2", "first")}],"nextBefore":null,"pinnedMessageId":null}""")
                path.contains("/messages") -> ok("""{"items":[${message("m2", 2, "u2", "second")}],"nextBefore":2,"pinnedMessageId":null}""")
                else -> ok(membersJson)
            }
        }
        val repo = RemoteMessageRepository(api) { me }
        val firstPage = withTimeout(5_000) { repo.messages("c1").first { it.isNotEmpty() } }
        assertEquals(listOf("m2"), firstPage.map { it.id })
        assertTrue(repo.hasOlder("c1").first())
        repo.loadOlder("c1")
        assertEquals(listOf("m1", "m2"), repo.messages("c1").first().map { it.id })
        assertFalse(repo.hasOlder("c1").first())
    }
    // ---- live events

    @Test fun aNewMessageEventRefreshesAtOnceInsteadOfWaitingForThePoll() = runBlocking {
        var delivered = false
        val api = FakeApi { _, path, _ ->
            if (path.contains("/messages")) {
                ok("""{"items":[${if (delivered) message("m1", 1, "u2", "hi") else ""}],"nextBefore":null,"pinnedMessageId":null}""")
            } else ok(membersJson)
        }
        val live = FakeLive()
        val repo = RemoteMessageRepository(api, null, live) { me }
        val arrived = CompletableDeferred<Unit>()
        val job = launch { repo.messages("c1").collect { if (it.any { m -> m.id == "m1" }) arrived.complete(Unit) } }
        withTimeout(5_000) { while (api.calls.none { it.second.contains("/messages") }) delay(10) }
        live.bus.subscriptionCount.first { it >= 1 }
        delivered = true
        live.bus.emit(ChatEvent.MessageNew("c1"))
        withTimeout(5_000) { arrived.await() } // with the link up the poll is every 20 s, so only the event can explain this
        job.cancel()
    }

    @Test fun otherChatsEventsDoNotTriggerARefresh() = runBlocking {
        val api = serverFor()
        val live = FakeLive()
        val repo = RemoteMessageRepository(api, null, live) { me }
        val job = launch { repo.messages("c1").collect { } }
        withTimeout(5_000) { while (api.calls.none { it.second.contains("/messages") }) delay(10) }
        live.bus.subscriptionCount.first { it >= 1 }
        delay(250) // the reconnect re-sync that every new subscription triggers settles first
        val before = api.calls.size
        live.bus.emit(ChatEvent.MessageNew("some-other-chat"))
        delay(150)
        assertEquals(before, api.calls.size)
        job.cancel()
    }

    @Test fun theOtherPersonsTypingShowsAndClears() = runBlocking {
        val live = FakeLive()
        val repo = RemoteMessageRepository(serverFor(), null, live) { me }
        val seen = mutableListOf<Boolean>()
        val job = launch { repo.typing("c1").collect { seen += it } }
        live.bus.subscriptionCount.first { it >= 1 }
        live.bus.emit(ChatEvent.Typing("c1", "u2", true))
        withTimeout(5_000) { while (true !in seen) delay(10) }
        live.bus.emit(ChatEvent.Typing("c1", "u2", false))
        withTimeout(5_000) { while (seen.last()) delay(10) }
        live.bus.emit(ChatEvent.Typing("c1", me, true)) // our own other device: not shown to us
        live.bus.emit(ChatEvent.Typing("c2", "u2", true)) // another chat: not shown here
        delay(100)
        assertFalse(seen.last())
        job.cancel()
    }

    @Test fun typingChangesAreSentOverTheLiveLink() = runBlocking {
        val live = FakeLive()
        val repo = RemoteMessageRepository(serverFor(), null, live) { me }
        repo.typingChanged("c1", true)
        repo.typingChanged("c1", false)
        assertEquals(listOf("typing:start", "typing:stop"), live.sent.map { it.getString("type") })
        assertEquals("c1", live.sent[0].getString("conversationId"))
    }

    @Test fun withoutALiveLinkTypingIsQuietAndSendingItDoesNothing() = runBlocking {
        val repo = RemoteMessageRepository(serverFor()) { me }
        assertFalse(repo.typing("c1").first())
        repo.typingChanged("c1", true) // must not crash
    }

    @Test fun seenComesFromTheOtherPersonsReadPosition() = runBlocking {
        val repo = RemoteMessageRepository(mineServer(otherRead = 1)) { me }
        val list = withTimeout(5_000) { repo.messages("c1").first { it.isNotEmpty() } }
        assertEquals(MessageStatus.Seen, list.single().status)
    }

    @Test fun unreadByTheOtherPersonStaysSentAndOurOwnPositionIsIgnored() = runBlocking {
        val repo = RemoteMessageRepository(mineServer(otherRead = 0)) { me }
        val list = withTimeout(5_000) { repo.messages("c1").first { it.isNotEmpty() } }
        assertEquals(MessageStatus.Sent, list.single().status) // our own lastReadSeq of 3 must not count
    }

    @Test fun aReadEventTurnsSentIntoSeenLive() = runBlocking {
        val live = FakeLive()
        val repo = RemoteMessageRepository(mineServer(otherRead = 0), null, live) { me }
        val latest = java.util.concurrent.atomic.AtomicReference<List<com.univibe.app.data.model.Message>>(emptyList())
        val job = launch { repo.messages("c1").collect { latest.set(it) } }
        withTimeout(5_000) { while (latest.get().firstOrNull()?.status != MessageStatus.Sent) delay(10) }
        live.bus.subscriptionCount.first { it >= 1 }
        live.bus.emit(ChatEvent.Read("c1", "u2", 1))
        withTimeout(5_000) { while (latest.get().firstOrNull()?.status != MessageStatus.Seen) delay(10) }
        job.cancel()
    }

    @Test fun theChatListReloadsOnMessageEventsOnly() = runBlocking {
        val live = FakeLive()
        val repo = RemoteChatRepository(serverFor(), live) { me }
        val got = mutableListOf<Unit>()
        val job = launch { repo.changes().collect { got += it } }
        live.bus.subscriptionCount.first { it >= 1 }
        live.bus.emit(ChatEvent.Typing("c1", "u2", true))
        live.bus.emit(ChatEvent.Presence("u2", true))
        delay(100)
        assertTrue(got.isEmpty())
        live.bus.emit(ChatEvent.MessageNew("c1"))
        withTimeout(5_000) { while (got.isEmpty()) delay(10) }
        job.cancel()
    }

    @Test fun notificationChangesAreReportedAndNothingElse() = runBlocking {
        val live = FakeLive()
        val repo = RemoteNotificationRepository(serverFor(), live)
        val got = mutableListOf<Unit>()
        val job = launch { repo.changes().collect { got += it } }
        live.bus.subscriptionCount.first { it >= 1 }
        live.bus.emit(ChatEvent.MessageNew("c1"))
        delay(100)
        assertTrue(got.isEmpty())
        live.bus.emit(ChatEvent.NotificationsChanged(2))
        withTimeout(5_000) { while (got.isEmpty()) delay(10) }
        job.cancel()
    }

}
