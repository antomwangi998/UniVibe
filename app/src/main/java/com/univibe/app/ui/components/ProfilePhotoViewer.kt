package com.univibe.app.ui.components

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.univibe.app.ui.theme.Spacing

/**
 * Full-screen view of a student's profile picture.
 *
 * The dialog's window is marked FLAG_SECURE, so Android blocks screenshots and screen recording of it (they come out
 * black) and hides it from the recent-apps preview. The flag lives on this dialog's window only: the rest of the app
 * stays screenshot-friendly, and it ends when the viewer closes.
 *
 * Be honest about the limit: this stops the system screenshot and screen recorder, not a second phone pointed at the
 * screen, and a rooted or modified device can bypass it.
 */
@Composable
fun SecureProfilePhotoViewer(name: String, imageUrl: String?, accent: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f))
                .clickable(role = Role.Button, onClickLabel = "Close photo", onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl != null) {
                PhotoImage(imageUrl, contentDescription = "$name profile photo", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Box(Modifier.size(240.dp).clip(CircleShape).background(accentSolid(accent)), contentAlignment = Alignment.Center) {
                    Text(
                        name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() },
                        color = Color.White, fontSize = 88.sp, style = MaterialTheme.typography.displayMedium,
                    )
                }
            }
            Text(
                name, color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(Spacing.lg),
            )
        }
    }
}
