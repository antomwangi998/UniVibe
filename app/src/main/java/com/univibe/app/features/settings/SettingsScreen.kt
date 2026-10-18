package com.univibe.app.features.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.prefs.ThemeMode
import com.univibe.app.navigation.SettingsRoutes
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.theme.Spacing

/**
 * The settings hub: your account at the top, a search box, then the categories that exist.
 * Typing in the search box swaps the categories for matching settings from [SettingsRegistry].
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onLogOut: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    var confirmLogOut by remember { mutableStateOf(false) }
    // Survives rotation and coming back from a nested screen, so the search is where you left it.
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query) { SettingsSearch.search(query) }

    SettingsPage("Settings", onBack) {
        user?.let { u ->
            item {
                VibeCard(onClick = { onNavigate(SettingsRoutes.ACCOUNT) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StudentAvatar(u.fullName, size = 48.dp, imagePath = u.avatarUrl)
                        Column(Modifier.padding(start = Spacing.md).weight(1f)) {
                            Text(u.fullName, style = MaterialTheme.typography.titleMedium)
                            if (u.handle.isNotEmpty()) Text("@${u.handle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(VibeIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            VibeTextField(
                value = query, onValueChange = { query = it.take(60) },
                placeholder = "Search settings", leadingIcon = VibeIcons.Search,
                trailingIcon = if (query.isNotEmpty()) {
                    { androidx.compose.material3.IconButton(onClick = { query = "" }) { Icon(VibeIcons.Close, contentDescription = "Clear search") } }
                } else null,
            )
        }
        if (query.isNotBlank()) {
            if (results.isEmpty()) {
                item { SettingsNote("No settings match \"${query.trim()}\".") }
            } else item {
                SettingsGroup {
                    results.forEachIndexed { i, entry ->
                        if (i > 0) RowDivider()
                        SettingsNavRow(
                            icon = iconFor(entry.path.first()), title = entry.title, onClick = { onNavigate(entry.route) },
                            subtitle = entry.path.joinToString(" › ") + " · " + entry.description,
                        )
                    }
                }
            }
        } else {
            item {
                SettingsGroup {
                    SettingsNavRow(VibeIcons.Profile, "Account", { onNavigate(SettingsRoutes.ACCOUNT) }, subtitle = user?.email)
                    RowDivider()
                    SettingsNavRow(VibeIcons.Lock, "Security", { onNavigate(SettingsRoutes.SECURITY) })
                    RowDivider()
                    SettingsNavRow(VibeIcons.Shield, "Privacy & safety", { onNavigate(SettingsRoutes.PRIVACY) })
                    RowDivider()
                    SettingsNavRow(VibeIcons.Bell, "Notifications", { onNavigate(SettingsRoutes.NOTIFICATIONS) })
                    RowDivider()
                    SettingsNavRow(VibeIcons.Palette, "Appearance", { onNavigate(SettingsRoutes.APPEARANCE) })
                    RowDivider()
                    SettingsNavRow(VibeIcons.Accessibility, "Accessibility", { onNavigate(SettingsRoutes.ACCESSIBILITY) })
                    RowDivider()
                    SettingsNavRow(VibeIcons.Shield, "Permissions", { onNavigate(SettingsRoutes.PERMISSIONS) })
                    RowDivider()
                    SettingsNavRow(VibeIcons.Archive, "Data & storage", { onNavigate(SettingsRoutes.DATA_STORAGE) })
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(VibeIcons.Document, "Account status", { onNavigate(SettingsRoutes.STATUS) })
                    RowDivider()
                    SettingsNavRow(VibeIcons.Help, "Help & support", { onNavigate(SettingsRoutes.HELP) })
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(VibeIcons.Logout, "Log out", { confirmLogOut = true }, destructive = true, showChevron = false)
                }
            }
            item { SettingsNote("UniVibe 0.1.0") }
        }
    }

    if (confirmLogOut) {
        ConfirmDialog(
            title = "Log out?",
            message = "You'll need to log in again to use UniVibe on this device.",
            confirmLabel = "Log out",
            destructive = true,
            onConfirm = { confirmLogOut = false; onLogOut() },
            onDismiss = { confirmLogOut = false },
        )
    }
}

private fun iconFor(category: String): ImageVector = when (category) {
    SettingsCategories.ACCOUNT -> VibeIcons.Profile
    SettingsCategories.SECURITY -> VibeIcons.Lock
    SettingsCategories.PRIVACY -> VibeIcons.Shield
    SettingsCategories.NOTIFICATIONS -> VibeIcons.Bell
    SettingsCategories.APPEARANCE -> VibeIcons.Palette
    SettingsCategories.ACCESSIBILITY -> VibeIcons.Accessibility
    SettingsCategories.PERMISSIONS -> VibeIcons.Shield
    SettingsCategories.DATA -> VibeIcons.Archive
    SettingsCategories.STATUS -> VibeIcons.Document
    else -> VibeIcons.Help
}

/** Settings → Appearance. The theme is a device preference, applied app-wide immediately. */
@Composable
fun AppearanceScreen(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit, onBack: () -> Unit) {
    SettingsPage("Appearance", onBack) {
        item { SettingsSectionTitle("Theme") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ThemeOption("System", VibeIcons.Contrast, themeMode == ThemeMode.SYSTEM, Modifier.weight(1f)) { onThemeModeChange(ThemeMode.SYSTEM) }
                ThemeOption("Light", VibeIcons.Sun, themeMode == ThemeMode.LIGHT, Modifier.weight(1f)) { onThemeModeChange(ThemeMode.LIGHT) }
                ThemeOption("Dark", VibeIcons.Moon, themeMode == ThemeMode.DARK, Modifier.weight(1f)) { onThemeModeChange(ThemeMode.DARK) }
            }
        }
        item { SettingsNote("System follows your phone's light or dark setting. Text size and motion are under Accessibility.") }
    }
}

@Composable
private fun ThemeOption(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) colors.primaryContainer else colors.surface, label = "themeBg")
    val border by animateColorAsState(if (selected) colors.primary else colors.outlineVariant, label = "themeBorder")
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "$label theme" },
        shape = MaterialTheme.shapes.medium,
        color = container,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, border),
    ) {
        Column(Modifier.padding(vertical = Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.primary else colors.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.xs))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
