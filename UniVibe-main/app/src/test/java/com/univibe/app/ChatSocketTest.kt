package com.univibe.app

import com.univibe.app.data.net.ChatEvent
import com.univibe.app.data.net.ChatSocket
import com.univibe.app.data.net.SocketConnection
import com.univibe.app.data.net.SocketFactory
import com.univibe.app.data.net.SocketListener
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/** The live connection: sign in with the first frame, report events, reconnect with backoff, never spin. */
class ChatSocketTest {
    private val url = "wss://example.test/api/v1/ws"

    private class FakeConnection : SocketConnection {
        val sent = mutableListOf<String>()
        var closedWith: Int? = null
        override fun send(text: String): Boolean { sent += text; return true }
        override fun close(code: Int, reason: String) { closedWith = code }
    }

    private class FakeFactory(private val failAfterOpen: Boolean = false) : SocketFactory {
        val urls = mutableListOf<String>()
        val listeners = mutableListOf<SocketListener>()
        val connections = mutableListOf<FakeConnection>()
        override fun open(url: String, listener: SocketListener): SocketConnection {
            urls += url
            listeners += listener
            val connection = FakeConnection().also { connections += it }
            listener.onOpen()
            if (failAfterOpen) listener.onFailure(IOException("network down"))
            return connection
        }
    }

    private fun authFrame(c: FakeConnection) = JSONObject(c.sent.first())

    @Test fun authenticatesWithTheFirstFrameAndOnlyThenCountsAsConnected() = runTest {
        val factory = FakeFactory()
        val socket = ChatSocket(url, factory, { "tok1" }, backgroundScope, Random(1))
        val got = mutableListOf<ChatEvent>()
        backgroundScope.launch { socket.events.collect { got += it } }
        socket.start()
        runCurrent()
        assertEquals(listOf(url), factory.urls)
        assertEquals("auth", authFrame(factory.connections[0]).getString("type"))
        assertEquals("tok1", authFrame(factory.connections[0]).getString("token"))
        assertFalse(url.contains("tok1")) // the token never goes in the URL
        assertFalse(socket.connected.value)
        assertFalse(socket.send(JSONObject().put("type", "ping"))) // not connected yet: nothing is sent
        factory.listeners[0].onText("""{"type":"auth:ok","userId":"me"}""")
        runCurrent()
        assertTrue(socket.connected.value)
        assertEquals(listOf<ChatEvent>(ChatEvent.Connected), got)
        assertTrue(socket.send(JSONObject().put("type", "typing:start").put("conversationId", "c1")))
        assertEquals("typing:start", JSONObject(factory.connections[0].sent.last()).getString("type"))
    }

    @Test fun serverFramesBecomeAppEventsAndJunkIsIgnored() = runTest {
        val factory = FakeFactory()
        val socket = ChatSocket(url, factory, { "tok1" }, backgroundScope, Random(1))
        val got = mutableListOf<ChatEvent>()
        backgroundScope.launch { socket.events.collect { got += it } }
        socket.start()
        runCurrent()
        val server = factory.listeners[0]
        server.onText("""{"type":"auth:ok","userId":"me"}""")
        server.onText("""{"type":"message:new","conversationId":"c1","message":{}}""")
        server.onText("""{"type":"message:updated","conversationId":"c1","message":{}}""")
        server.onText("""{"type":"message:reaction","conversationId":"c1","messageId":"m1","userId":"u2","reaction":"heart","reactions":{}}""")
        server.onText("""{"type":"message:read","conversationId":"c1","userId":"u2","upToSeq":7}""")
        server.onText("""{"type":"typing:update","conversationId":"c1","userId":"u2","typing":true}""")
        server.onText("""{"type":"notification:created","notification":{"id":"n1"},"unreadCount":3}""")
        server.onText("""{"type":"notification:unread","unreadCount":0}""")
        server.onText("not json at all")
        server.onText("""{"type":"something:new"}""")
        server.onText("""{"type":"message:read","conversationId":"c1"}""") // malformed: missing fields
        runCurrent()
        assertEquals(
            listOf(
                ChatEvent.Connected,
                ChatEvent.MessageNew("c1"),
                ChatEvent.MessageChanged("c1"),
                ChatEvent.MessageChanged("c1"),
                ChatEvent.Read("c1", "u2", 7),
                ChatEvent.Typing("c1", "u2", true),
                ChatEvent.NotificationsChanged(3),
                ChatEvent.NotificationsChanged(0),
            ),
            got,
        )
    }

    @Test fun reconnectsAfterAFailureOnceTheBackoffHasPassed() = runTest {
        val factory = FakeFactory()
        val socket = ChatSocket(url, factory, { "tok1" }, backgroundScope, Random(1))
        socket.start()
        runCurrent()
        factory.listeners[0].onText("""{"type":"auth:ok","userId":"me"}""")
        runCurrent()
        factory.listeners[0].onFailure(IOException("boom"))
        runCurrent()
        assertFalse(socket.connected.value)
        assertEquals(1, factory.listeners.size) // still waiting out the backoff
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, factory.listeners.size)
        assertEquals("tok1", authFrame(factory.connections[1]).getString("token"))
    }

    @Test fun backoffKeepsGrowingWhileTheServerIsUnreachable() = runTest {
        val factory = FakeFactory(failAfterOpen = true)
        val socket = ChatSocket(url, factory, { "tok1" }, backgroundScope, Random(1))
        socket.start()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        // Without growth this would be dozens of attempts in ten seconds; with it, a handful.
        assertTrue("attempts: ${factory.urls.size}", factory.urls.size in 3..6)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue("attempts: ${factory.urls.size}", factory.urls.size < 14) // capped at one try per ~15-30 seconds
    }

    @Test fun aRejectedTokenIsReplacedBeforeTheNextAttempt() = runTest {
        val factory = FakeFactory()
        val asked = mutableListOf<String?>()
        var n = 0
        val socket = ChatSocket(url, factory, { stale -> asked += stale; "tok${++n}" }, backgroundScope, Random(1))
        socket.start()
        runCurrent()
        factory.listeners[0].onClosed(4401, "unauthorized")
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(listOf(null, "tok1"), asked) // the second request says tok1 was refused
        assertEquals("tok2", authFrame(factory.connections[1]).getString("token"))
    }

    @Test fun staysQuietWhileSignedOutAndClosesOnStop() = runTest {
        val factory = FakeFactory()
        var token: String? = null
        val socket = ChatSocket(url, factory, { token }, backgroundScope, Random(1))
        socket.start()
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(factory.urls.isEmpty()) // no token, no connection
        token = "tok1"
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(1, factory.urls.size)
        factory.listeners[0].onText("""{"type":"auth:ok","userId":"me"}""")
        runCurrent()
        socket.stop()
        runCurrent()
        assertFalse(socket.connected.value)
        assertEquals(1000, factory.connections[0].closedWith)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, factory.urls.size) // stopped means stopped
    }
}
