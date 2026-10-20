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
