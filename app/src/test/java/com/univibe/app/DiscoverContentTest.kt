package com.univibe.app

import com.univibe.app.data.model.Post
import com.univibe.app.data.repository.DiscoverContentRepository
import com.univibe.app.data.repository.DiscoverPage
import com.univibe.app.data.repository.DiscoverReason
import com.univibe.app.data.repository.DiscoverTopic
import com.univibe.app.data.repository.MockData
import com.univibe.app.data.repository.MockFeedRepository
import com.univibe.app.data.repository.TrendingWindow
import com.univibe.app.features.discover.DiscoverCategory
import com.univibe.app.features.discover.DiscoverContentViewModel
import com.univibe.app.features.discover.ListKey
import com.univibe.app.features.discover.firstHashtag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

private fun post(id: String, text: String = "hello", authorId: String = "a1") =
    Post(id = id, author = MockData.aisha.copy(id = authorId), timeAgo = "1h", text = text, likeCount = 0, commentCount = 0)

/** A scripted server: pages are handed out by cursor, and any call can be made to fail. */
private class FakeContent : DiscoverContentRepository {
    var pages: Map<String?, DiscoverPage> = mapOf(null to DiscoverPage(listOf(post("p1"), post("p2")), "c1"), "c1" to DiscoverPage(listOf(post("p2"), post("p3")), null))
    var failing = false
    var failCursor: String? = "none"
    val forYouCalls = mutableListOf<String?>()
    var trendingCalls = 0
    val feedback = mutableListOf<String>()

    override suspend fun forYou(cursor: String?): DiscoverPage {
        forYouCalls += cursor
        if (failing || cursor == failCursor) throw IOException("down")
        return pages.getValue(cursor)
    }
    override suspend fun trending(window: TrendingWindow, cursor: String?): DiscoverPage { trendingCalls++; return DiscoverPage(listOf(post("t-${window.query}")), null) }
    override suspend fun photos(cursor: String?) = DiscoverPage(emptyList(), null)
    override suspend fun videos(cursor: String?) = DiscoverPage(emptyList(), null)
    override suspend fun topics() = listOf(DiscoverTopic("exams", 4, 3))
    override suspend fun topicPosts(tag: String, cursor: String?) = DiscoverPage(emptyList(), null)
    override suspend fun notInterested(postId: String) { if (failing) throw IOException("down"); feedback += "ni:$postId" }
    override suspend fun hideAuthor(authorId: String) { feedback += "ha:$authorId" }
    override suspend fun muteTopic(tag: String) { feedback += "mt:$tag" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverContentTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(content: FakeContent) = DiscoverContentViewModel(content, MockFeedRepository())

    @Test fun firstPageLoadsAndNextPageAppendsWithoutRepeats() {
        val content = FakeContent()
        val vm = vm(content)
        assertEquals(listOf("p1", "p2"), vm.state.value.list(ListKey.ForYou).posts.map { it.id })
        vm.loadMore(ListKey.ForYou)
        val list = vm.state.value.list(ListKey.ForYou)
        assertEquals(listOf("p1", "p2", "p3"), list.posts.map { it.id }) // p2 came twice from the server, shown once
        assertTrue(list.endReached)
        vm.loadMore(ListKey.ForYou) // nothing more: no extra request
        assertEquals(listOf<String?>(null, "c1"), content.forYouCalls)
    }

    @Test fun aFailedFirstLoadShowsAnErrorAndRetryRecovers() {
        val content = FakeContent().apply { failing = true }
        val vm = vm(content)
        assertNotNull(vm.state.value.list(ListKey.ForYou).error)
        assertTrue(vm.state.value.list(ListKey.ForYou).posts.isEmpty())
        content.failing = false
        vm.load(ListKey.ForYou, force = true)
        assertNull(vm.state.value.list(ListKey.ForYou).error)
        assertEquals(2, vm.state.value.list(ListKey.ForYou).posts.size)
    }

    @Test fun aFailedRefreshKeepsWhatIsOnScreenAndSaysItMayBeOld() {
        val content = FakeContent()
        val vm = vm(content)
        content.failing = true
        vm.load(ListKey.ForYou, force = true)
        val list = vm.state.value.list(ListKey.ForYou)
        assertEquals(2, list.posts.size)
        assertTrue(list.stale)
        assertNull(list.error)
    }

    @Test fun aFailedNextPageIsNotRetriedInALoop() {
        val content = FakeContent().apply { failCursor = "c1" }
        val vm = vm(content)
        vm.loadMore(ListKey.ForYou)
        vm.loadMore(ListKey.ForYou)
        vm.loadMore(ListKey.ForYou)
        assertEquals(listOf<String?>(null, "c1"), content.forYouCalls) // one attempt, then it waits for the student
        assertTrue(vm.state.value.list(ListKey.ForYou).moreFailed)
        content.failCursor = "none"
        vm.retryMore(ListKey.ForYou)
        assertEquals(3, vm.state.value.list(ListKey.ForYou).posts.size)
    }

    @Test fun switchingCategoriesKeepsEachListAndDoesNotReload() {
        val content = FakeContent()
        val vm = vm(content)
        vm.select(DiscoverCategory.Trending)
        vm.select(DiscoverCategory.ForYou)
        vm.select(DiscoverCategory.Trending)
        assertEquals(1, content.forYouCalls.size)
        assertEquals(1, content.trendingCalls)
        // each trending window is its own list
        vm.setWindow(TrendingWindow.Week)
        assertEquals(listOf("t-7d"), vm.state.value.list(ListKey.Trending(TrendingWindow.Week)).posts.map { it.id })
        assertEquals(listOf("t-24h"), vm.state.value.list(ListKey.Trending(TrendingWindow.Day)).posts.map { it.id })
        vm.select(DiscoverCategory.Trending) // tapping the selected chip reloads it
        assertEquals(3, content.trendingCalls)
    }

    @Test fun notInterestedRemovesThePostEverywhereAndTellsTheServer() {
        val content = FakeContent()
        val vm = vm(content)
        vm.openMenu(vm.state.value.list(ListKey.ForYou).posts.first())
        vm.notInterested()
        assertEquals(listOf("p2"), vm.state.value.list(ListKey.ForYou).posts.map { it.id })
        assertEquals(listOf("ni:p1"), content.feedback)
        assertNull(vm.state.value.menuPost)
    }

    @Test fun ifTheServerDidNotRecordFeedbackTheStudentIsToldAndTheListIsReread() {
        val content = FakeContent()
        val vm = vm(content)
        content.failing = true
        vm.openMenu(vm.state.value.list(ListKey.ForYou).posts.first())
        vm.notInterested()
        assertTrue(vm.state.value.notice!!.startsWith("Couldn't save"))
        assertTrue(content.feedback.isEmpty())
    }

    @Test fun hidingAnAuthorRemovesAllTheirPostsButNotOthers() {
        val content = FakeContent().apply { pages = mapOf(null to DiscoverPage(listOf(post("p1", authorId = "x"), post("p2", authorId = "y"), post("p3", authorId = "x")), null)) }
        val vm = vm(content)
        vm.openMenu(vm.state.value.list(ListKey.ForYou).posts.first())
        vm.hideAuthor()
        assertEquals(listOf("p2"), vm.state.value.list(ListKey.ForYou).posts.map { it.id })
        assertEquals(listOf("ha:x"), content.feedback)
    }

    @Test fun mutingATopicRemovesMatchingPostsAndTheTopic() {
        val content = FakeContent().apply { pages = mapOf(null to DiscoverPage(listOf(post("p1", "so tired #Exams"), post("p2", "lunch?")), null)) }
        val vm = vm(content)
        vm.select(DiscoverCategory.Topics)
        assertEquals(listOf("exams"), vm.state.value.topics.topics.map { it.tag })
        vm.muteTopic("exams")
        assertEquals(listOf("p2"), vm.state.value.list(ListKey.ForYou).posts.map { it.id })
        assertTrue(vm.state.value.topics.topics.isEmpty())
        assertEquals(listOf("mt:exams"), content.feedback)
    }

    @Test fun likesAreOptimisticAndShownInEveryListThePostIsIn() {
        val content = FakeContent()
        val vm = vm(content)
        vm.toggleLike("p1")
        val p = vm.state.value.list(ListKey.ForYou).posts.first { it.id == "p1" }
        assertTrue(p.likedByMe)
        assertEquals(1, p.likeCount)
        vm.toggleLike("p1")
        assertFalse(vm.state.value.list(ListKey.ForYou).posts.first { it.id == "p1" }.likedByMe)
    }

    @Test fun reasonsAreOnlyShownForKnownServerSignals() {
        assertEquals("From someone you follow", DiscoverReason.label("following"))
        assertEquals("Trending on your campus", DiscoverReason.label("trending_campus"))
        assertNull(DiscoverReason.label(null))
        assertNull(DiscoverReason.label("your_classmates_liked_this")) // never invents a social reason
    }

    @Test fun hashtagsAreFoundTheWayTheServerNormalisesThem() {
        assertEquals("exams", firstHashtag(post("p", "so tired #Exams #later")))
        assertNull(firstHashtag(post("p", "no tags, just a#b and # alone")))
    }
}
