package com.univibe.app.ui.components

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
