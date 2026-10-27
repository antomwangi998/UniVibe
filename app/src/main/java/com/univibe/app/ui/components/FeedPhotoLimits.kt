package com.univibe.app.ui.components

/** Tallest a feed photo may be (about 5:8). Keep in step with MIN_RATIO in PostMedia.kt, which is what the feed really uses. */
const val FEED_MIN_PHOTO_RATIO = 0.62f

/** Widest a feed photo may be. Keep in step with MAX_RATIO in PostMedia.kt. */
const val FEED_MAX_PHOTO_RATIO = 2.0f

/** True when the feed would cut part of a photo of this width / height (the photo editor warns about it). */
fun isTrimmedInFeed(ratio: Float): Boolean = ratio < FEED_MIN_PHOTO_RATIO - 0.005f || ratio > FEED_MAX_PHOTO_RATIO + 0.005f
