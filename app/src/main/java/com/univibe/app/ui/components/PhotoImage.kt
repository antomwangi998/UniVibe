package com.univibe.app.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.io.File

/** Shows a photo stored in app storage (see PhotoStore). Decoded off the main thread by Coil. */
@Composable
fun PhotoImage(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    AsyncImage(
        model = File(path),
        contentDescription = contentDescription,
        modifier = modifier.fillMaxSize(),
        contentScale = contentScale,
    )
}
