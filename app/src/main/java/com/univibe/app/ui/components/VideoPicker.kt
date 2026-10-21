package com.univibe.app.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.univibe.app.data.media.VideoStore
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Opens the system picker for one video (no storage permission needed), copies it into app storage and reports its
 * path. Videos over the size limit are refused while copying.
 */
@Composable
fun rememberVideoPicker(prefix: String, onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = VideoStore.import(context, uri, prefix)
            if (path != null) latest(path) else Toast.makeText(context, "Couldn't use that video. Videos can be up to 50 MB.", Toast.LENGTH_SHORT).show()
        }
    }
    return remember(launcher) { { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) } }
}

/** Asks the camera app to stop after [seconds], so a Story video can never come back longer than the server allows. */
private class CaptureVideoUpTo(private val seconds: Int) : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, seconds)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
}

/**
 * Opens the camera app to record one video of at most [maxSeconds] and reports it like a picked video (copied into app
 * storage). Needs no camera permission: the camera app does the recording. The temporary file is always removed.
 */
@Composable
fun rememberVideoCameraPicker(prefix: String, maxSeconds: Int, onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPicked)
    val authority = "${context.packageName}.files"
    var shot by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(CaptureVideoUpTo(maxSeconds)) { ok ->
        val file = shot?.let { File(it) }
        shot = null
        if (file != null) {
            if (!ok) file.delete()
            else scope.launch {
                val path = VideoStore.import(context, FileProvider.getUriForFile(context, authority, file), prefix)
                file.delete()
                if (path != null) latest(path) else Toast.makeText(context, "Couldn't use that video.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    return remember(launcher) {
        {
            val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "${UUID.randomUUID()}.mp4").apply { createNewFile() }
            shot = file.absolutePath
            try { launcher.launch(FileProvider.getUriForFile(context, authority, file)) }
            catch (e: ActivityNotFoundException) { file.delete(); shot = null; Toast.makeText(context, "No camera app found.", Toast.LENGTH_SHORT).show() }
        }
    }
}
