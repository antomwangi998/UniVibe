package com.univibe.app.features.post

/**
 * One-shot hand-off so "Post in this community" can open the composer with that audience already chosen.
 * The composer reads and clears it once; a stale value can't leak into a later post.
 */
object PostComposeHandoff {
    @Volatile var communityId: String? = null

    fun take(): String? = communityId.also { communityId = null }
}
