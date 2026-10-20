package com.univibe.app

import com.univibe.app.features.chats.findLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLinksTest {
    @Test fun findsPlainAndWwwAddresses() {
        val links = findLinks("see https://univibe.app/events and www.example.com now")
        assertEquals(listOf("https://univibe.app/events", "https://www.example.com"), links.map { it.url })
    }

    @Test fun sentencePunctuationIsNotPartOfTheLink() {
        assertEquals("https://a.com/x", findLinks("go to https://a.com/x.").single().url)
        assertEquals("https://a.com/x", findLinks("(https://a.com/x)").single().url)
        assertEquals("https://a.com/x", findLinks("really? https://a.com/x!!").single().url)
    }

    @Test fun balancedBracketsStayInTheLink() {
        assertEquals("https://en.wikipedia.org/wiki/Kotlin_(language)", findLinks("https://en.wikipedia.org/wiki/Kotlin_(language)").single().url)
    }

    @Test fun rangeCoversExactlyTheShownText() {
        val text = "hi https://a.com/x, ok"
        val l = findLinks(text).single()
        assertEquals("https://a.com/x", text.substring(l.range.first, l.range.last + 1))
    }

    @Test fun otherSchemesAndFileNamesAreIgnored() {
        assertTrue(findLinks("javascript:alert(1) file:///etc/passwd readme.md notes.txt").isEmpty())
        assertTrue(findLinks("https:// and www. alone").isEmpty())
    }

    @Test fun mentionInsideAnAddressStaysPartOfTheLink() {
        assertEquals("https://x.com/@jane", findLinks("https://x.com/@jane").single().url)
    }
}
