package com.univibe.app.ui.components

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File

/**
 * Shows a photo: either a file in app storage (see PhotoStore) or a link from the server. Server links expire and
 * change every hour, so the cache key ignores the signature and the same photo is not downloaded again.
 * Decoded off the main thread by Coil.
 */
@Composable
fun PhotoImage(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val model = rememberPhotoModel(path)
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier.fillMaxSize(),
        contentScale = contentScale,
    )
}

/** The Coil model for a photo path: a cache-keyed request for server links, a File for local storage. */
@Composable
fun rememberPhotoModel(path: String): Any {
    val context = LocalContext.current
    return remember(path) {
        if (path.startsWith("https://")) {
            val key = path.substringBefore('?')
            ImageRequest.Builder(context).data(path).memoryCacheKey(key).diskCacheKey(key).build()
        } else {
            File(path)
        }
    }
}

/**
 * Warms the disk cache for photos that are about to scroll into view, so they appear without waiting for the network.
 * Server links only. Decodes at a tiny size and keeps nothing in memory, so it costs almost no memory or CPU.
 */
fun prefetchPhotos(context: Context, paths: List<String>) {
    val loader = context.imageLoader
    for (path in paths) {
        if (!path.startsWith("https://")) continue
        val key = path.substringBefore('?')
        loader.enqueue(
            ImageRequest.Builder(context).data(path).memoryCacheKey(key).diskCacheKey(key)
                .size(1).memoryCachePolicy(CachePolicy.DISABLED).build(),
        )
    }
}
