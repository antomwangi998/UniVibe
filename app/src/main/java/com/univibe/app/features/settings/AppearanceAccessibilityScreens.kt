package com.univibe.app.features.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.prefs.ThemeMode
import com.univibe.app.data.settings.AppFont
import com.univibe.app.data.settings.TextScale
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.theme.Spacing
import com.univibe.app.ui.theme.family

/**
 * Appearance and Accessibility. Every control here is applied app-wide the moment it changes (see UniVibeApp and
 * UniVibeTheme): theme, font and text size, bold text, high contrast, reduce motion and haptic feedback. They are
 * device preferences, so none of them is sent to the server.
 *
 * Text size has one screen, under Accessibility → Text and display; Appearance links to it so there is a single control.
 */

private fun ThemeMode.label() = when (this) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }

private fun openSystem(context: Context, action: String) {
    runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Shows how the current choices look, so a change can be judged before leaving the screen. */
@Composable
private fun TextPreview() {
    VibeCard {
        Text("Preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Great session today on AI & innovation!", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Spacing.xs))
        Text("Study group at the library, 6 PM.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
    }
}

// ------------------------------------------------------------------ Appearance

/** Settings → Appearance: a hub that shows each current choice. */
@Composable
fun AppearanceScreen(
    themeMode: ThemeMode,
    onBack: () -> Unit,
    onTheme: () -> Unit,
    onFont: () -> Unit,
    onTextSize: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage("Appearance", onBack) {
        item {
            SettingsGroup {
                SettingsNavRow(VibeIcons.Contrast, "Theme", onTheme, subtitle = themeMode.label())
                RowDivider()
                SettingsNavRow(VibeIcons.Palette, "Font", onFont, subtitle = settings.font.label)
                RowDivider()
                SettingsNavRow(VibeIcons.Document, "Text size", onTextSize, subtitle = settings.textScale.label)
            }
        }
        item { SettingsNote("Changes apply everywhere straight away and stay on this phone. Bold text, high contrast and motion are under Accessibility.") }
    }
}

@Composable
fun ThemeScreen(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit, onBack: () -> Unit) {
    SettingsPage("Theme", onBack) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ThemeOption("System", VibeIcons.Contrast, themeMode == ThemeMode.SYSTEM, Modifier.weight(1f)) { onThemeModeChange(ThemeMode.SYSTEM) }
                ThemeOption("Light", VibeIcons.Sun, themeMode == ThemeMode.LIGHT, Modifier.weight(1f)) { onThemeModeChange(ThemeMode.LIGHT) }
                ThemeOption("Dark", VibeIcons.Moon, themeMode == ThemeMode.DARK, Modifier.weight(1f)) { onThemeModeChange(ThemeMode.DARK) }
            }
        }
        item { TextPreview() }
        item { SettingsNote("System follows your phone's light or dark setting.") }
    }
}

@Composable
fun FontScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage("Font", onBack) {
        item {
            SettingsGroup {
                AppFont.entries.forEach { f ->
                    SettingsRadioRow(f.label, f.description, settings.font == f, f.family()) { viewModel.update { it.copy(font = f) } }
                }
            }
        }
        item { TextPreview() }
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

// --------------------------------------------------------------- Accessibility

/** Settings → Accessibility: a hub that shows each current choice. */
@Composable
fun AccessibilityScreen(
    onBack: () -> Unit,
    onDisplay: () -> Unit,
    onMotion: () -> Unit,
    onInteraction: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsPage("Accessibility", onBack) {
        item {
            SettingsGroup {
                SettingsNavRow(
                    VibeIcons.Document, "Text and display", onDisplay,
                    subtitle = buildList {
                        add("Text size: ${s.textScale.label}")
                        if (s.boldText) add("bold")
                        if (s.highContrast) add("high contrast")
                    }.joinToString(", "),
                )
                RowDivider()
                SettingsNavRow(VibeIcons.Sparkle, "Motion", onMotion, subtitle = if (s.reduceMotion) "Reduced" else "Standard")
                RowDivider()
                SettingsNavRow(VibeIcons.Sensors, "Touch and haptics", onInteraction, subtitle = if (s.hapticFeedback) "Haptic feedback on" else "Haptic feedback off")
            }
        }
        item {
            VibeButton(
                "Open system accessibility settings",
                onClick = { openSystem(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                modifier = Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary,
                leadingIcon = VibeIcons.Accessibility,
            )
        }
        item { SettingsNote("UniVibe also follows your phone's font size, display size and animation settings.") }
    }
}

@Composable
fun AccessibilityDisplayScreen(onBack: () -> Unit, onTextSize: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage("Text and display", onBack) {
        item {
            SettingsGroup {
                SettingsNavRow(VibeIcons.Document, "Text size", onTextSize, subtitle = s.textScale.label)
                RowDivider()
                SettingsSwitchRow(
                    "Bold text", s.boldText, { v -> viewModel.update { it.copy(boldText = v) } },
                    subtitle = "Heavier letters throughout UniVibe.", icon = VibeIcons.Document,
                )
                RowDivider()
                SettingsSwitchRow(
                    "High contrast", s.highContrast, { v -> viewModel.update { it.copy(highContrast = v) } },
                    subtitle = "Stronger secondary text and borders, in light and dark themes.", icon = VibeIcons.Contrast,
                )
            }
        }
        item { TextPreview() }
    }
}

@Composable
fun TextSizeScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage("Text size", onBack) {
        item {
            SettingsGroup {
                TextScale.entries.forEach { scale ->
                    SettingsRadioRow(scale.label, null, s.textScale == scale) { viewModel.update { it.copy(textScale = scale) } }
                }
            }
        }
        item { TextPreview() }
        item { SettingsNote("This adds to your phone's own font size setting.") }
    }
}

@Composable
fun MotionScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage("Motion", onBack) {
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    "Reduce motion", s.reduceMotion, { v -> viewModel.update { it.copy(reduceMotion = v) } },
                    subtitle = "Calmer transitions and no looping animations.", icon = VibeIcons.Sparkle,
                )
            }
        }
        item { SettingsNote("UniVibe also reduces motion on its own when animations are turned off in Android's settings.") }
    }
}

@Composable
fun TouchAndHapticsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    SettingsPage("Touch and haptics", onBack) {
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    "Haptic feedback", s.hapticFeedback, { v -> viewModel.update { it.copy(hapticFeedback = v) } },
                    subtitle = "A short vibration when you long-press a post or chat, or swipe a message to reply.", icon = VibeIcons.Sensors,
                )
            }
        }
        item { SettingsNote("This only controls UniVibe's own vibrations. Your phone's keyboard and touch feedback are set in Android's settings.") }
    }
}
