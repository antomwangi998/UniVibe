package com.univibe.app.features.settings

import com.univibe.app.ui.components.VibeIcons
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.ProfileDiscoveryCard
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Where you control your Discover card: photo, a live preview, and whether you appear at all.
 * The visibility switch is the same setting as in Privacy & safety.
 */
@Composable
fun DiscoverProfileScreen(
    onBack: () -> Unit,
    viewModel: DiscoverProfileViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.notice) { state.notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.consumeNotice() } }

    // System photo picker: no storage permission. The copy is re-encoded without location data.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            viewModel.setImporting(true)
            viewModel.setPhoto(PhotoStore.import(context, uri, "discover"))
            viewModel.setImporting(false)
        }
    }

    val profile = state.profile
    val hasPhoto = profile?.discoverPhotoPath != null

    SettingsPage("Discover profile", onBack) {
        if (profile != null) {
            item {
                val preview = profile.student.copy(photoPath = profile.discoverPhotoPath, shared = emptyList(), sameCourse = false, online = false)
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(
                        Modifier.width(260.dp).aspectRatio(4f / 5f).alpha(if (settings.discoverable) 1f else 0.45f)
                            .semantics { contentDescription = "Preview of your Discover card" },
                    ) { ProfileDiscoveryCard(student = preview, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 5f)) }
                    Text(
                        if (settings.discoverable) "This is how other students see your card." else "You're hidden from Discover. Nobody sees this card.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
            }
        }
        item {
            SettingsGroup {
                SettingsSectionTitle("Visibility")
                SettingsSwitchRow(
                    "Show me in Discover", settings.discoverable,
                    { v -> settingsViewModel.update { it.copy(discoverable = v) } },
                    subtitle = if (settings.discoverable) "Other students at your university can see this card." else "Turn on to appear in other students' Discover.",
                    icon = VibeIcons.Visibility,
                )
            }
        }
        item {
            SettingsGroup {
                SettingsSectionTitle("Card photo")
                SettingsNavRow(
                    VibeIcons.Photo,
                    if (hasPhoto) "Change photo" else "Add a photo",
                    onClick = { if (!state.importing) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    subtitle = if (state.importing) "Adding…" else null,
                    showChevron = false,
                )
                if (hasPhoto) {
                    RowDivider()
                    SettingsNavRow(VibeIcons.Delete, "Remove photo", onClick = { viewModel.askRemove(true) }, destructive = true, showChevron = false)
                }
            }
        }
        item {
            SettingsNote(
                "While you're in Discover, other students at your university can see this card: your photo, first name, age, " +
                    "course, year and interests. They never see your email or location. Your photo is saved without location data " +
                    "and only used for this card. You can hide it or remove the photo at any time.",
                Modifier.padding(horizontal = Spacing.md),
            )
        }
    }

    if (state.confirmRemove) {
        ConfirmDialog(
            title = "Remove your Discover photo?",
            message = "Your card goes back to a colour with your initials.",
            confirmLabel = "Remove",
            dismissLabel = "Keep photo",
            destructive = true,
            onConfirm = viewModel::removePhoto,
            onDismiss = { viewModel.askRemove(false) },
        )
    }
}
