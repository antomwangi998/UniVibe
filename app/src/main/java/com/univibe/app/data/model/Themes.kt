package com.univibe.app.data.model

data class ThemeCategory(val slug: String, val name: String)

/**
 * One catalogue image (a wallpaper or theme picture). Everyone may see the preview ([thumbUrl]). [fullUrl] is null while the
 * image is Premium and this person is not entitled: it can then be previewed but not saved or applied (the server decides).
 */
data class ThemeAsset(
    val id: String,
    val title: String,
    val description: String,
    val categorySlug: String,
    val categoryName: String,
    val creatorHandle: String?,
    val sourceAttribution: String,
    val premium: Boolean,
    val locked: Boolean,
    val destinations: List<String>,
    val width: Int,
    val height: Int,
    val favourited: Boolean,
    val thumbUrl: String?,
    val fullUrl: String?,
) {
    val ratio: Float get() = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.5f, 1.6f) else 1f
    val canBeChatBackground: Boolean get() = "chat_background" in destinations
}

data class ThemePage(val items: List<ThemeAsset>, val nextCursor: String?)

/** The chat background the server keeps for me (all chats, or one chat), already resolved to a fresh link. */
data class ServerChatBackground(val assetId: String, val url: String, val dim: Int)

/** The darkest and lightest a chat photo may be dimmed. A floor keeps message text readable on any picture. */
const val CHAT_DIM_MIN = 20
const val CHAT_DIM_MAX = 80
