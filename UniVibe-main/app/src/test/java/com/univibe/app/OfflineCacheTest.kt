package com.univibe.app

import com.univibe.app.data.net.CachingTransport
import com.univibe.app.data.net.HttpRequest
import com.univibe.app.data.net.HttpResponse
import com.univibe.app.data.net.HttpTransport
import com.univibe.app.data.net.OfflineCache
import com.univibe.app.data.net.OfflineState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class OfflineCacheTest {
    private lateinit var dir: File
    private lateinit var cache: OfflineCache
    private var online = true
    private var status = 200
    private val inner = object : HttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse {
            if (!online) throw IOException("no network")
            return HttpResponse(status, "body-of-${request.url}")
        }
    }
    private val transport get() = CachingTransport(inner, cache)
    private val auth = mapOf("Authorization" to "Bearer t")

    @Before fun setUp() {
        dir = Files.createTempDirectory("offline").toFile()
        cache = OfflineCache(dir)
        online = true
        status = 200
        OfflineState.set(false)
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun get(url: String, headers: Map<String, String> = auth) = HttpRequest("GET", url, headers)

    @Test fun savedCopyIsServedWhenTheNetworkFails() = runTest {
        cache.useAccount("u1")
        transport.execute(get("https://x/api/v1/feed"))
        online = false
        val offline = transport.execute(get("https://x/api/v1/feed"))
        assertEquals(200, offline.status)
        assertEquals("body-of-https://x/api/v1/feed", offline.body)
        assertTrue(OfflineState.servedFromCache.value)
    }

    @Test fun theBannerFlagClearsOnceTheServerAnswersAgain() = runTest {
        cache.useAccount("u1")
        transport.execute(get("https://x/api/v1/feed"))
        online = false
        transport.execute(get("https://x/api/v1/feed"))
        online = true
        transport.execute(get("https://x/api/v1/events"))
        assertFalse(OfflineState.servedFromCache.value)
    }

    @Test fun nothingSavedMeansTheFailureIsStillReported() = runTest {
        cache.useAccount("u1")
        online = false
        try { transport.execute(get("https://x/api/v1/never-seen")); throw AssertionError("expected an IOException") } catch (_: IOException) {}
    }

    @Test fun changesAreNeverAnsweredFromTheCache() = runTest {
        cache.useAccount("u1")
        transport.execute(get("https://x/api/v1/feed"))
        online = false
        try { transport.execute(HttpRequest("POST", "https://x/api/v1/feed", auth, "{}")); throw AssertionError("expected an IOException") } catch (_: IOException) {}
    }

    @Test fun signInAndSessionDataIsNotKept() = runTest {
        cache.useAccount("u1")
        transport.execute(get("https://x/auth/status"))
        transport.execute(get("https://x/api/v1/me/sessions"))
        online = false
        for (url in listOf("https://x/auth/status", "https://x/api/v1/me/sessions")) {
            try { transport.execute(get(url)); throw AssertionError("should not be cached: $url") } catch (_: IOException) {}
        }
    }

    @Test fun requestsWithoutASignedInAccountOrTokenAreNotKept() = runTest {
        transport.execute(get("https://x/api/v1/feed")) // no account yet
        cache.useAccount("u1")
        transport.execute(get("https://x/api/v1/events", headers = emptyMap())) // no Authorization header
        online = false
        for (url in listOf("https://x/api/v1/feed", "https://x/api/v1/events")) {
            try { transport.execute(get(url)); throw AssertionError("should not be cached: $url") } catch (_: IOException) {}
        }
    }

    @Test fun aSleepingServerFallsBackToTheSavedCopy() = runTest {
        cache.useAccount("u1")
        transport.execute(get("https://x/api/v1/feed"))
        status = 503
        assertEquals("body-of-https://x/api/v1/feed", transport.execute(get("https://x/api/v1/feed")).body)
    }

    @Test fun anotherAccountNeverSeesTheFirstOnesData() = runTest {
        cache.useAccount("u1")
        transport.execute(get("https://x/api/v1/feed"))
        cache.useAccount("u2")
        assertNull(cache.get("https://x/api/v1/feed"))
    }

    @Test fun signingOutWipesEverything() = runTest {
        cache.useAccount("u1")
        transport.execute(get("https://x/api/v1/feed"))
        cache.clear()
        cache.useAccount("u1")
        assertNull(cache.get("https://x/api/v1/feed"))
    }
}
