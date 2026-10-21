package com.univibe.app

import com.univibe.app.data.repository.StoryDraft
import org.junit.Assert.assertEquals
import org.junit.Test

class StoryDraftTest {
    @Test
    fun kindFollowsTheAttachedMedia() {
        assertEquals("text", StoryDraft(text = "hi", clientId = "c").kind)
        assertEquals("photo", StoryDraft(photoPath = "/p.jpg", clientId = "c").kind)
        assertEquals("video", StoryDraft(videoPath = "/v.mp4", videoDurationMs = 12_000, clientId = "c").kind)
    }
}
