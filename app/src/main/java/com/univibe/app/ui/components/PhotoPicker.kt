package com.univibe.app.ui.components

import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.univibe.app.data.media.PhotoStore
import kotlinx.coroutines.launch

/**
 * Opens the system photo picker (no storage permission needed), copies the chosen image into
 * app storage and reports its path. Returns a function that launches the picker.
 */
@Composable
fun rememberPhotoPicker(prefix: String, onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = PhotoStore.import(context, uri, prefix)
            if (path != null) latest(path) else Toast.makeText(context, "Couldn't use that image.", Toast.LENGTH_SHORT).show()
        }
    }
    return remember(launcher) { { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
}

/**
 * Like [rememberPhotoPicker] but lets the person choose up to [max] photos at once (the order they were picked in is kept).
 * Photos that cannot be used are skipped, with one note saying how many.
 */
@Composable
fun rememberMultiPhotoPicker(prefix: String, max: Int, onPicked: (List<String>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPicked)
    // The system picker refuses a limit below 2 or above what the device allows.
    val limit = (if (Build.VERSION.SDK_INT >= 33) minOf(max, MediaStore.getPickImagesMaxLimit()) else max).coerceAtLeast(2)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(limit)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val paths = uris.take(max).mapNotNull { PhotoStore.import(context, it, prefix) }
            val skipped = uris.take(max).size - paths.size
            if (skipped > 0) Toast.makeText(context, if (skipped == 1) "Couldn't use one image." else "Couldn't use $skipped images.", Toast.LENGTH_SHORT).show()
            if (paths.isNotEmpty()) latest(paths)
        }
    }
    return remember(launcher) { { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
}
