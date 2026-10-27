package com.univibe.app.features.profile

import com.univibe.app.ui.components.VibeIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.features.auth.CampusPicker
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.accentGradient
import com.univibe.app.ui.components.rememberPhotoPicker
import com.univibe.app.ui.theme.Spacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditProfileScreen(onBack: () -> Unit, viewModel: EditProfileViewModel = viewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestBack = { if (s.dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = s.dirty) { confirmDiscard = true }
    LaunchedEffect(s.saved) { if (s.saved) onBack() }

    val pickAvatar = rememberPhotoPicker("avatar") { viewModel.setAvatar(it) }
    val pickCover = rememberPhotoPicker("cover") { viewModel.setCover(it) }

    Column(Modifier.fillMaxSize().imePadding()) {
        VibeTopBar(
            title = "Edit profile", compactTitle = true,
            navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", requestBack) },
            actions = { VibeButton("Save", viewModel::save, compact = true, enabled = s.dirty, loading = s.saving) },
        )
        when {
            s.loading -> Box(Modifier.fillMaxSize())
            s.error != null -> ErrorState(s.error!!, viewModel::load)
            else -> LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item {
                    VibeCard {
                        Text("Photos", style = MaterialTheme.typography.titleMedium)
                        Box(
                            Modifier.padding(top = Spacing.sm).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp))
                                .background(Brush.linearGradient(accentGradient(1))),
                        ) { s.coverPath?.let { PhotoImage(it, "Cover photo preview") } }
                        Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            VibeButton("Change cover", pickCover, style = VibeButtonStyle.Secondary, compact = true, leadingIcon = VibeIcons.Camera)
                            if (s.coverPath != null) TextButton(onClick = viewModel::removeCover) { Text("Remove") }
                        }
                        Row(Modifier.padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            StudentAvatar(s.name.ifBlank { "You" }, size = 72.dp, imagePath = s.avatarPath)
                            VibeButton("Change photo", pickAvatar, style = VibeButtonStyle.Secondary, compact = true, leadingIcon = VibeIcons.Camera)
                            if (s.avatarPath != null) TextButton(onClick = viewModel::removeAvatar) { Text("Remove") }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        VibeTextField(s.name, viewModel::onName, label = "Full name", isError = s.nameError != null, supportingText = s.nameError)
                        VibeTextField(
                            s.bio, viewModel::onBio, label = "Bio", singleLine = false, minLines = 3,
                            isError = s.bioError != null, supportingText = s.bioError ?: "${s.bio.length}/$MAX_PROFILE_BIO",
                        )
                        VibeTextField(s.course, viewModel::onCourse, label = "Course or faculty", isError = s.courseError != null, supportingText = s.courseError)
                    }
                }
                item {
                    VibeCard {
                        Text("Year of study", style = MaterialTheme.typography.titleMedium)
                        FlowRow(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            StudyYears.forEach { y -> InterestChip(y, selected = s.year == y, onClick = { viewModel.onYear(y) }) }
                        }
                    }
                }
                if (s.campuses.isNotEmpty()) item {
                    VibeCard {
                        CampusPicker(s.campuses, s.campusId.ifEmpty { null }, s.campusError, viewModel::onCampus)
                    }
                }
                item {
                    VibeCard {
                        Text("Interests", style = MaterialTheme.typography.titleMedium)
                        FlowRow(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            AllInterests.forEach { i -> InterestChip(i, selected = i in s.interests, onClick = { viewModel.toggleInterest(i) }) }
                        }
                    }
                }
                item {
                    Text(
                        "Photos are resized and stripped of location data on your phone before they are used.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?", message = "Your edits haven't been saved.",
            confirmLabel = "Discard", dismissLabel = "Keep editing", destructive = true,
            onConfirm = { confirmDiscard = false; viewModel.discard(); onBack() },
            onDismiss = { confirmDiscard = false },
        )
    }
}
