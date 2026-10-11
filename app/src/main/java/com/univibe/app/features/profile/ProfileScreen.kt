package com.univibe.app.features.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.MyProfile
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(onOpenSettings: () -> Unit, viewModel: ProfileViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Profile", actions = { VibeIconButton(Icons.Outlined.Settings, "Settings", onOpenSettings) })
        val profile = state.profile
        when {
            state.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 1)
            state.error != null || profile == null -> ErrorState(state.error ?: "Something went wrong.", viewModel::load)
            else -> LazyColumn(
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item { Header(profile) }
                item {
                    VibeCard {
                        Text("About me", style = MaterialTheme.typography.titleMedium)
                        Text(
                            profile.bio,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xs),
                        )
                    }
                }
                item {
                    VibeCard {
                        Text("Interests", style = MaterialTheme.typography.titleMedium)
                        FlowRow(
                            modifier = Modifier.padding(top = Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) { profile.student.interests.forEach { InterestChip(it) } }
                    }
                }
                item {
                    VibeCard {
                        Text("Privacy", style = MaterialTheme.typography.titleMedium)
                        SwitchRow("Show me in Discover", "Other students can find your profile.", state.discoverable, viewModel::setDiscoverable)
                        SwitchRow("Show online status", "Let others see when you're active.", state.showOnline, viewModel::setShowOnline)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(profile: MyProfile) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        StudentAvatar(profile.student.name, size = 96.dp, accent = profile.student.accent)
        Text(profile.student.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = Spacing.sm))
        Text(profile.student.handle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${profile.student.course} · ${profile.student.year}\n${profile.student.university}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.md), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(profile.posts.toString(), "Posts")
            Stat(compact(profile.followers), "Followers")
            Stat(profile.following.toString(), "Following")
        }
        VibeButton("Edit profile", onClick = { /* TODO: edit profile screen */ }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun compact(n: Int) = if (n >= 1000) String.format(java.util.Locale.US, "%.1fK", n / 1000f) else n.toString()

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
