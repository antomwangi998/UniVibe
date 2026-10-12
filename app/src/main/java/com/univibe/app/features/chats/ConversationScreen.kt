@file:OptIn(ExperimentalFoundationApi::class)

package com.univibe.app.features.chats

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.formatClock
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.Message
import com.univibe.app.data.model.MessageStatus
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.rememberPhotoPicker
import com.univibe.app.ui.components.rememberReducedMotion
import com.univibe.app.ui.theme.Spacing

@Composable
fun ConversationScreen(chatId: String, onBack: () -> Unit) {
    val viewModel = keyedViewModel("chat-$chatId") { ConversationViewModel(chatId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val pickPhoto = rememberPhotoPicker("chat") { viewModel.sendImage(it) }
    val chat = s.chat

    Column(Modifier.fillMaxSize().imePadding()) {
        VibeTopBar(
            title = chat?.title ?: "Chat", compactTitle = true,
            navigationIcon = { VibeIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) },
            titleContent = if (chat == null) null else {
                {
                    if (chat.isGroup) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                        }
                    } else {
                        StudentAvatar(chat.title, size = 40.dp, accent = chat.id.hashCode(), online = chat.online)
                    }
                    Column(Modifier.padding(start = Spacing.sm + 4.dp)) {
                        Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            when { s.typing -> "typing…"; chat.isGroup -> "Group chat"; chat.online -> "Online"; else -> "Offline" },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (s.typing || chat.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )

        when {
            s.loading -> Box(Modifier.weight(1f))
            s.notFound -> Box(Modifier.weight(1f)) { ErrorState("This conversation isn't available.", onBack, title = "Not found") }
            else -> {
                val reversed = s.messages.asReversed()
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (s.typing) item(key = "typing") { TypingBubble() }
                    items(reversed, key = { it.id }) { m ->
                        val index = reversed.indexOf(m)
                        val older = reversed.getOrNull(index + 1)
                        MessageBubble(
                            m, showSender = chat?.isGroup == true && !m.isMine && older?.senderId != m.senderId,
                            showReceipts = s.showReceipts, onLongPress = { viewModel.openMenu(m) },
                        )
                    }
                    if (s.messages.isEmpty()) item(key = "empty") {
                        Text(
                            "Say hello to ${chat?.title ?: "your friend"}.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                        )
                    }
                }
            }
        }

        if (!s.notFound) {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.sm), verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = pickPhoto, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Send a photo", tint = MaterialTheme.colorScheme.primary)
                    }
                    VibeTextField(
                        value = s.input, onValueChange = viewModel::onInput, modifier = Modifier.weight(1f),
                        placeholder = "Message", singleLine = false,
                        isError = s.input.length > MAX_MESSAGE,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Surface(
                        shape = CircleShape,
                        color = if (s.canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (s.canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        IconButton(onClick = viewModel::send, enabled = s.canSend, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send message")
                        }
                    }
                }
            }
        }
    }

    s.menuMessage?.let { m ->
        val clipboard = LocalClipboardManager.current
        VibeBottomSheet(onDismiss = { viewModel.openMenu(null) }) {
            if (m.text.isNotBlank()) {
                SheetAction(Icons.Outlined.ContentCopy, "Copy text", false) {
                    clipboard.setText(AnnotatedString(m.text)); viewModel.openMenu(null)
                }
            }
            if (m.isMine) SheetAction(Icons.Outlined.DeleteOutline, "Delete message", true, viewModel::deleteMessage)
            else if (m.text.isBlank()) Text("No actions for this message.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MessageBubble(m: Message, showSender: Boolean, showReceipts: Boolean, onLongPress: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val mine = m.isMine
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (showSender) Text(m.senderName, style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
        Surface(
            shape = if (mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
            color = if (mine) colors.primary else colors.surfaceContainerHigh,
            contentColor = if (mine) colors.onPrimary else colors.onSurface,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                m.imagePath?.let {
                    Box(Modifier.size(width = 220.dp, height = 160.dp).clip(RoundedCornerShape(12.dp))) { PhotoImage(it, "Photo in chat") }
                    Spacer(Modifier.height(4.dp))
                }
                if (m.text.isNotBlank()) Text(m.text, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatClock(m.timeMillis), style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.8f))
                    if (mine) {
                        Spacer(Modifier.width(4.dp))
                        val seen = showReceipts && m.status == MessageStatus.Seen
                        Icon(
                            imageVector = when { m.status == MessageStatus.Sending -> Icons.Outlined.Schedule; seen -> Icons.Outlined.DoneAll; else -> Icons.Outlined.Check },
                            contentDescription = when { m.status == MessageStatus.Sending -> "Sending"; seen -> "Seen"; else -> "Sent" },
                            modifier = Modifier.size(14.dp).alpha(if (seen) 1f else 0.8f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "typing")
    Surface(shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { i ->
                val alpha = if (reduced) 0.6f else {
                    val a by transition.animateFloat(
                        initialValue = 0.25f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "dot$i",
                    )
                    a
                }
                Box(Modifier.size(8.dp).alpha(alpha).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            }
        }
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, destructive: Boolean, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).combinedClickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(Spacing.md))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
