package com.univibe.app.features.chats

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.univibe.app.data.media.PickedFile
import com.univibe.app.data.model.FileAttachment
import com.univibe.app.ui.theme.Spacing

/** Shown after a file is chosen and before it is sent: check it is the right one, add a caption, then send. Nothing leaves the phone until Send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSendSheet(file: PickedFile, onCancel: () -> Unit, onSend: (caption: String) -> Unit) {
    var caption by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md).padding(bottom = Spacing.md).imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text("Send file", style = MaterialTheme.typography.titleMedium)
            FileCard(FileAttachment(file.name, file.sizeBytes, file.contentType, file.path), sending = false, progress = null, busy = false, onOpen = {}, onLongPress = {})
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
