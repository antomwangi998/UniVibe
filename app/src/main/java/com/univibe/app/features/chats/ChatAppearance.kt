package com.univibe.app.features.chats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.univibe.app.ui.components.GlassSurface
import com.univibe.app.ui.components.GlassTone
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.theme.Spacing
import java.io.File

/**
 * Chat theme = the accent of the conversation (my bubbles, send button, links, reply bars). Each theme has a colour pair
 * for light and one for dark, picked so the text on my bubbles stays readable (4.5:1 or better) and the accent stays
 * readable as text on the incoming bubbles. "default" leaves the app's own teal untouched.
 */
class ChatTheme(val id: String, val name: String, val light: Pair<Color, Color>?, val dark: Pair<Color, Color>?)

val ChatThemes = listOf(
    ChatTheme("default", "Teal", null, null),
    ChatTheme("ocean", "Ocean", Color(0xFF1D4ED8) to Color.White, Color(0xFF60A5FA) to Color(0xFF0B1B33)),
    ChatTheme("violet", "Violet", Color(0xFF6D28D9) to Color.White, Color(0xFFA78BFA) to Color(0xFF1E1038)),
    ChatTheme("rose", "Rose", Color(0xFFBE123C) to Color.White, Color(0xFFFB7185) to Color(0xFF3B0A17)),
    ChatTheme("sunset", "Sunset", Color(0xFFC2410C) to Color.White, Color(0xFFFB923C) to Color(0xFF3B1A05)),
    ChatTheme("forest", "Forest", Color(0xFF15803D) to Color.White, Color(0xFF4ADE80) to Color(0xFF052E14)),
    ChatTheme("graphite", "Graphite", Color(0xFF475569) to Color.White, Color(0xFFCBD5E1) to Color(0xFF0F172A)),
)

fun chatThemeById(id: String): ChatTheme = ChatThemes.firstOrNull { it.id == id } ?: ChatThemes.first()

/** Re-colours everything inside with the chosen accent, so bubbles, buttons and links all follow without each knowing. */
@Composable
fun ChatThemed(themeId: String, content: @Composable () -> Unit) {
    val theme = chatThemeById(themeId)
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    val pair = if (dark) theme.dark else theme.light
    if (pair == null) { content(); return }
    MaterialTheme(
        colorScheme = base.copy(primary = pair.first, onPrimary = pair.second),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}

class ChatWallpaper(val id: String, val name: String)

val ChatWallpapers = listOf(
    ChatWallpaper("none", "Plain"),
    ChatWallpaper("aurora", "Aurora"),
    ChatWallpaper("dusk", "Dusk"),
    ChatWallpaper("mint", "Mint"),
    ChatWallpaper("sand", "Sand"),
    ChatWallpaper("doodles", "Doodles"),
)

private fun gradientFor(id: String): List<Color>? = when (id) {
    // Tints laid over the normal background, so the same wallpaper works in light and dark mode.
    "aurora" -> listOf(Color(0xFF14B8A6).copy(alpha = 0.22f), Color(0xFF3B82F6).copy(alpha = 0.14f))
    "dusk" -> listOf(Color(0xFF8B5CF6).copy(alpha = 0.22f), Color(0xFFF43F5E).copy(alpha = 0.12f))
    "mint" -> listOf(Color(0xFF34D399).copy(alpha = 0.18f), Color(0xFF14B8A6).copy(alpha = 0.06f))
    "sand" -> listOf(Color(0xFFF59E0B).copy(alpha = 0.16f), Color(0xFFFB7185).copy(alpha = 0.08f))
    else -> null
}

/** Draws behind the messages. Never blocks touches. A missing picture falls back to plain, never to a blank screen. */
@Composable
fun ChatWallpaperLayer(wallpaperId: String, photoPath: String?, modifier: Modifier = Modifier) {
    val photoOk = remember(photoPath) { photoPath != null && File(photoPath).exists() }
    when {
        wallpaperId == "photo" && photoOk && photoPath != null -> Box(modifier) {
            PhotoImage(photoPath, contentDescription = null, modifier = Modifier.fillMaxSize())
            // A dim layer keeps the bubbles and the date chips readable on any picture.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
        }
        wallpaperId == "doodles" -> {
            val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
            Canvas(modifier) {
                val step = 44.dp.toPx()
                var row = 0
                var y = step / 2
                while (y < size.height + step) {
                    var x = if (row % 2 == 0) step / 2 else step
                    while (x < size.width + step) {
                        if ((row + (x / step).toInt()) % 3 == 0) {
                            val r = 3.dp.toPx()
                            drawLine(ink, Offset(x - r, y), Offset(x + r, y), strokeWidth = 1.6.dp.toPx())
                            drawLine(ink, Offset(x, y - r), Offset(x, y + r), strokeWidth = 1.6.dp.toPx())
                        } else drawCircle(ink, radius = 2.dp.toPx(), center = Offset(x, y))
                        x += step
                    }
                    y += step; row++
                }
            }
        }
        else -> gradientFor(wallpaperId)?.let { Box(modifier.background(Brush.verticalGradient(it))) }
    }
}

/** Pick the accent and the wallpaper. Every tap applies at once, so the chat behind the sheet shows the result. */
@Composable
fun ChatAppearanceSheet(
    themeId: String, wallpaperId: String, wallpaperPhoto: String?,
    onTheme: (String) -> Unit, onWallpaper: (String) -> Unit, onPickPhoto: () -> Unit,
    onReset: () -> Unit, onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    VibeBottomSheet(onDismiss = onDismiss) {
        Text("Chat theme and wallpaper", style = MaterialTheme.typography.titleMedium)
        Text("Only you see this, on this phone.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.sm))

        // A small live preview of what the chat will look like.
        Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(16.dp)).background(colors.background).border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp))) {
            ChatWallpaperLayer(wallpaperId, wallpaperPhoto, Modifier.matchParentSize())
            Column(Modifier.fillMaxSize().padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassSurface(shape = RoundedCornerShape(14.dp), tone = GlassTone.Incoming) {
                    Text("Hey, are you coming today?", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    GlassSurface(shape = RoundedCornerShape(14.dp), tone = GlassTone.Outgoing) {
                        Text("On my way!", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }

        Text("Theme", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            items(ChatThemes, key = { it.id }) { t ->
                val swatch = (if (dark) t.dark else t.light)?.first ?: Color(0xFF0D9488)
                val picked = t.id == themeId
                Column(
                    Modifier.width(56.dp)
                        .clickable(role = Role.RadioButton, onClickLabel = "Use ${t.name} theme") { onTheme(t.id) }
                        .semantics { selected = picked; contentDescription = "${t.name} theme" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(swatch)
                            .then(if (picked) Modifier.border(BorderStroke(3.dp, colors.onSurface), CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { if (picked) Icon(VibeIcons.Check, contentDescription = null, tint = if (dark) Color.Black else Color.White) }
                    Text(t.name, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = if (picked) colors.onSurface else colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        Text("Wallpaper", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            items(ChatWallpapers, key = { it.id }) { w ->
                WallpaperTile(w.name, picked = w.id == wallpaperId, onClick = { onWallpaper(w.id) }) {
                    ChatWallpaperLayer(w.id, null, Modifier.fillMaxSize())
                }
            }
            item(key = "photo") {
                WallpaperTile("Photo", picked = wallpaperId == "photo", onClick = onPickPhoto) {
                    if (wallpaperPhoto != null && File(wallpaperPhoto).exists()) PhotoImage(wallpaperPhoto, contentDescription = null, modifier = Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(VibeIcons.Add, contentDescription = null, tint = colors.primary) }
                }
            }
        }
        TextButton(onClick = onReset, modifier = Modifier.padding(top = Spacing.sm)) { Text("Reset to default") }
    }
}

@Composable
private fun WallpaperTile(label: String, picked: Boolean, onClick: () -> Unit, preview: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.width(72.dp)
            .clickable(role = Role.RadioButton, onClickLabel = if (label == "Photo") "Choose a photo wallpaper" else "Use $label wallpaper", onClick = onClick)
            .semantics { selected = picked; contentDescription = "$label wallpaper" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(width = 72.dp, height = 100.dp).clip(RoundedCornerShape(14.dp)).background(colors.background)
                .border(BorderStroke(if (picked) 3.dp else 1.dp, if (picked) colors.primary else colors.outlineVariant), RoundedCornerShape(14.dp)),
        ) { preview() }
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (picked) colors.onSurface else colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}
