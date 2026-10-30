package com.univibe.app.features.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.ProfileLook
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing

/**
 * Customise how my profile looks to other students. Trying options is free and shows a live preview; saving needs
 * UniVibe Premium, and the server checks that again. What other students see is always what the server last accepted.
 */
@Composable
fun ProfileLookScreen(onBack: () -> Unit, onOpenPremium: () -> Unit, viewModel: ProfileLookViewModel = viewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Profile look", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
            s.error != null -> ErrorState(s.error!!, { viewModel.load() })
            else -> LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item { LookPreview(s.draft) }
                if (s.locked) item {
                    VibeCard(Modifier.fillMaxWidth()) {
                        Text("Try it, then unlock it", style = MaterialTheme.typography.titleSmall)
                        Text("Previewing is free. Saving your look so other students see it is part of UniVibe Premium.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
                        Spacer(Modifier.height(Spacing.sm))
                        VibeButton("See UniVibe Premium", onOpenPremium, Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary)
                    }
                }
                s.notice?.let { n -> item { Text(n, style = MaterialTheme.typography.bodyMedium, color = if (n.startsWith("Saved")) colors.primary else colors.error) } }

                item { Heading("Theme") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        item { Choice("Standard", s.draft.theme == null, { Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant)) }) { viewModel.pickTheme(null) } }
                        items(ProfileThemes, key = { it.id }) { t ->
                            Choice(t.name, s.draft.theme == t.id, { Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(themeColors(t.id) ?: listOf(Color.Gray, Color.DarkGray)))) }) { viewModel.pickTheme(t.id) }
                        }
                    }
                }
                item { Heading("Accent colour") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        item { Choice("Theme", s.draft.accent == null, { Box(Modifier.size(40.dp).clip(CircleShape).border(BorderStroke(1.dp, colors.outline), CircleShape)) }) { viewModel.pickAccent(null) } }
                        items(AccentPresets, key = { it }) { hex ->
                            Choice(null, s.draft.accent == hex, { Box(Modifier.size(40.dp).clip(CircleShape).background(parseAccent(hex) ?: Color.Gray)) }, label = "Accent $hex") { viewModel.pickAccent(hex) }
                        }
                    }
                }
                item { Heading("Avatar frame") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        item { Choice("None", s.draft.frame == null, { Box(Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceVariant)) }) { viewModel.pickFrame(null) } }
                        items(ProfileFrames, key = { it.id }) { f ->
                            Choice(f.name, s.draft.frame == f.id, { Box(Modifier.size(48.dp).profileFrame(f.id, lookAccent(s.draft, colors.primary))) { Box(Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceVariant)) } }) { viewModel.pickFrame(f.id) }
                        }
                    }
                }
                item { Heading("Badge") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        item { Choice("None", s.draft.badge == null, { Text("\u2014", style = MaterialTheme.typography.titleMedium) }) { viewModel.pickBadge(null) } }
                        items(ProfileBadges, key = { it.id }) { b ->
                            Choice(b.label, s.draft.badge == b.id, { Text(b.glyph, style = MaterialTheme.typography.titleLarge) }) { viewModel.pickBadge(b.id) }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        VibeButton("Save look", viewModel::save, Modifier.fillMaxWidth(), loading = s.saving, enabled = s.canSave)
                        if (s.dirty && !s.canSave && !s.saving) Text("Saving needs UniVibe Premium for the options you picked.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        VibeButton("Clear everything", viewModel::clearAll, Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary, enabled = !s.draft.isEmpty && !s.saving)
                        Text("Badges are for fun and say what they are. Only UniVibe's own check makes a student verified.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.xs))

@Composable
private fun Choice(caption: String?, picked: Boolean, swatch: @Composable () -> Unit, label: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.clickable(onClick = onClick).padding(2.dp)
            .semantics { selected = picked; contentDescription = label ?: caption ?: "Option" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.clip(RoundedCornerShape(14.dp)).border(BorderStroke(if (picked) 3.dp else 1.dp, if (picked) colors.primary else colors.outlineVariant), RoundedCornerShape(14.dp)).padding(4.dp), contentAlignment = Alignment.Center) { swatch() }
        if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = if (picked) colors.onSurface else colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

/** A small stand-in for the real profile header, drawn from the same pieces the real profile uses. */
@Composable
private fun LookPreview(look: ProfileLook) {
    val colors = MaterialTheme.colorScheme
    val palette = themeColors(look.theme)
    val accent = lookAccent(look, colors.primary)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surface), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) {
            Canvas(
                Modifier.fillMaxWidth().height(110.dp).background(
                    if (palette != null) Brush.linearGradient(palette) else Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.85f), colors.primary.copy(alpha = 0.5f))),
                ),
            ) { look.theme?.let { drawThemeMotif(it) } }
            Box(Modifier.align(Alignment.BottomCenter).offset(y = 42.dp).profileFrame(look.frame, accent)) {
                Box(Modifier.size(84.dp).clip(CircleShape).background(colors.surface).padding(5.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                    Text("You", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(48.dp))
        Text("Your name", style = MaterialTheme.typography.titleMedium)
        ProfileBadgeChip(look.badge, accent, Modifier.padding(vertical = Spacing.xs))
        Spacer(Modifier.height(Spacing.sm))
    }
}
