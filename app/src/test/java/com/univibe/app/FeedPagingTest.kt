package com.univibe.app

import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.PostLimits
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.FileUploader
import com.univibe.app.data.net.MediaUploader
import com.univibe.app.data.net.HttpResponse
import com.univibe.app.data.repository.RemoteFeedRepository
import com.univibe.app.data.repository.parsePost
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthUser
import com.univibe.app.features.home.PAGE_SIZE
import com.univibe.app.features.home.mergeFirstPage
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

private const val AUTHOR = "22222222-2222-4222-8222-222222222222"

private fun post(id: String, media: String = "[]") = parsePost(
    JSONObject(
        """{"id":"$id","author":{"id":"$AUTHOR","fullName":"Brian Otieno","handle":"brianotieno","course":"Law","yearOfStudy":"","interests":[],"studentVerified":true,"hasPhoto":false,"universityName":"Uni","avatarUrl":null},"ageSeconds":60,"text":"t","audience":"campus","likeCount":0,"commentCount":0,"shareCount":0,"likedByMe":false,"savedByMe":false,"edited":false,"media":$media,"poll":null}""",
    ),
)

private fun posts(range: IntRange) = range.map { post("p$it") }

class FeedPagingTest {
    // ---- merging a refreshed first page into what was already loaded
    @Test fun refreshKeepsOlderPagesAndAddsNewPostsOnTop() {
        val old = posts(1..45)                       // three pages loaded
        val first = listOf(post("new")) + posts(1..19) // one new post pushed p20 out of the first page
        val merged = mergeFirstPage(old, first).map { it.id }
        assertEquals(listOf("new") + (1..45).map { "p$it" }, merged) // no gap at p20, no repeats
    }

    @Test fun refreshWithNothingNewChangesNothing() {
        val old = posts(1..45)
        assertEquals(old.map { it.id }, mergeFirstPage(old, posts(1..PAGE_SIZE)).map { it.id })
    }

    @Test fun aShortFirstPageMeansTheFeedIsThatShort() {
        assertEquals(listOf("p1", "p2"), mergeFirstPage(posts(1..45), posts(1..2)).map { it.id })
    }

    @Test fun whenMoreThanAPageOfNewPostsArrivedTheOldOnesFollow() {
        val first = posts(100..119) // none of these were loaded before
        val merged = mergeFirstPage(posts(1..30), first).map { it.id }
        assertEquals(first.map { it.id } + posts(1..30).map { it.id }, merged)
    }

    // ---- the page request
    @Test fun theFirstPageAsksForALimitAndLaterPagesNameTheLastPostSeen() = runTest {
        val seen = mutableListOf<String>()
        val api = object : AuthedApi {
            override suspend fun authedCall(method: String, path: String, body: JSONObject?): AuthResult<HttpResponse> {
                seen += path
                return AuthResult.Success(HttpResponse(200, "[]"))
            }
        }
        val repo = RemoteFeedRepository(api) { account }
        repo.getPostsPage(FeedFilter.Campus, null, 20)
        repo.getPostsPage(FeedFilter.Following, "abc", 20)
        assertEquals(listOf("/posts?filter=campus&limit=20", "/posts?filter=following&limit=20&before=abc"), seen)
    }

    // ---- known photo and video sizes
    @Test fun photoAndVideoSizesComeFromTheServer() {
        val p = post(
            "m",
            """[{"id":"1","kind":"image","url":"https://x/1","width":1200,"height":1600},{"id":"2","kind":"image","url":"https://x/2","width":null,"height":null}]""",
        )
        assertEquals(listOf(0.75f, null), p.mediaRatios)
        assertNull(p.videoRatio)
        val clip = post("v", """[{"id":"3","kind":"video","url":"https://x/3","width":1920,"height":1080}]""")
        assertEquals(1920f / 1080f, clip.videoRatio!!, 0.0001f)
    }

    @Test fun olderPostsWithoutSizesStillParse() {
        val p = post("o", """[{"id":"1","kind":"image","url":"https://x/1"}]""")
        assertEquals(listOf<Float?>(null), p.mediaRatios)
    }

    // ---- feed thumbnails
    @Test fun thumbnailsAreUsedOnlyWhenEveryPhotoHasOne() {
        val both = post("t1", """[{"id":"1","kind":"image","url":"https://x/1","thumbUrl":"https://x/1t"},{"id":"2","kind":"image","url":"https://x/2","thumbUrl":"https://x/2t"}]""")
        assertEquals(listOf("https://x/1t", "https://x/2t"), both.thumbPaths)
        val partly = post("t2", """[{"id":"1","kind":"image","url":"https://x/1","thumbUrl":"https://x/1t"},{"id":"2","kind":"image","url":"https://x/2","thumbUrl":null}]""")
        assertEquals(emptyList<String>(), partly.thumbPaths)
        assertEquals(emptyList<String>(), post("t3", """[{"id":"1","kind":"image","url":"https://x/1"}]""").thumbPaths)
    }

    // ---- size limits
    @Test fun tooLargeFilesAreRefusedBeforeAnyUpload() = runTest {
        val api = object : AuthedApi {
            override suspend fun authedCall(method: String, path: String, body: JSONObject?): AuthResult<HttpResponse> = error("nothing should be sent")
        }
        val uploader = MediaUploader(api, FileUploader { _, _, _ -> error("nothing should be uploaded") })
        val photo = File.createTempFile("big", ".jpg").apply { deleteOnExit() }
        RandomAccessFile(photo, "rw").use { it.setLength(PostLimits.MAX_IMAGE_UPLOAD_BYTES + 1) }
        try {
            uploader.upload(photo.path, "image", "post")
            fail("expected the photo to be refused")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("8 MB"))
        }
        val clip = File.createTempFile("big", ".mp4").apply { deleteOnExit() }
        RandomAccessFile(clip, "rw").use { it.setLength(PostLimits.MAX_VIDEO_BYTES + 1) }
        try {
            uploader.upload(clip.path, "video", "post")
            fail("expected the video to be refused")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("50 MB"))
        }
    }

    // ---- profile posts, single posts, saved pages
    private fun recordingApi(status: Int = 200, body: String = "[]", seen: MutableList<String>) = object : AuthedApi {
        override suspend fun authedCall(method: String, path: String, body2: JSONObject?): AuthResult<HttpResponse> {
            seen += path
            return AuthResult.Success(HttpResponse(status, body))
        }
    }

    @Test fun aProfileAsksForThatStudentsPostsOnly() = runTest {
        val seen = mutableListOf<String>()
        RemoteFeedRepository(recordingApi(seen = seen)) { account }.getPostsBy("abc", 3)
        assertEquals(listOf("/posts?author=abc&limit=3"), seen)
    }

    @Test fun savedPostsAskForPages() = runTest {
        val seen = mutableListOf<String>()
        val repo = RemoteFeedRepository(recordingApi(seen = seen)) { account }
        repo.getSavedPostsPage(null, 20)
        repo.getSavedPostsPage("xyz", 20)
        assertEquals(listOf("/posts/saved?limit=20", "/posts/saved?limit=20&before=xyz"), seen)
    }

    @Test fun aMissingPostIsNullNotAnError() = runTest {
        val gone = RemoteFeedRepository(recordingApi(status = 404, body = "{\"error\":\"not_found\",\"message\":\"Post not found.\"}", seen = mutableListOf())) { account }
        assertNull(gone.getPost("nope"))
    }

    @Test fun mergingSavedPagesUsesTheirOwnPageSize() {
        val old = posts(1..30)
        val merged = mergeFirstPage(old, posts(1..10), pageSize = 10).map { it.id }
        assertEquals(posts(1..30).map { it.id }, merged)
    }

    private val account = AuthUser(
        id = "11111111-1111-4111-8111-111111111111", fullName = "Wanjiru Kamau", email = "x@student.cuk.ac.ke", universityId = "cuk",
        universityName = "Uni", emailVerified = true, studentVerified = true, course = "Law", interests = emptyList(), bio = "", handle = "w",
    )
}
