package com.univibe.app

import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.FileUploader
import com.univibe.app.data.net.HttpResponse
import com.univibe.app.data.net.MediaUploader
import com.univibe.app.data.net.UploadTracker
import com.univibe.app.domain.auth.AuthResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class UploadProgressTest {
    private val api = object : AuthedApi {
        override suspend fun authedCall(method: String, path: String, body: JSONObject?): AuthResult<HttpResponse> =
            AuthResult.Success(
                if (path == "/media/uploads") HttpResponse(201, """{"mediaId":"m1","uploadUrl":"https://storage.test/up"}""") else HttpResponse(200, "{}"),
            )
    }
    private fun photo() = File.createTempFile("pic", ".jpg").apply { writeBytes(ByteArray(10) { 1 }); deleteOnExit() }

    @Before fun clean() = UploadTracker.reset()
    @After fun after() = UploadTracker.reset()

    @Test fun progressIsReportedAndTheTrackerIsClearedWhenTheUploadEnds() = runBlocking {
        val seen = mutableListOf<Float>()
        val during = mutableListOf<List<UploadTracker.Upload>>()
        val files = object : FileUploader {
            override suspend fun put(url: String, contentType: String, file: File) = Unit
            override suspend fun put(url: String, contentType: String, file: File, onProgress: (Long, Long) -> Unit) {
                onProgress(50, 100)
                during += UploadTracker.uploads.value
                onProgress(100, 100)
            }
        }
        val id = MediaUploader(api, files).upload(photo().path, "image", "message", tag = "local-1") { seen += it }
        assertEquals("m1", id)
        assertEquals(listOf(0f, 0.5f, 1f), seen)
        val live = during.single().single()
        assertEquals("local-1", live.tag); assertEquals("image", live.kind); assertEquals(0.5f, live.fraction, 0.001f)
        assertTrue(UploadTracker.uploads.value.isEmpty())
    }

    @Test fun anUploaderThatCannotReportProgressStillCompletesTheBar() = runBlocking {
        val seen = mutableListOf<Float>()
        MediaUploader(api, FileUploader { _, _, _ -> }).upload(photo().path, "image", "post") { seen += it }
        assertEquals(listOf(0f, 1f), seen) // the whole file arrived at once
    }

    @Test fun aFailedUploadLeavesNothingShownAsUploading() = runBlocking {
        val files = FileUploader { _, _, _ -> throw IOException("connection lost") }
        try { MediaUploader(api, files).upload(photo().path, "image", "post"); throw AssertionError("expected an IOException") }
        catch (e: IOException) { /* expected */ }
        assertTrue(UploadTracker.uploads.value.isEmpty())
    }

    @Test fun tooBigAFileIsRefusedBeforeAnyUploadIsShown() = runBlocking {
        val big = File.createTempFile("voice", ".m4a").apply { deleteOnExit(); RandomAccessFile0(this, 5L * 1024 * 1024 + 1) }
        try { MediaUploader(api, FileUploader { _, _, _ -> }).upload(big.path, "audio", "message", durationMs = 1000); throw AssertionError("expected an IOException") }
        catch (e: IOException) { assertTrue(e.message!!.contains("too large")) }
        assertTrue(UploadTracker.uploads.value.isEmpty())
    }

    @Suppress("FunctionName")
    private fun RandomAccessFile0(f: File, length: Long) = java.io.RandomAccessFile(f, "rw").use { it.setLength(length) }
}
