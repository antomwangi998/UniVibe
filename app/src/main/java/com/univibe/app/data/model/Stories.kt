package com.univibe.app.data.model

/**
 * One person with at least one active Story I may see, as `GET /api/v1/stories` lists them. This is only what the row
 * needs for the ring: no media and no links. The server already applied audience, blocks and expiry.
 */
data class StoryTrayEntry(
    val authorId: String,
    val authorName: String,
    val authorHandle: String,
    val avatarUrl: String?,
    val count: Int,
    val latestAtMs: Long,
    /** At least one of their Stories I have not opened yet: teal ring. False: every one seen, so a muted ring. */
    val hasUnviewed: Boolean,
    val isMine: Boolean,
)

enum class StoryKind { Photo, Video, Text }

/** One Story as the viewer needs it. [mediaUrl] is a short-lived link the server minted for this viewer: never store it. */
data class StoryItem(
    val id: String,
    val kind: StoryKind,
    val mediaUrl: String?,
    val durationMs: Long?,
    val text: String?,
    val caption: String?,
    val background: String?,
    val createdAtMs: Long,
    val viewedByMe: Boolean,
    val myReaction: String?,
)

/** One of my own active Stories, with what only its author sees: how many people watched and how they reacted. */
data class MyStory(
    val item: StoryItem,
    val audience: String,
    val expiresAtMs: Long,
    val viewCount: Int,
    val reactions: Map<String, Int>,
)

data class StoryViewer(val id: String, val name: String, val handle: String, val avatarUrl: String?, val viewedAtMs: Long, val reaction: String?)

data class StoryViewersPage(val total: Int, val viewers: List<StoryViewer>, val nextCursor: String?)
