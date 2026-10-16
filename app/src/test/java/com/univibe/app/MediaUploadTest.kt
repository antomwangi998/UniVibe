package com.univibe.app

import com.univibe.app.data.auth.Clock
import com.univibe.app.data.auth.InMemorySecureStore
import com.univibe.app.data.auth.RemoteAuthRepository
import com.univibe.app.data.model.NewPost
import com.univibe.app.data.model.PostAudience
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.FileUploader
import com.univibe.app.data.net.HttpRequest
import com.univibe.app.data.net.HttpResponse
import com.univibe.app.data.net.HttpTransport
import com.univibe.app.data.net.MediaUploader
import com.univibe.app.data.profile.InMemoryProfileLocalStore
import com.univibe.app.data.repository.MockData
import com.univibe.app.data.repository.MockProfileRepository
import com.univibe.app.data.repository.MockStore
import com.univibe.app.data.repository.RemoteFeedRepository
import com.univibe.app.data.repository.parsePost
import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.AuthUser
import com.univibe.app.domain.auth.PhotoChange
import com.univibe.app.domain.auth.ProfileEdit
import com.univibe.app.data.model.ProfileUpdate
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

private const val BASE = "https://api.example.test"

private class UploadCall(val method: String, val path: String, val body: JSONObject?)

private class UploadApi(private val handler: (UploadCall) -> AuthResult<HttpResponse>) : AuthedApi {
    val calls = mutableListOf<UploadCall>()
    override suspend fun authedCall(method: String, path: String, body: JSONObject?): AuthResult<HttpResponse> {
        calls += UploadCall(method, path, body)
        return handler(calls.last())
    }
}

private class FakeFiles(private val fail: Boolean = false) : FileUploader {
    val sent = mutableListOf<Triple<String, String, File>>()
    override suspend fun put(url: String, contentType: String, file: File) {
        if (fail) throw IOException("connection lost")
        sent += Triple(url, contentType, file)
    }
}

private fun ok(body: String, status: Int = 200) = AuthResult.Success(HttpResponse(status, body))

/** The server's answers to the three upload steps; every upload gets the id "m<n>". */
private class UploadServer {
    var next = 0
    fun answer(call: UploadCall): AuthResult<HttpResponse> = when {
        call.method == "POST" && call.path == "/media/uploads" -> {
            next++
            ok(
                """{"mediaId":"m$next","uploadUrl":"https://upload.test/m$next","headers":{"Content-Type":"x"},"expiresIn":600}""",
                201
            )
        }
        call.path.endsWith("/complete") -> ok("""{"id":"x","kind":"image"}""")
        else -> ok("{}")
    }
}

// Ensure the prefix used for createTempFile is at least 3 characters long
private fun temp(name: String, ext: String, bytes: Int = 8): File {
    val prefix = if (name.length >= 3) name else name.padEnd(3, '_')
    return File.createTempFile(prefix, ext).apply {
        writeBytes(ByteArray(bytes) { 1 })
        deleteOnExit()
    }
}

private fun authorJson() =
    """{"id":"22222222-2222-4222-8222-222222222222","fullName":"Brian","handle":"brian","course":"Law","yearOfStudy":"","interests":[],"studentVerified":true,"avatarUrl":"https://files.test/a.jpg"[...]"""

internal val account = AuthUser(
    id = "11111111-1111-4111-8111-111111111111", fullName = "Wanjiru Kamau", email = "x@student.cuk.ac.ke",
    universityId = "cuk", universityName = "The Co-operative University of Kenya", emailVerified = true, studentVerified = true,
    handle = "wanjirukamau", avatarUrl = "https://files.test/old.jpg?sig=1", coverUrl = null,
)

class MediaUploadTest {
    @After fun resetIdentity() = MockData.applyAccount(null)

    // ---- the three-step upload
    @Test fun aPhotoIsRequestedSentAndConfirmed() = runTest {
        val server = UploadServer()
        val api = UploadApi { server.answer(it) }
        val files = FakeFiles()
        val file = temp("pic", ".jpg", 20)
        val id = MediaUploader(api, files).upload(file.path, "image", "post")

        assertEquals("m1", id)
        assertEquals(listOf("POST /media/uploads", "POST /media/m1/complete"), api.calls.map { "${it.method} ${it.path}" })
        val asked = api.calls[0].body!!
        assertEquals("image", asked.getString("kind"))
        assertEquals("post", asked.getString("purpose"))
        assertEquals("image/jpeg", asked.getString("contentType"))
        assertEquals(20, asked.getInt("size"))
        assertEquals(Triple("https://upload.test/m1", "image/jpeg", file), files.sent.single())
    }

    @Test fun aVideoIsLabelledAsVideo() = runTest {
        val server = UploadServer()
        val api = UploadApi { server.answer(it) }
        val files = FakeFiles()
        MediaUploader(api, files).upload(temp("clip", ".mp4").path, "video", "post")
        assertEquals("video/mp4", files.sent.single().second)
    }

    @Test fun aMissingOrEmptyFileNeverReachesTheServer() = runTest {
        val api = UploadApi { ok("{}") }
        val uploader = MediaUploader(api, FakeFiles())
        for (path in listOf("/no/such/file.jpg", File.createTempFile("empty", ".jpg").path)) {
            try { uploader.upload(path, "image", "post"); fail("expected an IOException") } catch (e: IOException) { /* expected */ }
        }
        assertTrue(api.calls.isEmpty())
    }

    @Test fun aFailedTransferIsNotConfirmed() = runTest {
        val server = UploadServer()
        val api = UploadApi { server.answer(it) }
        try {
            MediaUploader(api, FakeFiles(fail = true)).upload(temp("pic", ".jpg").path, "image", "post")
            fail("expected an IOException")
        } catch (e: IOException) { /* expected */ }
        assertEquals(listOf("POST /media/uploads"), api.calls.map { "${it.method} ${it.path}" })
    }

    // ---- posts with media
    @Test fun aPhotoAndVideoPostUploadsFirstThenSendsTheIds() = runTest {
        val server = UploadServer()
        val api = UploadApi { call ->
            if (call.path == "/posts") ok(
                """{"id":"p1","author":${authorJson()},"ageSeconds":1,"text":"","audience":"campus","media":[{"id":"m1","kind":"image","url":"https://files.test/a.jpg"}],"imagePaths":["https://fi[...]""", 201
            ) else server.answer(call)
        }
        val repo = RemoteFeedRepository(api, MediaUploader(api, FakeFiles())) { account }
        val post = repo.createPost("", hasMedia = true, imagePath = temp("pic", ".jpg").path)
        val body = api.calls.last().body!!
        assertEquals("m1", body.getJSONArray("mediaIds").getString(0))
        assertEquals(listOf("https://files.test/a.jpg"), post.imagePaths)
        assertEquals("https://files.test/a.jpg", post.imagePath)
    }

    @Test fun publishingSeveralPhotosKeepsTheirOrder() = runTest {
        val server = UploadServer()
        val api = UploadApi { call -> if (call.path == "/posts") ok(
            """{"id":"p1","author":${authorJson()},"text":"t","audience":"campus"}""", 201
        ) else server.answer(call) }
        val repo = RemoteFeedRepository(api, MediaUploader(api, FakeFiles())) { account }
        repo.publishPost(NewPost("t", listOf(temp("a", ".jpg").path, temp("b", ".jpg").path, temp("c", ".jpg").path), PostAudience.Campus))
        val ids = api.calls.last().body!!.getJSONArray("mediaIds")
        assertEquals(listOf("m1", "m2", "m3"), (0 until ids.length()).map { ids.getString(it) })
    }

    @Test fun aFailedUploadMeansNoPostIsCreated() = runTest {
        val server = UploadServer()
        val api = UploadApi { server.answer(it) }
        val repo = RemoteFeedRepository(api, MediaUploader(api, FakeFiles(fail = true))) { account }
        try { repo.createPost("hi", hasMedia = true, imagePath = temp("pic", ".jpg").path); fail("expected an IOException") } catch (e: IOException) { /* expected */ }
        assertTrue(api.calls.none { it.path == "/posts" })
    }

    @Test fun mediaInAPostBecomesImagesAndAVideo() {
        val photos = parsePost(JSONObject("""{"id":"p","author":${authorJson()},"text":"t","media":[{"id":"1","kind":"image","url":"https://f/1"},{"id":"2","kind":"image","url":"https://f/2"}]}"""[...]")
        assertEquals(listOf("https://f/1", "https://f/2"), photos.imagePaths)
        assertNull(photos.videoPath)
        val clip = parsePost(JSONObject("""{"id":"p","author":${authorJson()},"text":"t","media":[{"id":"1","kind":"video","url":"https://f/v"}]}"""))
        assertEquals("https://f/v", clip.videoPath)
        assertTrue(clip.imagePaths.isEmpty())
        val plain = parsePost(JSONObject("""{"id":"p","author":${authorJson()},"text":"t","media":[]}"""))
        assertNull(plain.videoPath)
        assertNull(plain.imagePath)
    }

    // ---- profile and cover photos
    private class Clock0 : Clock { override fun nowMs() = 1_000_000L }

    private fun session(avatarUrl: String? = null) = JSONObject(
        """{"accessToken":"a1","refreshToken":"r1","expiresIn":900,"user":${user(avatarUrl)}}""",
    ).toString()

    private fun user(avatarUrl: String?) = JSONObject()
        .put("id", "u1").put("fullName", "Wanjiru Kamau").put("email", "x@student.cuk.ac.ke").put("universityId", "cuk")
        .put("universityName", "CUK").put("emailVerified", true).put("studentVerified", true).put("course", "Law")
        .put("interests", org.json.JSONArray()).put("bio", "").put("hasPhoto", avatarUrl != null).put("handle", "w")
        .put("yearOfStudy", "").put("onboardingComplete", true)
        .put("avatarUrl", avatarUrl ?: JSONObject.NULL).put("coverUrl", JSONObject.NULL).toString()

    private class RouteTransport(private val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse = handler(request)
    }

    private class Server(val upload: UploadServer = UploadServer()) {
        var sessionBody = ""
        var profileBody = ""
        val requests = mutableListOf<HttpRequest>()
        val transport: HttpTransport = RouteTransport { request ->
            requests += request
            val path = request.url.removePrefix(BASE)
            when (path) {
                "/auth/login" -> HttpResponse(200, sessionBody)
                "/me/photos" -> HttpResponse(200, "{}")
                "/me/profile" -> HttpResponse(200, profileBody)
                else -> (upload.answer(UploadCall(request.method, path, request.body?.let { JSONObject(it) })) as AuthResult.Success).value
            }
        }
        fun routes() = requests.map { "${it.method} ${it.url.removePrefix(BASE)}" }
    }

    private suspend fun signedIn(server: Server, files: FileUploader?): RemoteAuthRepository {
        val url = "https://files.test/old.jpg"
        server.sessionBody = session(url)
        server.profileBody = user("https://files.test/new.jpg")
        val repo = RemoteAuthRepository(BASE, server.transport, InMemorySecureStore(), Clock0(), files)
        assertTrue(repo.logIn("x@student.cuk.ac.ke", "correct-horse-9", true) is AuthResult.Success)
        return repo
    }

    @Test fun aReplacedProfilePhotoIsUploadedBeforeTheProfileIsSaved() = runTest {
        val server = Server()
        val files = FakeFiles()
        val repo = signedIn(server, files)
        server.requests.clear()
        val file = temp("me", ".jpg")
        val result = repo.updateProfile(ProfileEdit("Wanjiru Kamau", "Law", "", emptyList(), "", avatar = PhotoChange.Replace(file.path), cover = PhotoChange.Remove))
        assertTrue(result is AuthResult.Success)
        assertEquals(
            listOf("POST /media/uploads", "POST /media/m1/complete", "PUT /me/photos", "PUT /me/profile"),
            server.routes(),
        )
        val photos = JSONObject(server.requests[2].body!!)
        assertEquals("m1", photos.getString("avatarMediaId"))
        assertTrue(photos.isNull("coverMediaId"))
        assertEquals(1, files.sent.size)
        assertEquals("https://files.test/new.jpg", (repo.session.value as AuthState.Authenticated).user.avatarUrl)
    }

    @Test fun keepingPhotosMakesNoPhotoRequests() = runTest {
        val server = Server()
        val repo = signedIn(server, FakeFiles())
        server.requests.clear()
        assertTrue(repo.updateProfile(ProfileEdit("Wanjiru Kamau", "Law", "", emptyList(), "")) is AuthResult.Success)
        assertEquals(listOf("PUT /me/profile"), server.routes())
    }

    @Test fun aFailedPhotoUploadStopsTheWholeSave() = runTest {
        val server = Server()
        val repo = signedIn(server, FakeFiles(fail = true))
        server.requests.clear()
        val result = repo.updateProfile(ProfileEdit("Wanjiru Kamau", "Law", "", emptyList(), "", avatar = PhotoChange.Replace(temp("me", ".jpg").path)))
        assertEquals(AuthError.Network, (result as AuthResult.Failure).error)
        assertTrue(server.requests.none { it.url.endsWith("/me/profile") || it.url.endsWith("/me/photos") })
    }

    @Test fun withoutAnUploaderAPhotoChangeFailsCleanly() = runTest {
        val server = Server()
        val repo = signedIn(server, null)
        val result = repo.updateProfile(ProfileEdit("Wanjiru Kamau", "Law", "", emptyList(), "", avatar = PhotoChange.Replace(temp("me", ".jpg").path)))
        assertTrue(result is AuthResult.Failure)
    }

    // ---- what the profile screen asks the data layer for
    @Test fun theProfileScreenSendsOnlyWhatChanged() = runTest {
        var saved: ProfileEdit? = null
        val repo = MockProfileRepository(
            MockStore(demo = false), InMemoryProfileLocalStore(), account = { account },
            saveAccount = { edit -> saved = edit; true },
        )
        val local = temp("new", ".jpg").path
        repo.updateProfile(ProfileUpdate("Wanjiru Kamau", "", "Law", "", emptyList(), local, "https://files.test/cover.jpg?sig=9"))
        assertEquals(PhotoChange.Replace(local), saved!!.avatar)
        assertEquals(PhotoChange.Keep, saved!!.cover) // the account had no cover and the screen still shows a server link

        repo.updateProfile(ProfileUpdate("Wanjiru Kamau", "", "Law", "", emptyList(), "https://files.test/old.jpg?sig=1", null))
        assertEquals(PhotoChange.Keep, saved!!.avatar) // unchanged

        repo.updateProfile(ProfileUpdate("Wanjiru Kamau", "", "Law", "", emptyList(), null, null))
        assertEquals(PhotoChange.Remove, saved!!.avatar) // the account had one and the student removed it
    }

    @Test fun theProfileShowsThePhotosFromTheServer() = runTest {
        val repo = MockProfileRepository(
            MockStore(demo = false), InMemoryProfileLocalStore(),
            account = { account.copy(coverUrl = "https://files.test/cover.jpg") },
        )
        val profile = repo.getMyProfile()
        assertEquals("https://files.test/old.jpg?sig=1", profile.avatarPath)
        assertEquals("https://files.test/cover.jpg", profile.coverPath)
    }
}
