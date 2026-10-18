package com.univibe.app.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
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
