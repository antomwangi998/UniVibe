package com.univibe.app

import com.univibe.app.data.net.Outbox
import com.univibe.app.data.net.OutboxResult
import com.univibe.app.data.net.OutboxRunner
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OutboxTest {
    private lateinit var dir: File
    private lateinit var file: File
    private var clock = 1_000L

    @Before fun setUp() {
        dir = Files.createTempDirectory("outbox").toFile()
        file = File(dir, "outbox.json")
        clock = 1_000L
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun box() = Outbox(file) { clock }
    private fun data(vararg pairs: Pair<String, Any>) = JSONObject().also { o -> pairs.forEach { o.put(it.first, it.second) } }

    @Test fun nothingIsQueuedWhileSignedOut() {
        assertFalse(box().add("like", data("postId" to "p1")))
    }

    @Test fun queuedActionsSurviveARestartForTheSameAccount() {
        val first = box()
        first.useAccount("u1")
        assertTrue(first.add("message", data("chatId" to "c1", "content" to "hi")))
        val second = box()
        second.useAccount("u1")
        assertEquals(1, second.count.value)
        assertEquals("hi", second.snapshot().single().data.getString("content"))
    }

    @Test fun anotherAccountStartsEmpty() {
        val first = box()
        first.useAccount("u1")
        first.add("like", data("postId" to "p1"))
        val second = box()
        second.useAccount("u2")
        assertEquals(0, second.count.value)
        val back = box()
        back.useAccount("u1")
        assertEquals(0, back.count.value) // the first account's queue was discarded when u2 signed in
    }

    @Test fun signingOutForgetsEverything() {
        val b = box()
        b.useAccount("u1")
        b.add("like", data("postId" to "p1"))
        b.clear()
        assertEquals(0, b.count.value)
        assertFalse(file.exists())
    }

    @Test fun theLatestActionWithTheSameKeyWins() {
        val b = box()
        b.useAccount("u1")
        b.add("like", data("on" to true), key = "like:p1")
        b.add("like", data("on" to false), key = "like:p1")
        val entries = b.snapshot()
        assertEquals(1, entries.size)
        assertFalse(entries.single().data.getBoolean("on"))
    }

    @Test fun theQueueHasACap() {
        val b = box()
        b.useAccount("u1")
        repeat(Outbox.MAX_ENTRIES) { assertTrue(b.add("message", data("n" to it))) }
        assertFalse(b.add("message", data("n" to -1)))
    }

    @Test fun runnerSendsOldestFirstAndRemovesWhatIsDone() = runTest {
        val b = box()
        b.useAccount("u1")
        b.add("like", data("n" to 1)); b.add("like", data("n" to 2))
        val sent = mutableListOf<Int>()
        val runner = OutboxRunner(b) { clock }
        runner.register("like") { sent += it.data.getInt("n"); OutboxResult.Done }
        runner.flush()
        assertEquals(listOf(1, 2), sent)
        assertEquals(0, b.count.value)
    }

    @Test fun runnerStopsAtTheFirstRetryAndKeepsTheRest() = runTest {
        val b = box()
        b.useAccount("u1")
        b.add("like", data("n" to 1)); b.add("like", data("n" to 2))
        val runner = OutboxRunner(b) { clock }
        var calls = 0
        runner.register("like") { calls++; OutboxResult.Retry }
        runner.flush()
        assertEquals(1, calls)
        assertEquals(2, b.count.value)
        assertEquals(1, b.snapshot().first().attempts)
    }

    @Test fun refusedActionsAreDropped() = runTest {
        val b = box()
        b.useAccount("u1")
        b.add("like", data("n" to 1))
        val runner = OutboxRunner(b) { clock }
        runner.register("like") { OutboxResult.Drop }
        runner.flush()
        assertEquals(0, b.count.value)
    }

    @Test fun aThrowingHandlerCountsAsRetry() = runTest {
        val b = box()
        b.useAccount("u1")
        b.add("like", data("n" to 1))
        val runner = OutboxRunner(b) { clock }
        runner.register("like") { throw IllegalStateException("boom") }
        runner.flush()
        assertEquals(1, b.count.value)
    }

    @Test fun veryOldEntriesAreGivenUp() = runTest {
        val b = box()
        b.useAccount("u1")
        b.add("like", data("n" to 1))
        clock += OutboxRunner.MAX_AGE_MS + 1
        val runner = OutboxRunner(b) { clock }
        var called = false
        runner.register("like") { called = true; OutboxResult.Done }
        runner.flush()
        assertFalse(called)
        assertEquals(0, b.count.value)
    }

    @Test fun kindsWithoutAHandlerAreLeftAlone() = runTest {
        val b = box()
        b.useAccount("u1")
        b.add("unknown", data("n" to 1))
        OutboxRunner(b) { clock }.flush()
        assertEquals(1, b.count.value)
    }
}
