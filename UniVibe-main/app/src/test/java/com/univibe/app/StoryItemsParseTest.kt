package com.univibe.app

import com.univibe.app.data.model.StoryKind
import com.univibe.app.data.repository.parseStoryItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoryItemsParseTest {
    @Test
    fun parsesPhotoAndTextStoriesAndSkipsUnknownKinds() {
        val body = """{"author":{"id":"a"},"items":[
          {"id":"1","kind":"photo","mediaUrl":"https://x/y.jpg","durationMs":null,"text":null,"caption":"hi","background":null,
           "createdAt":"2026-10-22T10:00:00.000Z","viewedByMe":false,"myReaction":null},
          {"id":"2","kind":"text","mediaUrl":null,"text":"hello","caption":null,"background":"rose",
           "createdAt":"2026-10-22T11:00:00.000Z","viewedByMe":true,"myReaction":"heart"},
          {"id":"3","kind":"hologram","createdAt":"2026-10-22T12:00:00.000Z"}]}"""
        val items = parseStoryItems(body)
        assertEquals(listOf("1", "2"), items.map { it.id })
        assertEquals(StoryKind.Photo, items[0].kind)
        assertEquals("hi", items[0].caption)
        assertNull(items[0].durationMs)
        assertEquals(StoryKind.Text, items[1].kind)
        assertEquals("heart", items[1].myReaction)
    }
}
