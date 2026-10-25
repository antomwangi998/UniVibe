package com.univibe.app.features.communities

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.Channel
import com.univibe.app.data.model.ChannelDirectory
import com.univibe.app.data.model.ChannelGroup
import com.univibe.app.data.model.ChannelType
import com.univibe.app.data.model.groupOrderAfterMoving
import com.univibe.app.data.model.orderAfterMovingChannel
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.repository.ChannelEdit
import com.univibe.app.data.repository.ChannelRepository
import com.univibe.app.features.chats.SheetRow
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class ManageUiState(
    val loading: Boolean = true,
    val directory: ChannelDirectory? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
)

/** Moderator tools for a community's channels. Every change is made on the server, then the list is re-read. */
class ManageChannelsViewModel(
    private val communityId: String,
    private val repository: ChannelRepository = AppContainer.channelRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ManageUiState())
    val state: StateFlow<ManageUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.directory == null, error = null) }
            try { _state.update { it.copy(loading = false, directory = repository.directory(communityId)) } }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = if (it.directory == null) text(e, "We couldn't load the channels.") else null) } }
        }
    }

    private fun text(e: Exception, fallback: String) = when {
        e is IOException -> "No connection. Try again when you're back online."
        e is ApiException && !e.message.isNullOrBlank() && e.message!!.length < 120 -> e.message!!
        else -> fallback
    }

    private fun change(done: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
                _state.update { it.copy(busy = false, notice = done) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, notice = text(e, "That didn't work. Try again.")) }
            }
            load()
        }
    }

    fun edit(channelId: String, edit: ChannelEdit) = change("Saved") { repository.editChannel(communityId, channelId, edit) }
    fun deleteChannel(channelId: String) = change("Channel deleted") { repository.deleteChannel(communityId, channelId) }
    fun createGroup(name: String) = change("Group added") { repository.createGroup(communityId, name) }
    fun renameGroup(groupId: String, name: String) = change { repository.renameGroup(communityId, groupId, name) }
    fun deleteGroup(groupId: String) = change("Group removed. Its channels were kept.") { repository.deleteGroup(communityId, groupId) }

    fun moveChannel(channelId: String, delta: Int) {
        val d = _state.value.directory ?: return
        val order = d.orderAfterMovingChannel(channelId, delta) ?: return
        change { repository.reorder(communityId, d.groups.map { it.id }, order) }
    }

    fun moveGroup(groupId: String, delta: Int) {
        val d = _state.value.directory ?: return
        val groups = d.groupOrderAfterMoving(groupId, delta) ?: return
        change { repository.reorder(communityId, groups, d.sections.flatMap { (g, list) -> list.map { it.id to g?.id } }) }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }
}

@Composable
fun ManageChannelsScreen(communityId: String, onBack: () -> Unit) {
    val viewModel = keyedViewModel("manage-channels-$communityId") { ManageChannelsViewModel(communityId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var groupDialog by rememberSaveable { mutableStateOf<String?>(null) } // "new" or a group id being renamed
    var deleteGroup by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    val d = s.directory

    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Manage channels", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        when {
            s.loading && d == null -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 3)
            d == null -> ErrorState(s.error ?: "We couldn't load the channels.", onRetry = viewModel::load)
            !d.canManage -> EmptyState(VibeIcons.Lock, "Moderators only", "Only the owner and moderators can manage channels.", actionLabel = "Back", onAction = onBack)
            else -> LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                item {
                    Text("Move channels with the arrows. Groups keep related channels together.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                d.sections.forEach { (group, channels) ->
                    item(key = "group-${group?.id ?: "none"}") {
                        if (group != null) GroupHeader(
                            group, canUp = d.groupOrderAfterMoving(group.id, -1) != null, canDown = d.groupOrderAfterMoving(group.id, 1) != null,
                            onUp = { viewModel.moveGroup(group.id, -1) }, onDown = { viewModel.moveGroup(group.id, 1) },
                            onRename = { groupDialog = group.id }, onDelete = { deleteGroup = group.id },
                        ) else Text("NO GROUP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(channels, key = { it.id }) { c ->
                        ManageRow(
                            c, canUp = d.orderAfterMovingChannel(c.id, -1) != null, canDown = d.orderAfterMovingChannel(c.id, 1) != null,
                            onUp = { viewModel.moveChannel(c.id, -1) }, onDown = { viewModel.moveChannel(c.id, 1) }, onEdit = { editing = c.id },
                        )
                    }
                }
                item { VibeButton("New group", { groupDialog = "new" }, Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary, leadingIcon = VibeIcons.Add, enabled = !s.busy && d.groups.size < 8) }
            }
        }
    }

    val channel = d?.channels?.firstOrNull { it.id == editing }
    if (channel != null && d != null) ChannelEditSheet(
        channel, d.groups, canDelete = d.canDelete, busy = s.busy, onDismiss = { editing = null },
        onSave = { viewModel.edit(channel.id, it); editing = null }, onDelete = { viewModel.deleteChannel(channel.id); editing = null },
    )
    groupDialog?.let { target ->
        val existing = d?.groups?.firstOrNull { it.id == target }
        GroupNameSheet(
            title = if (existing == null) "New group" else "Rename group", initial = existing?.name.orEmpty(), onDismiss = { groupDialog = null },
            onSave = { name -> if (existing == null) viewModel.createGroup(name) else viewModel.renameGroup(existing.id, name); groupDialog = null },
        )
    }
    deleteGroup?.let { id ->
        ConfirmDialog(
            title = "Remove this group?", message = "Its channels are kept and move to \"No group\".", confirmLabel = "Remove", destructive = true,
            onConfirm = { viewModel.deleteGroup(id); deleteGroup = null }, onDismiss = { deleteGroup = null },
        )
    }
}

@Composable
private fun GroupHeader(group: ChannelGroup, canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(group.name.uppercase(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (canUp) VibeIconButton(VibeIcons.ChevronUp, "Move group up", onUp)
        if (canDown) VibeIconButton(VibeIcons.ChevronDown, "Move group down", onDown)
        TextButton(onClick = onRename) { Text("Rename") }
        TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ManageRow(c: Channel, canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit, onEdit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant.copy(alpha = 0.4f)).clickable(onClick = onEdit).padding(start = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = Spacing.sm)) {
            Text("# ${c.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val tags = listOfNotNull(c.type.label, "Locked".takeIf { c.locked }, "Archived".takeIf { c.archived }, "Moderators only".takeIf { c.viewRole == "staff" })
            Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (canUp) VibeIconButton(VibeIcons.ChevronUp, "Move up", onUp)
        if (canDown) VibeIconButton(VibeIcons.ChevronDown, "Move down", onDown)
    }
}

@Composable
private fun GroupNameSheet(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    VibeBottomSheet(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.sm))
        VibeTextField(name, { name = it.take(30) }, label = "Name", placeholder = "Academics")
        VibeButton("Save", { onSave(name) }, Modifier.fillMaxWidth().padding(top = Spacing.md), enabled = name.isNotBlank())
    }
}

@Composable
private fun SwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Everything a moderator can change about one channel. Only what actually changed is sent. */
@Composable
private fun ChannelEditSheet(
    c: Channel, groups: List<ChannelGroup>, canDelete: Boolean, busy: Boolean, onDismiss: () -> Unit, onSave: (ChannelEdit) -> Unit, onDelete: () -> Unit,
) {
    var name by rememberSaveable(c.id) { mutableStateOf(c.name) }
    var description by rememberSaveable(c.id) { mutableStateOf(c.description) }
    var groupId by rememberSaveable(c.id) { mutableStateOf(c.groupId) }
    var replies by rememberSaveable(c.id) { mutableStateOf(c.repliesEnabled) }
    var attachments by rememberSaveable(c.id) { mutableStateOf(c.allowAttachments) }
    var staffView by rememberSaveable(c.id) { mutableStateOf(c.viewRole == "staff") }
    var staffPost by rememberSaveable(c.id) { mutableStateOf(c.postRole == "staff") }
    var locked by rememberSaveable(c.id) { mutableStateOf(c.locked) }
    var archived by rememberSaveable(c.id) { mutableStateOf(c.archived) }
    var confirmDelete by rememberSaveable(c.id) { mutableStateOf(false) }
    val announcement = c.type == ChannelType.Announcement
    val mediaLike = c.type == ChannelType.Media || c.type == ChannelType.Resources

    VibeBottomSheet(onDismiss = onDismiss) {
        Text("# ${c.name}", style = MaterialTheme.typography.titleLarge)
        Text(c.type.label + " channel (the type can't be changed)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.sm))
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            VibeTextField(name, { name = it.take(40) }, label = "Name")
            VibeTextField(description, { description = it.take(200) }, label = "Description", singleLine = false, maxLines = 3)
            Text("Group", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                item { InterestChip("No group", selected = groupId == null, onClick = { groupId = null }) }
                items(groups, key = { it.id }) { g -> InterestChip(g.name, selected = groupId == g.id, onClick = { groupId = g.id }) }
            }
            SwitchRow("Members can reply", null, replies) { replies = it }
            if (!mediaLike) SwitchRow("Allow attachments", null, attachments) { attachments = it }
            if (!announcement) SwitchRow("Only moderators can post", "Members can still read and react.", staffPost) { staffPost = it }
            SwitchRow("Locked", "Read-only for members. Moderators can still write.", locked) { locked = it }
            SwitchRow("Hidden from members", "Only the owner and moderators can see it.", staffView) { staffView = it }
            SwitchRow("Archived", "Hidden from members and read-only for moderators.", archived) { archived = it }
        }
        VibeButton(
            "Save", {
                onSave(
                    ChannelEdit(
                        name = name.takeIf { it.isNotBlank() && it != c.name }, description = description.takeIf { it != c.description },
                        setGroup = groupId != c.groupId, groupId = groupId,
                        repliesEnabled = replies.takeIf { it != c.repliesEnabled }, allowAttachments = attachments.takeIf { it != c.allowAttachments },
                        viewRole = (if (staffView) "staff" else "members").takeIf { it != c.viewRole },
                        postRole = (if (staffPost) "staff" else "members").takeIf { !announcement && it != c.postRole },
                        locked = locked.takeIf { it != c.locked }, archived = archived.takeIf { it != c.archived },
                    ),
                )
            },
            Modifier.fillMaxWidth().padding(top = Spacing.md), enabled = name.isNotBlank() && !busy,
        )
        if (canDelete) SheetRow(VibeIcons.Delete, "Delete channel", true) { confirmDelete = true }
    }
    if (confirmDelete) ConfirmDialog(
        title = "Delete # ${c.name}?", message = "Every message and file in it is deleted for everyone. This can't be undone. Archive it instead to keep the history.",
        confirmLabel = "Delete", destructive = true, onConfirm = { confirmDelete = false; onDelete() }, onDismiss = { confirmDelete = false },
    )
}
