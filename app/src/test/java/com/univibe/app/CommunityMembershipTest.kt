package com.univibe.app

import com.univibe.app.data.model.MembershipState
import com.univibe.app.data.model.NewPost
import com.univibe.app.data.model.PostAudience
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.HttpResponse
import com.univibe.app.data.repository.CommunityMembershipStore
import com.univibe.app.data.repository.MembershipActions
import com.univibe.app.data.repository.MembershipResult
import com.univibe.app.data.repository.RemoteCommunityRepository
import com.univibe.app.data.repository.RemoteFeedRepository
import com.univibe.app.data.repository.parseCommunity
import com.univibe.app.data.repository.parsePost
import com.univibe.app.domain.auth.AuthResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val C1 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val U1 = "22222222-2222-4222-8222-222222222222"

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityMembershipTest {

    private class Fake(var next: suspend () -> MembershipResult) : MembershipActions {
        var joins = 0
        var leaves = 0
        override suspend fun join(id: String): MembershipResult { joins++; return next() }
        override suspend fun leave(id: String): MembershipResult { leaves++; return next() }
    }

    private fun store(fake: Fake, scope: TestScope) = CommunityMembershipStore(fake, scope.backgroundScope)

    @Test fun joinShowsLoadingThenTheServersAnswer() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<MembershipResult>()
        val fake = Fake { gate.await() }
        val s = store(fake, this)
        s.seed(C1, MembershipState.Join, 10)
        s.click(C1)
        assertTrue(s.all.value[C1]!!.busy)
        assertEquals(MembershipState.Join, s.all.value[C1]!!.state) // no optimistic flip
        gate.complete(MembershipResult(MembershipState.Joined, 11))
        val after = s.all.value[C1]!!
        assertEquals(MembershipState.Joined, after.state)
        assertEquals(11, after.memberCount)
        assertTrue(!after.busy && after.error == null)
    }

    @Test fun repeatedTapsSendOneRequest() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<MembershipResult>()
        val fake = Fake { gate.await() }
        val s = store(fake, this)
        s.seed(C1, MembershipState.Join, 1)
        s.click(C1); s.click(C1); s.click(C1)
        assertEquals(1, fake.joins)
        gate.complete(MembershipResult(MembershipState.Joined, 2))
    }

    @Test fun failureRestoresThePreviousStateWithAnErrorAndRetryWorks() = runTest(UnconfinedTestDispatcher()) {
        val fake = Fake { throw IOException("offline") }
        val s = store(fake, this)
        s.seed(C1, MembershipState.Request, 5)
        s.click(C1)
        val failed = s.all.value[C1]!!
        assertEquals(MembershipState.Request, failed.state)
        assertNotNull(failed.error)
        assertTrue(!failed.busy)
        fake.next = { MembershipResult(MembershipState.Pending, 5) }
        s.click(C1)
        val ok = s.all.value[C1]!!
        assertEquals(MembershipState.Pending, ok.state)
        assertNull(ok.error)
    }

    @Test fun inviteOnlyRefusalGetsAClearMessage() = runTest(UnconfinedTestDispatcher()) {
        val s = store(Fake { throw ApiException(403, "invite_only", "x") }, this)
        s.seed(C1, MembershipState.Join, 1)
        s.click(C1)
        assertEquals("This community is invitation-only.", s.all.value[C1]!!.error)
    }

    @Test fun seedNeverOverwritesAndServerDataIsIgnoredWhileATapIsInFlight() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<MembershipResult>()
        val s = store(Fake { gate.await() }, this)
        s.seed(C1, MembershipState.Join, 1)
        s.seed(C1, MembershipState.Request, 99) // a stale card must not change what is known
        assertEquals(MembershipState.Join, s.all.value[C1]!!.state)
        s.click(C1)
        s.apply(C1, MembershipState.Unavailable, 0) // a refresh racing the tap
        assertTrue(s.all.value[C1]!!.busy)
        gate.complete(MembershipResult(MembershipState.Joined, 2))
        assertEquals(MembershipState.Joined, s.all.value[C1]!!.state)
    }

    @Test fun liveEventsAndFetchedDataUpdateEveryScreenAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val s = store(Fake { MembershipResult(MembershipState.Joined, 1) }, this)
        s.seed(C1, MembershipState.Pending, 3)
        s.apply(C1, MembershipState.Joined, 4) // approved by a moderator
        assertEquals(MembershipState.Joined, s.all.value[C1]!!.state)
        assertEquals(4, s.all.value[C1]!!.memberCount)
        s.clear()
        assertNull(s.all.value[C1])
    }

    @Test fun unavailableOffersNoAction() = runTest(UnconfinedTestDispatcher()) {
        val fake = Fake { MembershipResult(MembershipState.Joined, 1) }
        val s = store(fake, this)
        s.seed(C1, MembershipState.Unavailable, 1)
        s.click(C1)
        assertEquals(0, fake.joins + fake.leaves)
    }

    // ---- parsing and requests

    private fun community(membership: String, visibility: String = "public") =
        """{"id":"$C1","name":"Chess Club","slug":"chess-club","description":"Chess players on campus.","category":"clubs","visibility":"$visibility","membershipPolicy":"open","status":"active","memberCount":2450,"createdAt":"2026-10-19T10:00:00.000000Z","membership":"$membership","myRole":null,"muted":false,"avatarUrl":null,"coverUrl":null,"canPost":false,"rules":["Be kind"]}"""

    @Test fun communityJsonMapsMembershipAndCategory() {
        val c = parseCommunity(JSONObject(community("pending")))
        assertEquals(MembershipState.Pending, c.effectiveMembership)
        assertTrue(c.joinRequested)
        assertEquals("Clubs", c.category)
        assertEquals(2450, c.members)
        assertEquals(listOf("Be kind"), c.rules)
    }

    @Test fun postJsonCarriesTheCommunitySeparatelyFromTheAuthor() {
        val json = """{"id":"p1","author":{"id":"$U1","fullName":"Brian Otieno","handle":"brian","studentVerified":false,"avatarUrl":null},
            "ageSeconds":900,"text":"AI project","audience":"community","likeCount":0,"commentCount":0,"shareCount":0,"likedByMe":false,"savedByMe":false,
            "edited":false,"announcement":true,"media":[],"poll":null,
            "community":{"id":"$C1","name":"Computer Science & AI","slug":"cs-ai","visibility":"public","memberCount":2450,"avatarUrl":null,"membership":"join"}}"""
        val p = parsePost(JSONObject(json))
        assertEquals("Brian Otieno", p.author.name)
        assertEquals("Computer Science & AI", p.community!!.name)
        assertEquals(MembershipState.Join, p.community!!.membership)
        assertTrue(!p.community!!.isPrivate)
        assertTrue(p.announcement)
        assertTrue(p.audience is PostAudience.Community)
    }

    private class Call(val method: String, val path: String, val body: JSONObject?)
    private class FakeApi(val handler: (Call) -> AuthResult<HttpResponse>) : AuthedApi {
        val calls = mutableListOf<Call>()
        override suspend fun authedCall(method: String, path: String, body: JSONObject?): AuthResult<HttpResponse> {
            val c = Call(method, path, body); calls += c; return handler(c)
        }
    }

    @Test fun joinGoesToTheMembershipEndpointAndReportsTheServersState() = runTest {
        val seen = mutableListOf<Triple<String, MembershipState, Int>>()
        val api = FakeApi { AuthResult.Success(HttpResponse(202, """{"communityId":"$C1","membership":"pending","memberCount":7}""")) }
        val repo = RemoteCommunityRepository(api, { null }) { id, st, n -> seen += Triple(id, st, n) }
        val r = repo.join(C1)
        assertEquals(MembershipState.Pending, r.state)
        assertEquals("PUT", api.calls.single().method)
        assertEquals("/api/v1/communities/$C1/membership", api.calls.single().path)
        assertEquals(listOf(Triple(C1, MembershipState.Pending, 7)), seen)
    }

    @Test fun communityPostsCarryTheCommunityIdToTheServer() = runTest {
        val api = FakeApi { AuthResult.Success(HttpResponse(201, """{"id":"p1","author":{"id":"$U1","fullName":"A B","handle":"ab"},"text":"hi","audience":"community"}""")) }
        val repo = RemoteFeedRepository(api, null) { null }
        repo.publishPost(NewPost("hi", emptyList(), PostAudience.Community(C1, "Chess Club")))
        assertEquals(C1, api.calls.single().body!!.getString("communityId"))
    }

    @Test fun deleteUsesPostWithTheConfirmationName() = runTest {
        val api = FakeApi { c ->
            if (c.method == "GET") AuthResult.Success(HttpResponse(200, community("joined")))
            else AuthResult.Success(HttpResponse(204, ""))
        }
        RemoteCommunityRepository(api, { null }).deleteCommunity(C1)
        val del = api.calls.last()
        assertEquals("POST", del.method)
        assertEquals("/api/v1/communities/$C1/delete", del.path)
        assertEquals("Chess Club", del.body!!.getString("confirmName"))
    }
}
