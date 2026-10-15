package com.univibe.app.features.chats

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Two steps: choose people, then name the group and optionally add a photo. */
@Composable
fun NewGroupScreen(onBack: () -> Unit, onCreated: (chatId: String) -> Unit) {
    val viewModel = keyedViewModel("new-group") { NewGroupViewModel() }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    LaunchedEffect(s.createdChatId) { s.createdChatId?.let(onCreated) }
    BackHandler(enabled = s.step == 2 && !s.creating) { viewModel.backStep() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            viewModel.setImporting(true)
            viewModel.setPhoto(PhotoStore.import(context, uri, "group"))
            viewModel.setImporting(false)
        }
    }

    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().imePadding()) {
        if (s.step == 1) {
            VibeTopBar(
                "New group", compactTitle = true,
                navigationIcon = { VibeIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) },
                actions = { VibeButton("Next", viewModel::next, enabled = s.canNext, compact = true) },
            )
            if (s.selectedStudents.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    s.selectedStudents.forEach { st ->
                        Surface(
                            shape = CircleShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
                            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Remove ${st.name}") { viewModel.toggle(st.id) },
                        ) {
                            Row(Modifier.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                StudentAvatar(st.name, size = 28.dp, accent = st.accent)
                                Text(st.name.substringBefore(' '), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 6.dp))
                                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
            VibeTextField(s.query, viewModel::setQuery, Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs), placeholder = "Search students")
            Text(
                if (s.selected.isEmpty()) "Choose at least one person" else "${s.selected.size} selected",
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            )
            when {
                s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 4)
                s.visible.isEmpty() -> EmptyState(Icons.Outlined.Groups, "No students found", "Try a different name.")
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(s.visible, key = { it.id }) { st ->
                        val picked = st.id in s.selected
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Checkbox) { viewModel.toggle(st.id) }.padding(horizontal = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StudentAvatar(st.name, size = 44.dp, accent = st.accent, online = st.online)
                            Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                                Text(st.name, style = MaterialTheme.typography.titleSmall)
                                Text("${st.courseLine}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            Checkbox(checked = picked, onCheckedChange = null)
                        }
                    }
                }
            }
        } else {
            VibeTopBar(
                "Group details", compactTitle = true,
                navigationIcon = { VibeIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", viewModel::backStep) },
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Box(
                    Modifier.padding(top = Spacing.md).clickable(enabled = !s.importing, role = Role.Button, onClickLabel = if (s.photoPath == null) "Add group photo" else "Change group photo") {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                ) {
                    GroupAvatar(s.photoPath, 104.dp)
                    Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary, modifier = Modifier.align(Alignment.BottomEnd).size(36.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    }
                }
                if (s.photoPath != null) TextButton(onClick = viewModel::removePhoto) { Text("Remove photo") }
                VibeTextField(
                    s.name, viewModel::setName, label = "Group name", isError = s.name.isNotEmpty() && !s.nameOk,
                    supportingText = "${s.name.length}/$MAX_GROUP_NAME",
                )
                VibeTextField(
                    s.description, viewModel::setDescription, label = "Description (optional)", singleLine = false, minLines = 3,
                    isError = !s.descriptionOk, supportingText = "${s.description.length}/$MAX_GROUP_DESCRIPTION",
                )
                val names = s.selectedStudents.map { it.name.substringBefore(' ') }
                Text(
                    "Members: " + when {
                        names.size <= 3 -> names.joinToString(", ")
                        else -> names.take(2).joinToString(", ") + " and ${names.size - 2} others"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Text(
                    "You'll be the owner. You can add or remove people, make admins and change the rules later.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
            VibeButton(
                "Create group", viewModel::create, Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.md),
                enabled = s.canCreate, loading = s.creating,
            )
        }
    }
}
