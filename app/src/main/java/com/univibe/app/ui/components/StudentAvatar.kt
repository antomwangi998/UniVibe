package com.univibe.app.ui.components

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Initials avatar; swap the inner Box for an async image once photos are available. */
@Composable
fun StudentAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    accent: Int = 0,
    online: Boolean = false,
    imagePath: String? = null,
) {
    val initials = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    Box(modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(accentSolid(accent)),
            contentAlignment = Alignment.Center,
        ) {
            if (imagePath != null) {
                PhotoImage(imagePath, contentDescription = "$name profile photo")
            } else {
                Text(initials, color = Color.White, fontSize = (size.value * 0.36f).sp, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (online) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).size(size * 0.28f).semantics { contentDescription = "Online" },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
            ) {}
        }
    }
}
