package com.univibe.app.features.chats

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.rememberPhotoModel
import com.univibe.app.ui.theme.Spacing

/**
 * Shown after photos are chosen and before anything is sent: look them over, drop any, add more (up to the limit),
 * write a caption, then send. Nothing leaves the phone until Send. Text uses theme styles and nothing around it has a
 * fixed height, so it follows the person's font and text size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSendSheet(
    photos: List<String>,
    canAddMore: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: (caption: String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var caption by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md).padding(bottom = Spacing.md).imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(if (photos.size == 1) "Send 1 photo" else "Send ${photos.size} photos", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(photos, key = { it }) { path ->
                    Box(Modifier.size(112.dp).clip(RoundedCornerShape(12.dp))) {
                        AsyncImage(model = rememberPhotoModel(path), contentDescription = "Photo to send", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        IconButton(onClick = { onRemove(path) }, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)) {
                            Box(Modifier.size(24.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(VibeIcons.Close, contentDescription = "Remove photo", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
                if (canAddMore) item {
                    Box(
                        Modifier.size(112.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh)
                            .clickable(role = Role.Button, onClickLabel = "Add another photo", onClick = onAdd),
                        contentAlignment = Alignment.Center,
                    ) { Icon(VibeIcons.Add, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(28.dp)) }
                }
            }
            OutlinedTextField(
                value = caption, onValueChange = { if (it.length <= MAX_MESSAGE) caption = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("Add a caption") }, maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = { onSend(caption) }) { Text("Send") }
            }
        }
    }
}
