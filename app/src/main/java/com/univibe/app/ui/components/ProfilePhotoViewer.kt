package com.univibe.app.ui.components

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.univibe.app.ui.theme.Spacing

/**
 * View of a student's profile picture, shown as a framed circle on a dimmed background (tap anywhere to close).
 *
 * The dialog's window is marked FLAG_SECURE, so Android blocks screenshots and screen recording of it (they come out
 * black) and hides it from the recent-apps preview. The flag is also set on the host activity's window while the viewer
 * is open, because some phones only honour it there. Both are cleared when the viewer closes, so the rest of the app
 * stays screenshot-friendly.
 *
 * Be honest about the limit: this stops the system screenshot and screen recorder, not a second phone pointed at the
 * screen, and a rooted or modified device can bypass it.
 */
@Composable
fun SecureProfilePhotoViewer(name: String, imageUrl: String?, accent: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            // The dialog's window: the view may be the dialog layout itself or sit one level below it.
            var node: android.view.View? = view
            var dialogWindow: android.view.Window? = null
            while (node != null && dialogWindow == null) {
                dialogWindow = (node as? DialogWindowProvider)?.window
                node = node.parent as? android.view.View
            }
            val activityWindow = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.window
            val windows = listOfNotNull(dialogWindow, activityWindow)
            windows.forEach { it.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            onDispose { windows.forEach { it.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f))
                .clickable(role = Role.Button, onClickLabel = "Close photo", onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val frame = Modifier.fillMaxWidth(0.8f).widthIn(max = 340.dp).aspectRatio(1f)
                .border(4.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(6.dp).clip(CircleShape)
            if (imageUrl != null) {
                PhotoImage(imageUrl, contentDescription = "$name profile photo", modifier = frame, contentScale = ContentScale.Crop)
            } else {
                Box(frame.background(accentSolid(accent)), contentAlignment = Alignment.Center) {
                    Text(
                        name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() },
                        color = Color.White, fontSize = 88.sp, style = MaterialTheme.typography.displayMedium,
                    )
                }
            }
            Text(
                name, color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(Spacing.xl),
            )
        }
    }
}
