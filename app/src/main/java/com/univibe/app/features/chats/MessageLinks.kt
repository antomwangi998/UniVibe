package com.univibe.app.features.chats

/** One tappable web address found in a message: where it sits in the text, and the address to open. */
class LinkSpan(val range: IntRange, val url: String)

private val LinkRegex = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")
private const val TRAILING = ".,!?;:'\"]}"

/**
 * Finds web addresses in [text]. Only http(s):// and www. addresses count, so words like "readme.md" are left alone,
 * and no other scheme (javascript:, file:, intent:) can ever be opened from a message. Punctuation that ends a sentence
 * is not part of the address; a closing bracket is kept only when the address opened one (Wikipedia-style links).
 */
fun findLinks(text: String): List<LinkSpan> = LinkRegex.findAll(text).mapNotNull { m ->
    var end = m.range.last + 1
    while (end > m.range.first) {
        val c = text[end - 1]
        val open = text.substring(m.range.first, end).count { it == '(' }
        val close = text.substring(m.range.first, end).count { it == ')' }
        if (c in TRAILING || (c == ')' && close > open)) end-- else break
    }
    val shown = text.substring(m.range.first, end)
    // Nothing after the scheme or "www." (for example a lone "https://") is not a link.
    if (shown.substringAfter("://").removePrefix("www.").removePrefix("WWW.").isBlank()) null
    else LinkSpan(m.range.first until end, if (shown.startsWith("www.", ignoreCase = true)) "https://$shown" else shown)
}.toList()
