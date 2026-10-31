package com.univibe.app.data.model

/**
 * One sticker, as the server describes it. [url] is the full file (animated when [animated]); it is null when the pack is
 * Premium and this person is not entitled (only the still [thumbUrl] is shown), and when the sticker was removed.
 * A sticker in a message carries [available] = false once it has been removed: the bubble then shows a neutral placeholder.
 */
data class StickerRef(
    val id: String,
    val packId: String?,
    val label: String?,
    val url: String?,
    val thumbUrl: String?,
    val width: Int?,
    val height: Int?,
    val animated: Boolean,
    val available: Boolean = true,
) {
    /** True when the picture can be previewed but not sent (a Premium pack without Premium). */
    val locked: Boolean get() = available && url == null
    val ratio: Float get() = if ((width ?: 0) > 0 && (height ?: 0) > 0) width!!.toFloat() / height!! else 1f
}

data class StickerPack(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val premium: Boolean,
    /** Premium pack and the person has no Premium right now: previewable, not installable. */
    val locked: Boolean,
    val stickerCount: Int,
    val creatorHandle: String?,
    val installed: Boolean,
    val favourite: Boolean,
    val coverThumbUrl: String?,
    /** Filled by the pack page and the picker; empty in store lists. */
    val stickers: List<StickerRef> = emptyList(),
)

data class StickerPage(val packs: List<StickerPack>, val nextCursor: String?)

/** Store filters. The ids are the server's; the names are what people read. */
val StickerCategories: List<Pair<String, String>> = listOf(
    "campus_vibes" to "Campus vibes", "funny_reactions" to "Funny reactions", "emotions" to "Emotions",
    "love_friendship" to "Love & friendship", "study_mode" to "Study mode", "tech_gaming" to "Tech & gaming",
    "univibe_originals" to "UniVibe originals", "seasonal" to "Seasonal",
)
