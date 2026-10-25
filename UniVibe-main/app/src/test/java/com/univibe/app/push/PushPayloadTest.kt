package com.univibe.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PushPayloadTest {
    private val conv = "3f2c1a9e-1111-4222-8333-444455556666"

    @Test fun directMessageOpensTheConversationWithGenericText() {
        val e = PushPayload.parse(mapOf("kind" to "message", "conversationId" to conv, "messageId" to "m1", "group" to "0"))!!
        assertEquals(PushChannel.DirectMessages, e.channel)
        assertEquals("New message", e.title)
        assertEquals("univibe://chats/$conv", e.deepLink)
        assertEquals("c:$conv", e.tag)
    }

    @Test fun groupMessageUsesTheGroupChannel() {
        val e = PushPayload.parse(mapOf("kind" to "message", "conversationId" to conv, "group" to "1"))!!
        assertEquals(PushChannel.GroupMessages, e.channel)
        assertEquals("New message in your group", e.title)
    }

    @Test fun twoPushesForTheSameConversationShareATagSoTheyReplaceEachOther() {
        val a = PushPayload.parse(mapOf("kind" to "message", "conversationId" to conv))!!
        val b = PushPayload.parse(mapOf("kind" to "message", "conversationId" to conv, "messageId" to "other"))!!
        assertEquals(a.tag, b.tag)
    }

    @Test fun unsafeIdsAreRejectedSoTheyCannotInjectIntoALink() {
        assertNull(PushPayload.parse(mapOf("kind" to "message", "conversationId" to "x/../settings/account/delete")))
        assertNull(PushPayload.parse(mapOf("kind" to "message", "conversationId" to "")))
        assertNull(PushPayload.parse(mapOf("kind" to "message")))
    }

    @Test fun socialNotificationsRouteToTheRightScreen() {
        val like = PushPayload.parse(mapOf("kind" to "notification", "type" to "like", "notificationId" to "n1", "entityId" to "p1"))!!
        assertEquals("univibe://post/p1/comments", like.deepLink)
        val reply = PushPayload.parse(mapOf("kind" to "notification", "type" to "reply", "notificationId" to "n2", "entityId" to "c9", "postId" to "p2"))!!
        assertEquals("univibe://post/p2/comments", reply.deepLink)
        val follow = PushPayload.parse(mapOf("kind" to "notification", "type" to "follow", "notificationId" to "n3", "entityId" to "u7"))!!
        assertEquals("univibe://student/u7", follow.deepLink)
        assertEquals(PushChannel.Social, follow.channel)
    }

    @Test fun groupInviteEventAndSecurityRoute() {
        assertEquals("univibe://chats/$conv", PushPayload.parse(mapOf("kind" to "notification", "type" to "group_invite", "conversationId" to conv))!!.deepLink)
        val event = PushPayload.parse(mapOf("kind" to "notification", "type" to "event_update", "entityId" to "e1"))!!
        assertEquals("univibe://events/e1", event.deepLink)
        assertEquals(PushChannel.Campus, event.channel)
        val security = PushPayload.parse(mapOf("kind" to "notification", "type" to "verification"))!!
        assertEquals(PushChannel.Security, security.channel)
        assertEquals("univibe://notifications", security.deepLink)
    }

    @Test fun aMissingIdFallsBackToTheNotificationList() {
        val e = PushPayload.parse(mapOf("kind" to "notification", "type" to "like"))
        assertNotNull(e)
        assertEquals("univibe://notifications", e!!.deepLink)
    }

    @Test fun unknownKindsAndTypesAreIgnoredForForwardCompatibility() {
        assertNull(PushPayload.parse(mapOf("kind" to "something_new")))
        assertNull(PushPayload.parse(mapOf("kind" to "notification", "type" to "brand_new_type")))
        assertNull(PushPayload.parse(emptyMap()))
    }

    @Test fun textsNeverContainPrivateContent() {
        val e = PushPayload.parse(mapOf("kind" to "message", "conversationId" to conv, "content" to "secret plan"))!!
        assertFalse((e.title + e.body).contains("secret"))
    }
}

class MessagePreviewFormatTest {
    @Test fun directChatShowsSenderAndText() {
        val p = MessagePreviewFormat.compose(false, null, "Ashley Wangeci", "  see you\nat 5  ", emptyList())
        assertEquals("Ashley Wangeci", p.title)
        assertEquals("see you at 5", p.body)
    }

    @Test fun groupShowsGroupNameAndSenderPrefix() {
        val p = MessagePreviewFormat.compose(true, "I-RISE", "Joy Anne", "Workshop moved", emptyList())
        assertEquals("I-RISE", p.title)
        assertEquals("Joy Anne: Workshop moved", p.body)
    }

    @Test fun mediaOnlyMessagesGetALabel() {
        assertEquals("Sent a photo", MessagePreviewFormat.compose(false, null, "A", "", listOf("image")).body)
        assertEquals("Voice message", MessagePreviewFormat.compose(false, null, "A", "", listOf("audio")).body)
    }

    @Test fun longTextIsClipped() {
        val body = MessagePreviewFormat.compose(false, null, "A", "x".repeat(500), emptyList()).body
        assertEquals(MessagePreviewFormat.MAX_BODY, body.length)
    }
}
