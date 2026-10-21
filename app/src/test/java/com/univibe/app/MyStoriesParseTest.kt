package com.univibe.app

import com.univibe.app.data.model.StoryKind
import com.univibe.app.data.repository.parseMyStories
import com.univibe.app.data.repository.parseStoryViewers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MyStoriesParseTest {
    @Test
    fun parsesCountsReactionsAndAudience() {
        val body = """{"serverTime":"2026-10-22T12:00:00.000Z","items":[
          {"id":"1","kind":"text","mediaUrl":null,"text":"hi","caption":null,"background":"rose","audience":"followers",
           "createdAt":"2026-10-22T10:00:00.000Z","expiresAt":"2026-10-23T10:00:00.000Z","viewedByMe":true,"myReaction":null,
           "viewCount":7,"reactions":{"heart":3,"fire":1}}]}"""
        val story = parseMyStories(body).single()
        assertEquals(StoryKind.Text, story.item.kind)
        assertEquals("followers", story.audience)
        assertEquals(7, story.viewCount)
        assertEquals(mapOf("heart" to 3, "fire" to 1), story.reactions)
    }

    @Test
    fun parsesViewersPage() {
        val body = """{"total":2,"nextCursor":null,"items":[
          {"viewer":{"id":"u1","handle":"amy","fullName":"Amy Otieno","avatarUrl":null,"studentVerified":true},
           "viewedAt":"2026-10-22T11:00:00.000Z","reaction":"heart"},
          {"viewer":{"id":"u2","handle":"bo","fullName":"Bo Kamau","avatarUrl":"https://x/a.jpg"},
           "viewedAt":"2026-10-22T10:30:00.000Z","reaction":null}]}"""
        val page = parseStoryViewers(body)
        assertEquals(2, page.total)
        assertNull(page.nextCursor)
        assertEquals(listOf("Amy Otieno", "Bo Kamau"), page.viewers.map { it.name })
        assertEquals("heart", page.viewers[0].reaction)
        assertNull(page.viewers[1].reaction)
    }
}
