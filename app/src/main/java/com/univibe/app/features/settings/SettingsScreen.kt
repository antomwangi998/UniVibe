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
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Notifications
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.prefs.ThemeMode
import com.univibe.app.navigation.SettingsRoutes
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.theme.Spacing

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onLogOut: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    var confirmLogOut by remember { mutableStateOf(false) }

    SettingsPage("Settings", onBack) {
        item {
            VibeCard {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    ThemeOption("System", Icons.Outlined.SettingsBrightness, themeMode == ThemeMode.SYSTEM, Modifier.weight(1f)) { onThemeModeChange(ThemeMode.SYSTEM) }
                    ThemeOption("Light", Icons.Outlined.LightMode, themeMode == ThemeMode.LIGHT, Modifier.weight(1f)) { onThemeModeChange(ThemeMode.LIGHT) }
                    ThemeOption("Dark", Icons.Outlined.DarkMode, themeMode == ThemeMode.DARK, Modifier.weight(1f)) { onThemeModeChange(ThemeMode.DARK) }
                }
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(Icons.Outlined.AccountCircle, "Account", { onNavigate(SettingsRoutes.ACCOUNT) }, subtitle = user?.email)
                RowDivider()
                SettingsNavRow(Icons.Outlined.Shield, "Privacy & safety", { onNavigate(SettingsRoutes.PRIVACY) })
                RowDivider()
                SettingsNavRow(Icons.Outlined.Notifications, "Notifications", { onNavigate(SettingsRoutes.NOTIFICATIONS) })
                RowDivider()
                SettingsNavRow(Icons.Outlined.Accessibility, "Accessibility", { onNavigate(SettingsRoutes.ACCESSIBILITY) })
                RowDivider()
                SettingsNavRow(Icons.AutoMirrored.Outlined.HelpOutline, "Help & support", { onNavigate(SettingsRoutes.HELP) })
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(Icons.AutoMirrored.Outlined.Logout, "Log out", { confirmLogOut = true }, destructive = true, showChevron = false)
            }
        }
        item { SettingsNote("UniVibe 0.1.0 · Preview build with sample data") }
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

@Composable
private fun ThemeOption(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) colors.primaryContainer else colors.surface, label = "themeBg")
    val border by animateColorAsState(if (selected) colors.primary else colors.outlineVariant, label = "themeBorder")
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
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
