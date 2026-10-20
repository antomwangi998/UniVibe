package com.univibe.app

import com.univibe.app.data.net.CommunityCachePolicy
import com.univibe.app.data.net.OfflineCache
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

private const val POST_PUBLIC = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val POST_PRIVATE = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private const val POST_CAMPUS = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

private fun post(id: String, audience: String, visibility: String?) = JSONObject()
    .put("id", id).put("text", "secret $id").put("audience", audience)
    .apply { if (visibility != null) put("community", JSONObject().put("id", "c1").put("visibility", visibility)) }

class CommunityOfflineTest {
    @Before fun reset() = CommunityCachePolicy.forgetAll()

    private fun ids(body: String): List<String> {
        val arr = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body).getJSONArray("items")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }

    @Test fun homeFeedKeepsCampusAndPublicPostsButDropsPrivateCommunityPosts() {
        val body = JSONArray()
            .put(post(POST_CAMPUS, "campus", null))
            .put(post(POST_PUBLIC, "community", "public"))
            .put(post(POST_PRIVATE, "community", "private")).toString()
        val saved = CommunityCachePolicy.sanitize("https://x/posts?filter=campus", body)!!
        assertEquals(listOf(POST_CAMPUS, POST_PUBLIC), ids(saved))
        assertFalse(saved.contains("secret $POST_PRIVATE"))
    }

    @Test fun pagedResponsesAreFilteredToo() {
        val body = JSONObject().put("items", JSONArray().put(post(POST_PRIVATE, "community", "private")).put(post(POST_PUBLIC, "community", "public")))
            .put("nextCursor", JSONObject.NULL).toString()
        val saved = CommunityCachePolicy.sanitize("https://x/api/v1/communities/c1/posts?limit=30", body)!!
        assertEquals(listOf(POST_PUBLIC), ids(saved))
        assertTrue(JSONObject(saved).isNull("nextCursor"))
    }

    @Test fun aSinglePrivatePostIsNotSavedAndItsCommentsAreNotEither() {
        assertNull(CommunityCachePolicy.sanitize("https://x/posts/$POST_PRIVATE", post(POST_PRIVATE, "community", "private").toString()))
        assertNull(CommunityCachePolicy.sanitize("https://x/posts/$POST_PRIVATE/comments", """[{"id":"k1","text":"private reply"}]"""))
        // a public post's comments are saved as before
        assertNotNull(CommunityCachePolicy.sanitize("https://x/posts/$POST_PUBLIC/comments", """[{"id":"k2","text":"hello"}]"""))
    }

    @Test fun memberListsRequestsAndInvitationsAreNeverSaved() {
        for (url in listOf("https://x/api/v1/communities/c1/members?limit=50", "https://x/api/v1/communities/c1/requests?limit=50",
            "https://x/api/v1/community-invitations", "https://x/api/v1/communities/c1/invitations")) {
            assertNull(url, CommunityCachePolicy.sanitize(url, """{"items":[{"id":"u1"}]}"""))
        }
    }

    @Test fun searchHitsOfCommunityPostsAreDroppedBecauseTheirVisibilityIsUnknown() {
        val hit = JSONObject().put("id", POST_PRIVATE).put("snippet", "secret plans").put("audience", "community")
        val campusHit = JSONObject().put("id", POST_CAMPUS).put("snippet", "hello").put("audience", "campus")
        val body = JSONObject().put("users", JSONArray()).put("events", JSONArray())
            .put("posts", JSONArray().put(hit).put(campusHit)).put("communities", JSONArray()).toString()
        val saved = JSONObject(CommunityCachePolicy.sanitize("https://x/api/v1/search?q=plans", body)!!)
        assertEquals(1, saved.getJSONArray("posts").length())
        assertFalse(saved.toString().contains("secret plans"))
    }

    @Test fun communityProfilesAndOrdinaryDataAreSavedUnchanged() {
        val profile = """{"id":"c1","name":"Chess Club","membership":"join","visibility":"private","memberCount":3}"""
        assertEquals(profile, CommunityCachePolicy.sanitize("https://x/api/v1/communities/c1", profile))
        val other = """[{"id":"n1","type":"like"}]"""
        assertEquals(other, CommunityCachePolicy.sanitize("https://x/api/v1/notifications", other))
    }

    @Test fun garbledCommunityBodiesAreNotSaved() {
        assertNull(CommunityCachePolicy.sanitize("https://x/posts", """[{"audience":"community" oops"""))
    }

    // ---- the on-disk cache starts clean when the storage policy changes

    private lateinit var dir: File
    @Before fun makeDir() { dir = Files.createTempDirectory("offline").toFile() }
    @After fun removeDir() { dir.deleteRecursively() }

    @Test fun copiesSavedUnderAnOlderPolicyAreWipedOnce() {
        val first = OfflineCache(dir)
        first.useAccount("me")
        first.put("https://x/posts", "[\"old\"]")
        assertEquals("[\"old\"]", first.get("https://x/posts"))
        // Same account, same policy: kept across launches.
        OfflineCache(dir).also { it.useAccount("me"); assertEquals("[\"old\"]", it.get("https://x/posts")) }
        // An app update from before the policy existed (no marker): wiped, then current again.
        File(dir, ".policy").delete()
        OfflineCache(dir).also { it.useAccount("me"); assertNull(it.get("https://x/posts")) }
        OfflineCache(dir).also { it.useAccount("me"); it.put("https://x/posts", "[\"new\"]"); assertEquals("[\"new\"]", it.get("https://x/posts")) }
    }

    @Test fun anotherAccountNeverSeesThePreviousOnesCopies() {
        val c = OfflineCache(dir)
        c.useAccount("me")
        c.put("https://x/a", "mine")
        OfflineCache(dir).also { it.useAccount("someone-else"); assertNull(it.get("https://x/a")) }
    }
}
