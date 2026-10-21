package com.univibe.app.features.chats

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A PDF opened with the phone's own renderer. Pages are drawn one at a time (the renderer allows only one open page),
 * and closing waits for a page in progress, so leaving the viewer never crashes it.
 */
private class PdfDoc(file: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (e: Throwable) { descriptor.close(); throw e }
    private val lock = Mutex()
    @Volatile private var closeRequested = false

    val pageCount: Int = renderer.pageCount

    /** Width / height of every page, so the list can reserve the right space before a page is drawn. */
    val ratios: List<Float> = List(pageCount) { i ->
        val page = renderer.openPage(i)
        try { if (page.height > 0) page.width.toFloat() / page.height else 0.7f } finally { page.close() }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap = lock.withLock {
        if (closeRequested) throw CancellationException("closed")
        withContext(Dispatchers.IO) {
            val page = renderer.openPage(index)
            try {
                val height = (widthPx / ratios[index]).toInt().coerceIn(1, 8000)
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
                if (closeRequested) closeNow()
            }
        }
    }

    private fun closeNow() { runCatching { renderer.close() }; runCatching { descriptor.close() } }

    override fun close() {
        closeRequested = true
        if (lock.tryLock()) try { closeNow() } finally { lock.unlock() } // otherwise the page being drawn closes it when done
    }
}

private sealed interface PdfState {
    data object Loading : PdfState
    data object Failed : PdfState
    class Ready(val doc: PdfDoc) : PdfState
}

/**
 * Full-screen reader for a PDF. A file the phone cannot render (locked with a password, damaged) is handed to
 * [onOpenElsewhere] instead, so the person is never left looking at nothing.
 */
@Composable
fun PdfViewerDialog(file: File, title: String, onClose: () -> Unit, onOpenElsewhere: () -> Unit) {
    val context = LocalContext.current
    val state by produceState<PdfState>(PdfState.Loading, file) {
        val doc = withContext(Dispatchers.IO) { runCatching { PdfDoc(file) }.getOrNull() }
        value = if (doc == null) PdfState.Failed else PdfState.Ready(doc)
        awaitDispose { doc?.close() }
    }
    LaunchedEffect(state) {
        if (state is PdfState.Failed) {
            Toast.makeText(context, "Couldn't show this PDF here. Opening it in another app.", Toast.LENGTH_SHORT).show()
            onOpenElsewhere()
        }
    }
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = colors.surfaceContainerLow) {
            Column(Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                val ready = state as? PdfState.Ready
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(VibeIcons.Close, contentDescription = "Close") }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (ready != null) Text("Page ${listState.firstVisibleItemIndex + 1} of ${ready.doc.pageCount}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    TextButton(onClick = onOpenElsewhere) { Text("Open in…") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (ready == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else BoxWithConstraints(Modifier.fillMaxSize()) {
                        val widthPx = with(LocalDensity.current) { (maxWidth - 16.dp).toPx() }.toInt().coerceIn(300, 1600)
                        LazyColumn(state = listState, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((0 until ready.doc.pageCount).toList(), key = { it }) { index -> PdfPage(ready.doc, index, widthPx) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDoc, index: Int, widthPx: Int) {
    var bitmap by remember(doc, index, widthPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(doc, index, widthPx) {
        bitmap = try { doc.render(index, widthPx) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(doc.ratios[index]).background(Color.White), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize()) }
            ?: CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.Gray)
    }
}
