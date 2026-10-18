package com.univibe.app.features.chats

import com.univibe.app.ui.components.VibeIcons
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.model.GroupInfo
import com.univibe.app.data.model.GroupMember
import com.univibe.app.data.model.GroupRole
import com.univibe.app.features.settings.RowDivider
import com.univibe.app.features.settings.SettingsGroup
import com.univibe.app.features.settings.SettingsNavRow
import com.univibe.app.features.settings.SettingsNote
import com.univibe.app.features.settings.SettingsSectionTitle
import com.univibe.app.features.settings.SettingsSwitchRow
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.PhotoViewerDialog
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.launch

private val ReportReasons = listOf("Spam", "Harassment or bullying", "Hate speech", "Inappropriate content", "Something else")

/** Everything about one group chat: details, members and roles, shared photos, rules, and leaving. */
@Composable
fun GroupInfoScreen(
    chatId: String,
    onBack: () -> Unit,
    onOpenStudent: (String) -> Unit,
    onOpenChat: (chatId: String) -> Unit,
    onLeft: () -> Unit,
) {
    val viewModel = keyedViewModel("group-info-$chatId") { GroupInfoViewModel(chatId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var viewerPath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    LaunchedEffect(s.left) { if (s.left) onLeft() }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { viewModel.setPhoto(PhotoStore.import(context, uri, "group")) }
    }

    val group = s.group
    if (group == null) {
        Column(Modifier.fillMaxSize()) {
            VibeTopBar("Group info", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
            if (s.loading) LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
            else EmptyState(VibeIcons.Groups, "Group not found", "You may have left this group, or it no longer exists.", actionLabel = "Back", onAction = onBack)
        }
        return
    }

    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        VibeTopBar(
            "Group info", compactTitle = true,
            navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) },
            actions = { if (group.canEditInfo) VibeIconButton(VibeIcons.Edit, "Edit group", { viewModel.edit(true) }) },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Box {
                        GroupAvatar(group.photoPath, 104.dp)
                        if (group.canEditInfo) {
                            Surface(
                                shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary,
                                modifier = Modifier.align(Alignment.BottomEnd).size(36.dp)
                                    .clickable(role = Role.Button, onClickLabel = "Change group photo") {
                                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                            ) { Box(contentAlignment = Alignment.Center) { Icon(VibeIcons.Photo, contentDescription = "Change group photo", modifier = Modifier.size(18.dp)) } }
                        }
                    }
                    Text(group.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.sm))
                    Text("Group · ${group.members.size} ${if (group.members.size == 1) "member" else "members"}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    if (group.description.isNotBlank()) {
                        Text(group.description, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.xs))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.md), horizontalArrangement = Arrangement.SpaceEvenly) {
                        if (group.canAddMembers) QuickAction(VibeIcons.PersonAdd, "Add", viewModel::openAdd)
                        QuickAction(
                            if (group.muted) VibeIcons.BellOff else VibeIcons.BellFilled,
                            if (group.muted) "Unmute" else "Mute", viewModel::toggleMute,
                        )
                        QuickAction(VibeIcons.Comment, "Chat", onBack)
                    }
                }
            }
            item {
                SettingsGroup {
                    SettingsSectionTitle("About")
                    Text(
                        "Created by ${group.createdBy}\n${group.createdLabel}",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    )
                    Spacer(Modifier.size(Spacing.xs))
                }
            }
            item {
                SettingsGroup {
                    SettingsSectionTitle("${group.members.size} ${if (group.members.size == 1) "member" else "members"}")
                    if (group.canAddMembers) {
                        SettingsNavRow(VibeIcons.PersonAdd, "Add members", viewModel::openAdd, showChevron = false)
                        RowDivider()
                    }
                    group.members.sortedWith(compareBy<GroupMember>({ -it.role.ordinal }, { !it.isMe }, { it.student.name })).forEachIndexed { i, m ->
                        if (i > 0) RowDivider()
                        MemberRow(m, onClick = { if (!m.isMe) viewModel.memberMenu(m) })
                    }
                }
            }
            item {
                SettingsGroup {
                    SettingsSectionTitle("Shared photos")
                    if (s.photos.isEmpty()) {
                        Text("No photos shared yet.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs))
                    } else {
                        Text("${s.photos.size} ${if (s.photos.size == 1) "photo" else "photos"}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.md))
                        PhotoGrid(s.photos.take(6), onOpen = { viewerPath = it })
                    }
                    Spacer(Modifier.size(Spacing.sm))
                }
            }
            item { RulesSection(group, onOnlyAdminsMessage = { viewModel.setPermissions(onlyAdminsMessage = it) },
                onOnlyAdminsEdit = { viewModel.setPermissions(onlyAdminsEdit = it) }, onOnlyAdminsAdd = { viewModel.setPermissions(onlyAdminsAdd = it) }) }
            item {
                SettingsGroup {
                    SettingsNavRow(VibeIcons.Delete, "Clear chat", { viewModel.confirm(GroupConfirm.ClearChat) }, subtitle = "Removes messages for you only.", destructive = true, showChevron = false)
                    RowDivider()
                    SettingsNavRow(VibeIcons.Report, "Report group", { viewModel.reporting(true) }, destructive = true, showChevron = false)
                    RowDivider()
                    SettingsNavRow(Icons.AutoMirrored.Outlined.ExitToApp, "Leave group", { viewModel.confirm(GroupConfirm.Leave) }, destructive = true, showChevron = false)
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    viewerPath?.let { PhotoViewerDialog(it) { viewerPath = null } }

    if (s.editing) EditGroupSheet(group, onDismiss = { viewModel.edit(false) }, onSave = viewModel::saveInfo, onRemovePhoto = viewModel::removePhoto)

    s.memberMenu?.let { m ->
        MemberSheet(
            member = m, group = group, onDismiss = { viewModel.memberMenu(null) },
            onProfile = { viewModel.memberMenu(null); onOpenStudent(m.student.id) },
            onMessage = { viewModel.message(m, onOpenChat) },
            onMakeAdmin = { viewModel.makeAdmin(m) }, onRemoveAdmin = { viewModel.removeAdmin(m) },
            onRemove = { viewModel.confirm(GroupConfirm.RemoveMember(m)) },
        )
    }

    if (s.adding) {
        VibeBottomSheet(onDismiss = viewModel::closeAdd) {
            Text("Add members", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.md))
            VibeTextField(s.query, viewModel::setQuery, Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), placeholder = "Search students")
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                if (s.addableVisible.isEmpty()) {
                    Text("No students to add.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(Spacing.md))
                }
                s.addableVisible.forEach { st ->
                    val picked = st.id in s.selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Checkbox) { viewModel.toggleSelected(st.id) }.padding(horizontal = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StudentAvatar(st.name, size = 44.dp, accent = st.accent, imagePath = st.avatarUrl)
                        Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                            Text(st.name, style = MaterialTheme.typography.titleSmall)
                            Text("${st.courseLine}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Checkbox(checked = picked, onCheckedChange = null)
                    }
                }
            }
            VibeButton(
                if (s.selected.isEmpty()) "Select students" else "Add ${s.selected.size}", viewModel::confirmAdd,
                Modifier.fillMaxWidth().padding(Spacing.md), enabled = s.selected.isNotEmpty(),
            )
        }
    }

    if (s.reporting) {
        VibeBottomSheet(onDismiss = { viewModel.reporting(false) }) {
            Text("Why are you reporting this group?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm))
            ReportReasons.forEach { r -> SheetRow(VibeIcons.Report, r, false) { viewModel.report(r) } }
        }
    }

    when (val c = s.confirm) {
        is GroupConfirm.RemoveMember -> ConfirmDialog(
            title = "Remove ${c.member.student.name}?", message = "They will no longer see new messages in this group.",
            confirmLabel = "Remove", dismissLabel = "Cancel", destructive = true, onConfirm = viewModel::doConfirmed, onDismiss = { viewModel.confirm(null) },
        )
        GroupConfirm.Leave -> ConfirmDialog(
            title = "Leave this group?",
            message = when {
                group.members.size == 1 -> "You're the last member, so the group will be deleted."
                group.myRole == GroupRole.Owner -> "You're the owner. Ownership will pass to another member. You won't get new messages."
                else -> "You won't get new messages. Someone has to add you to join again."
            },
            confirmLabel = "Leave", dismissLabel = "Stay", destructive = true, onConfirm = viewModel::doConfirmed, onDismiss = { viewModel.confirm(null) },
        )
        GroupConfirm.ClearChat -> ConfirmDialog(
            title = "Clear this chat?", message = "Messages are removed for you only. Other members keep theirs.",
            confirmLabel = "Clear", dismissLabel = "Cancel", destructive = true, onConfirm = viewModel::doConfirmed, onDismiss = { viewModel.confirm(null) },
        )
        null -> Unit
    }
}

/**
 * Uploaded photo first; otherwise a coloured avatar with the group's initials (colour derived from the name, so it is the
 * same everywhere); otherwise the plain group icon when no name is known.
 */
@Composable
fun GroupAvatar(photoPath: String?, size: androidx.compose.ui.unit.Dp, name: String? = null, memberNames: List<String> = emptyList()) {
    val colors = MaterialTheme.colorScheme
    val initials = remember(name) {
        name.orEmpty().split(" ").filter { w -> w.isNotBlank() }.mapNotNull { w -> w.firstOrNull { c -> c.isLetterOrDigit() } }.take(2).joinToString("") { c -> c.uppercase() }
    }
    when {
        photoPath != null -> Box(Modifier.size(size).clip(CircleShape)) { PhotoImage(photoPath, contentDescription = "Group photo", modifier = Modifier.fillMaxSize()) }
        // No uploaded photo: a collage of up to four members' initials, when we know who they are.
        memberNames.size >= 2 -> androidx.compose.foundation.layout.Column(Modifier.size(size).clip(CircleShape)) {
            val tiles = memberNames.take(4)
            val rows = tiles.chunked(2)
            rows.forEach { row ->
                androidx.compose.foundation.layout.Row(Modifier.weight(1f).fillMaxWidth()) {
                    row.forEach { n ->
                        Box(Modifier.weight(1f).fillMaxSize().background(com.univibe.app.ui.components.accentSolid(n.hashCode())), contentAlignment = Alignment.Center) {
                            Text(n.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(), color = Color.White, fontSize = (size.value * if (tiles.size > 2) 0.24f else 0.34f).sp, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (row.size == 1 && tiles.size > 2) Box(Modifier.weight(1f).fillMaxSize().background(colors.surfaceContainerHigh))
                }
            }
        }
        initials.isNotEmpty() -> Box(
            Modifier.size(size).clip(CircleShape).background(com.univibe.app.ui.components.accentSolid(name.orEmpty().hashCode())),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, color = Color.White, fontSize = (size.value * 0.36f).sp, style = MaterialTheme.typography.titleMedium)
        }
        else -> Surface(shape = CircleShape, color = colors.primaryContainer, modifier = Modifier.size(size)) {
            Box(contentAlignment = Alignment.Center) { Icon(VibeIcons.Groups, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(size / 2)) }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.widthIn96().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = colors.surfaceContainerHigh, modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = colors.primary) }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun Modifier.widthIn96(): Modifier = this.then(Modifier.width(88.dp))

@Composable
private fun MemberRow(m: GroupMember, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = !m.isMe, role = Role.Button, onClickLabel = "Member options", onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .semantics(mergeDescendants = true) {
                contentDescription = "${m.student.name}${if (m.isMe) ", you" else ""}${if (m.role != GroupRole.Member) ", ${m.role.name}" else ""}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StudentAvatar(m.student.name, size = 44.dp, accent = m.student.accent, online = m.student.online, imagePath = m.student.avatarUrl)
        Column(Modifier.weight(1f).padding(start = Spacing.md)) {
            Text(if (m.isMe) "${m.student.name} (you)" else m.student.name, style = MaterialTheme.typography.titleSmall)
            Text("${m.student.courseLine}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (m.role != GroupRole.Member) {
            Surface(shape = CircleShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                Text(m.role.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun PhotoGrid(photos: List<String>, onOpen: (String) -> Unit) {
    Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        photos.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { i, path ->
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "View photo") { onOpen(path) }) {
                        PhotoImage(path, contentDescription = "Shared photo", modifier = Modifier.fillMaxSize())
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RulesSection(group: GroupInfo, onOnlyAdminsMessage: (Boolean) -> Unit, onOnlyAdminsEdit: (Boolean) -> Unit, onOnlyAdminsAdd: (Boolean) -> Unit) {
    if (group.iAmAdmin) {
        SettingsGroup {
            SettingsSectionTitle("Group settings")
            SettingsSwitchRow("Only admins can send messages", group.onlyAdminsCanMessage, onOnlyAdminsMessage, subtitle = "Everyone else can read, not post.", icon = Icons.Outlined.AdminPanelSettings)
            RowDivider()
            SettingsSwitchRow("Only admins can edit group info", group.onlyAdminsCanEditInfo, onOnlyAdminsEdit, subtitle = "Name, description and photo.")
            RowDivider()
            SettingsSwitchRow("Only admins can add members", group.onlyAdminsCanAddMembers, onOnlyAdminsAdd)
        }
    } else {
        SettingsGroup {
            SettingsSectionTitle("Group rules")
            val rules = listOfNotNull(
                if (group.onlyAdminsCanMessage) "Only admins can send messages." else "Everyone can send messages.",
                if (group.onlyAdminsCanEditInfo) "Only admins can edit group info." else "Everyone can edit group info.",
                if (group.onlyAdminsCanAddMembers) "Only admins can add members." else "Everyone can add members.",
            )
            SettingsNote(rules.joinToString("\n"), Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs))
        }
    }
}

@Composable
private fun EditGroupSheet(group: GroupInfo, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onRemovePhoto: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(group.name) }
    var description by rememberSaveable { mutableStateOf(group.description) }
    val nameOk = name.trim().isNotEmpty() && name.length <= MAX_GROUP_NAME
    val descOk = description.length <= MAX_GROUP_DESCRIPTION
    VibeBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("Edit group", style = MaterialTheme.typography.titleMedium)
            VibeTextField(
                name, { name = it }, label = "Group name", isError = !nameOk,
                supportingText = if (!nameOk) "1 to $MAX_GROUP_NAME characters" else "${name.length}/$MAX_GROUP_NAME",
            )
            VibeTextField(
                description, { description = it }, label = "Description", singleLine = false, minLines = 3, isError = !descOk,
                supportingText = "${description.length}/$MAX_GROUP_DESCRIPTION",
            )
            if (group.photoPath != null) VibeButton("Remove group photo", onRemovePhoto, Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary)
            VibeButton("Save", { onSave(name, description) }, Modifier.fillMaxWidth(), enabled = nameOk && descOk)
            Spacer(Modifier.size(Spacing.sm))
        }
    }
}

@Composable
private fun MemberSheet(
    member: GroupMember, group: GroupInfo, onDismiss: () -> Unit,
    onProfile: () -> Unit, onMessage: () -> Unit, onMakeAdmin: () -> Unit, onRemoveAdmin: () -> Unit, onRemove: () -> Unit,
) {
    val isOwner = group.myRole == GroupRole.Owner
    val canRemove = group.iAmAdmin && member.role != GroupRole.Owner && (member.role != GroupRole.Admin || isOwner)
    VibeBottomSheet(onDismiss = onDismiss) {
        Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            StudentAvatar(member.student.name, size = 44.dp, accent = member.student.accent, imagePath = member.student.avatarUrl)
            Column(Modifier.padding(start = Spacing.md)) {
                Text(member.student.name, style = MaterialTheme.typography.titleMedium)
                Text(member.student.handle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SheetRow(VibeIcons.Profile, "View profile", false, onProfile)
        SheetRow(VibeIcons.Comment, "Message", false, onMessage)
        if (isOwner && member.role == GroupRole.Member) SheetRow(Icons.Outlined.AdminPanelSettings, "Make group admin", false, onMakeAdmin)
        if (isOwner && member.role == GroupRole.Admin) SheetRow(Icons.Outlined.AdminPanelSettings, "Remove as admin", false, onRemoveAdmin)
        if (canRemove) SheetRow(VibeIcons.PersonRemove, "Remove from group", true, onRemove)
        Spacer(Modifier.size(Spacing.md))
    }
}

@Composable
internal fun SheetRow(icon: ImageVector, label: String, destructive: Boolean, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(Spacing.md))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
