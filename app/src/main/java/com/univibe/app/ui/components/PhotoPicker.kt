package com.univibe.app.ui.components

import android.content.ActivityNotFoundException
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.univibe.app.data.media.FileImport
import com.univibe.app.data.media.FileStore
import com.univibe.app.data.media.PickedFile
import java.io.File
import java.util.UUID
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

/**
 * Opens the camera app for one photo and reports it like a picked photo (copied into app storage). Needs no camera
 * permission: the camera app does the shooting and hands the picture back. The temporary file is always removed.
 */
@Composable
fun rememberCameraPicker(prefix: String, onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPicked)
    val authority = "${context.packageName}.files"
    var shot by rememberSaveable { mutableStateOf<String?>(null) } // survives the app being recreated while the camera is open
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = shot?.let { File(it) }
        shot = null
        if (file != null) {
            if (!ok) file.delete()
            else scope.launch {
                val path = PhotoStore.import(context, FileProvider.getUriForFile(context, authority, file), prefix)
                file.delete()
                if (path != null) latest(path) else Toast.makeText(context, "Couldn't use that photo.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    return remember(launcher) {
        {
            val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "${UUID.randomUUID()}.jpg").apply { createNewFile() }
            shot = file.absolutePath
            try { launcher.launch(FileProvider.getUriForFile(context, authority, file)) }
            catch (e: ActivityNotFoundException) { file.delete(); shot = null; Toast.makeText(context, "No camera app found.", Toast.LENGTH_SHORT).show() }
        }
    }
}

/** Opens the system file chooser for one document, copies it into app storage and checks it (type, size) before reporting it. */
@Composable
fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            when (val r = FileStore.import(context, uri)) {
                is FileImport.Ok -> latest(r.file)
                is FileImport.Refused -> Toast.makeText(context, r.reason, Toast.LENGTH_LONG).show()
            }
        }
    }
    // Every document type is offered; anything not on the allowed list is refused with a reason, so nothing is silently hidden.
    return remember(launcher) { { launcher.launch(arrayOf("application/*", "text/*")) } }
}
