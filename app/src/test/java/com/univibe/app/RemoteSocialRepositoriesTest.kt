package com.univibe.app

import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.NewPost
import com.univibe.app.data.model.PostAudience
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.HttpResponse
import com.univibe.app.data.net.timeAgo
import com.univibe.app.data.profile.InMemoryProfileLocalStore
import com.univibe.app.data.repository.MockData
import com.univibe.app.data.repository.MockProfileRepository
import com.univibe.app.data.repository.MockStore
import com.univibe.app.data.repository.RemoteCommentRepository
import com.univibe.app.data.repository.RemoteFeedRepository
import com.univibe.app.data.repository.RemoteProfileBackend
import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthUser
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

private const val ME = "11111111-1111-4111-8111-111111111111"
private const val OTHER = "22222222-2222-4222-8222-222222222222"

private class Call(val method: String, val path: String, val body: JSONObject?)

private class FakeApi(private val handler: (Call) -> AuthResult<HttpResponse>) : AuthedApi {
    val calls = mutableListOf<Call>()
    override suspend fun authedCall(method: String, path: String, body: JSONObject?): AuthResult<HttpResponse> {
        val call = Call(method, path, body)
        calls += call
        return handler(call)
    }
}

private fun ok(body: String, status: Int = 200) = AuthResult.Success(HttpResponse(status, body))
private fun none() = AuthResult.Success(HttpResponse(204, ""))
private fun serverError(status: Int, code: String) = AuthResult.Success(HttpResponse(status, """{"error":"$code","message":"msg"}"""))

private fun authorJson(id: String = OTHER, name: String = "Brian Otieno", handle: String = "brianotieno") =
    """{"id":"$id","fullName":"$name","handle":"$handle","course":"Law","yearOfStudy":"","interests":["Music"],"studentVerified":true,"hasPhoto":false,"universityName":"The Co-operative University of Kenya"}"""

private fun postJson(id: String = "p1", poll: String = "null", audience: String = "campus") =
    """{"id":"$id","author":${authorJson()},"ageSeconds":7300,"text":"hello","audience":"$audience","likeCount":3,"commentCount":2,"shareCount":1,"likedByMe":true,"savedByMe":false,"edited":true,"poll":$poll}"""

private val account = AuthUser(
    id = ME, fullName = "Wanjiru Kamau", email = "x@student.cuk.ac.ke", universityId = "cuk",
    universityName = "The Co-operative University of Kenya", emailVerified = true, studentVerified = true,
    course = "Law", interests = listOf("Music", "Debate"), bio = "Hi", handle = "wanjirukamau",
)

class RemoteSocialRepositoriesTest {
    @After fun resetIdentity() = MockData.applyAccount(null)

    private fun feed(api: FakeApi) = RemoteFeedRepository(api) { account }

    // ---- time
    @Test fun timeAgoUsesTheServersAge() {
        assertEquals("now", timeAgo(5))
        assertEquals("2m", timeAgo(125))
        assertEquals("3h", timeAgo(3 * 3600 + 10))
        assertEquals("2d", timeAgo(2 * 86400))
        assertEquals("3w", timeAgo(21 * 86400))
    }

    // ---- feed
    @Test fun postsAreParsedFromTheServer() = runTest {
        val api = FakeApi { ok("[${postJson(poll = """{"options":[{"text":"A","votes":2},{"text":"B","votes":0}],"myVote":0}""")}]") }
        val posts = feed(api).getPosts(FeedFilter.Following)
        assertEquals("GET /posts?filter=following", "${api.calls[0].method} ${api.calls[0].path}")
        val post = posts.single()
        assertEquals("p1", post.id)
        assertEquals(OTHER, post.author.id)
        assertEquals("@brianotieno", post.author.handle)
        assertEquals("2h", post.timeAgo)
        assertEquals(3, post.likeCount)
        assertTrue(post.likedByMe)
        assertTrue(post.edited)
        assertTrue(post.fromFollowing)
        assertEquals(PostAudience.Campus, post.audience)
        assertEquals(2, post.poll!!.options[0].votes)
        assertEquals(0, post.poll!!.myVote)
    }

    @Test fun aPostWithoutAPollOrVoteIsParsedToo() = runTest {
        val api = FakeApi { ok("[${postJson(audience = "followers")}]") }
        val post = feed(api).getPosts(FeedFilter.Campus).single()
        assertNull(post.poll)
        assertEquals(PostAudience.Followers, post.audience)
        assertFalse(post.fromFollowing)
    }

    @Test fun pollsWithoutAVoteHaveNoMyVote() = runTest {
        val api = FakeApi { ok("[${postJson(poll = """{"options":[{"text":"A","votes":0},{"text":"B","votes":1}],"myVote":null}""")}]") }
        assertNull(feed(api).getPosts(FeedFilter.Campus).single().poll!!.myVote)
    }

    @Test fun theCurrentUserIsTheRealAccountWithItsServerId() = runTest {
        val me = feed(FakeApi { ok("{}") }).getCurrentUser()
        assertEquals(ME, me.id)
        assertEquals("Wanjiru Kamau", me.name)
        assertEquals("@wanjirukamau", me.handle)
    }

    @Test fun thereAreNoStoriesAndNoMediaYet() = runTest {
        val repo = feed(FakeApi { ok("[]") })
        assertTrue(repo.getStories().isEmpty())
        assertFalse(repo.supportsMedia)
    }

    @Test fun likesAndSavesUsePutAndDelete() = runTest {
        val api = FakeApi { none() }
        val repo = feed(api)
        repo.setLiked("p1", true); repo.setLiked("p1", false)
        repo.setSaved("p1", true); repo.setSaved("p1", false)
        assertEquals(
            listOf("PUT /posts/p1/like", "DELETE /posts/p1/like", "PUT /posts/p1/save", "DELETE /posts/p1/save"),
            api.calls.map { "${it.method} ${it.path}" },
        )
    }

    @Test fun createPostSendsTextAudienceAndPoll() = runTest {
        val api = FakeApi { ok(postJson(), 201) }
        feed(api).createPost("  hi  ", hasMedia = false, pollOptions = listOf(" A ", "B"))
        val body = api.calls.single().body!!
        assertEquals("POST /posts", "${api.calls.single().method} ${api.calls.single().path}")
        assertEquals("hi", body.getString("text"))
        assertEquals("campus", body.getString("audience"))
        assertEquals("A", body.getJSONObject("poll").getJSONArray("options").getString(0))
    }

    @Test fun mediaPostsAreRefusedWithoutAnyRequest() = runTest {
        val api = FakeApi { ok(postJson(), 201) }
        val repo = feed(api)
        for (attempt in listOf<suspend () -> Unit>(
            { repo.createPost("x", hasMedia = true) },
            { repo.createPost("x", false, imagePath = "/a.jpg") },
            { repo.createPost("x", false, videoPath = "/a.mp4") },
            { repo.publishPost(NewPost("x", listOf("/a.jpg"), PostAudience.Campus)) },
            { repo.publishPost(NewPost("x", emptyList(), PostAudience.Community("tech", "Tech Club"))) },
        )) {
            try { attempt(); fail("expected a refusal") } catch (e: UnsupportedOperationException) { /* expected */ }
        }
        assertTrue(api.calls.isEmpty())
    }

    @Test fun publishPostMapsTheAudience() = runTest {
        val api = FakeApi { ok(postJson(), 201) }
        feed(api).publishPost(NewPost("only for followers", emptyList(), PostAudience.Followers))
        assertEquals("followers", api.calls.single().body!!.getString("audience"))
    }

    @Test fun editVoteDeleteShareAndReportHitTheRightRoutes() = runTest {
        val api = FakeApi { call ->
            when (call.method + " " + call.path) {
                "PUT /posts/p1", "POST /posts/p1/vote" -> ok(postJson())
                else -> none()
            }
        }
        val repo = feed(api)
        repo.editPost("p1", " fixed ")
        repo.vote("p1", 1)
        repo.deletePost("p1")
        repo.recordShare("p1")
        repo.reportPost("p1", "spam")
        assertEquals(
            listOf("PUT /posts/p1", "POST /posts/p1/vote", "DELETE /posts/p1", "POST /posts/p1/share", "POST /posts/p1/report"),
            api.calls.map { "${it.method} ${it.path}" },
        )
        assertEquals("fixed", api.calls[0].body!!.getString("text"))
        assertEquals(1, api.calls[1].body!!.getInt("optionIndex"))
        assertEquals("spam", api.calls[4].body!!.getString("reason"))
    }

    @Test fun serverRefusalsKeepTheirCode() = runTest {
        val repo = feed(FakeApi { serverError(409, "already_voted") })
        try { repo.vote("p1", 0); fail("expected a refusal") } catch (e: ApiException) {
            assertEquals(409, e.status)
            assertEquals("already_voted", e.code)
        }
    }

    @Test fun offlineAndExpiredSessionsBecomeExceptions() = runTest {
        try {
            feed(FakeApi { AuthResult.Failure(AuthError.Network) }).getPosts(FeedFilter.Campus)
            fail("expected an IOException")
        } catch (e: IOException) { /* expected */ }
        try {
            feed(FakeApi { AuthResult.Failure(AuthError.SessionExpired) }).getPosts(FeedFilter.Campus)
            fail("expected an ApiException")
        } catch (e: ApiException) { assertEquals(401, e.status) }
    }

    // ---- comments
    private fun commentJson(id: String, parent: String? = null, replyTo: String? = null) =
        """{"id":"$id","postId":"p1","author":${authorJson()},"text":"hey","ageSeconds":90,"likeCount":1,"likedByMe":false,"isMine":false,"isPostAuthor":true,"parentId":${parent?.let { "\"$it\"" } ?: "null"},"replyToHandle":${replyTo?.let { "\"$it\"" } ?: "null"}}"""

    @Test fun commentsAreParsedWithThreadsAndMentions() = runTest {
        val api = FakeApi { ok("[${commentJson("c1")},${commentJson("c2", "c1", "brianotieno")}]") }
        val comments = RemoteCommentRepository(api).getComments("p1")
        assertEquals("GET /posts/p1/comments", "${api.calls[0].method} ${api.calls[0].path}")
        assertNull(comments[0].parentId)
        assertEquals("c1", comments[1].parentId)
        assertEquals("@brianotieno", comments[1].replyToHandle)
        assertEquals("1m", comments[0].timeAgo)
        assertTrue(comments[0].isPostAuthor)
    }

    @Test fun addingLikingAndDeletingCommentsUseTheRightRoutes() = runTest {
        val api = FakeApi { call -> if (call.method == "POST" && call.path.endsWith("/comments")) ok(commentJson("c9"), 201) else none() }
        val repo = RemoteCommentRepository(api)
        assertEquals("c9", repo.addComment("p1", " hi ", replyToId = "c1").id)
        repo.setLiked("c9", true); repo.setLiked("c9", false)
        repo.deleteComment("c9")
        repo.reportComment("c9", "rude")
        assertEquals("hi", api.calls[0].body!!.getString("text"))
        assertEquals("c1", api.calls[0].body!!.getString("replyToId"))
        assertEquals(
            listOf("POST /posts/p1/comments", "PUT /comments/c9/like", "DELETE /comments/c9/like", "DELETE /comments/c9", "POST /comments/c9/report"),
            api.calls.map { "${it.method} ${it.path}" },
        )
    }

    // ---- people
    @Test fun statsFollowListsAndProfilesComeFromTheServer() = runTest {
        val api = FakeApi { call ->
            when {
                call.path == "/me/stats" -> ok("""{"posts":4,"followers":2,"following":1,"saved":3}""")
                call.path == "/students/$OTHER" -> ok("""{"student":${authorJson()},"bio":"Hello","posts":5,"followers":6,"following":7,"followedByMe":true}""")
                call.path == "/students/missing" -> serverError(404, "not_found")
                call.path == "/me/followers" -> ok("""[{"student":${authorJson()},"isFollowing":false}]""")
                call.path == "/me/following" -> ok("""[{"student":${authorJson()},"isFollowing":true}]""")
                else -> none()
            }
        }
        val backend = RemoteProfileBackend(api)
        assertEquals(4, backend.myStats().posts)
        assertEquals(3, backend.myStats().saved)
        val profile = backend.studentProfile(OTHER)!!
        assertEquals("Hello", profile.bio)
        assertEquals(6, profile.followers)
        assertNull(backend.studentProfile("missing"))
        assertFalse(backend.followers().single().isFollowing)
        assertTrue(backend.following().single().isFollowing)
        backend.setFollowing(OTHER, true)
        backend.setFollowing(OTHER, false)
        assertEquals("PUT /students/$OTHER/follow", "${api.calls[api.calls.size - 2].method} ${api.calls[api.calls.size - 2].path}")
        assertEquals("DELETE", api.calls.last().method)
    }

    @Test fun myProfileShowsTheServersCountsNotInventedOnes() = runTest {
        val api = FakeApi { ok("""{"posts":4,"followers":2,"following":1,"saved":3}""") }
        val repo = MockProfileRepository(
            MockStore(demo = false), InMemoryProfileLocalStore(), account = { account }, backend = RemoteProfileBackend(api),
        )
        val profile = repo.getMyProfile()
        assertEquals(4, profile.posts)
        assertEquals(2, profile.followers)
        assertEquals(1, profile.following)
        assertEquals(3, profile.savedPosts)
    }

    @Test fun offlineProfileShowsZerosNotSampleNumbers() = runTest {
        val api = FakeApi { AuthResult.Failure(AuthError.Network) }
        val repo = MockProfileRepository(
            MockStore(demo = false), InMemoryProfileLocalStore(), account = { account }, backend = RemoteProfileBackend(api),
        )
        val profile = repo.getMyProfile()
        assertEquals(0, profile.posts)
        assertEquals(0, profile.followers)
    }

    @Test fun otherStudentsAreLoadedFromTheServerWithWhatYouShare() = runTest {
        val api = FakeApi { ok("""{"student":${authorJson()},"bio":"Hello","posts":5,"followers":6,"following":7,"followedByMe":false}""") }
        val repo = MockProfileRepository(
            MockStore(demo = false), InMemoryProfileLocalStore(), account = { account }, backend = RemoteProfileBackend(api),
        )
        val profile = repo.getStudentProfile(OTHER)!!
        assertEquals("Brian Otieno", profile.student.name)
        assertEquals(listOf("Music"), profile.student.shared) // both have Music
        assertEquals("Law", profile.student.course)
        assertTrue(profile.student.sameCourse)
        // your own server id opens your own profile, without asking the server about yourself
        val before = api.calls.size
        assertEquals("Wanjiru Kamau", repo.getStudentProfile(ME)!!.student.name)
        assertTrue(api.calls.size >= before) // only the counts call, never /students/ME
        assertTrue(api.calls.none { it.path == "/students/$ME" })
    }

    @Test fun followListsAndFollowingCallTheServerNotTheSamplePeople() = runTest {
        val api = FakeApi { call ->
            when (call.path) {
                "/me/followers" -> ok("[]")
                "/me/following" -> ok("[]")
                else -> none()
            }
        }
        val repo = MockProfileRepository(
            MockStore(demo = false), InMemoryProfileLocalStore(), account = { account }, backend = RemoteProfileBackend(api),
        )
        assertTrue(repo.getFollowers().isEmpty())
        assertTrue(repo.getFollowing().isEmpty())
        repo.setFollowing(OTHER, true)
        assertEquals("PUT /students/$OTHER/follow", "${api.calls.last().method} ${api.calls.last().path}")
    }

    @Test fun aRealAccountStoreHasNoSamplePosts() {
        assertTrue(MockStore(demo = false).posts.isEmpty())
        assertFalse(MockStore().posts.isEmpty())
    }
}
