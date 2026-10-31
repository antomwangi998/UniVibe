@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.univibe.app.features.stickers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.univibe.app.data.model.StickerPack
import com.univibe.app.data.model.StickerRef
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeIconSize
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.rememberPhotoModel
import com.univibe.app.ui.theme.Spacing

private const val TAB_RECENT = 0
private const val TAB_FAVOURITES = 1
private const val FIRST_PACK_TAB = 2

/**
 * The sticker bottom sheet for the chat composer: search, recent, favourites and one tab per installed pack.
 * Tapping a sticker calls [onPick] and the chat sends it at once. A sticker from a Premium pack without Premium
 * is shown dimmed and calls [onLocked] instead; the server refuses it as well.
 */
@Composable
fun StickerPickerSheet(
    onPick: (StickerRef) -> Unit,
    onOpenStore: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: StickerPickerViewModel = viewModel(),
) {
    val s by viewModel.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(if (s.recent.isEmpty()) FIRST_PACK_TAB else TAB_RECENT) }
    var notice by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    // The view model outlives the sheet; fetch again on every open so packs added or removed in the store show up.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    VibeBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            // Search
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHigh).padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(VibeIcons.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(VibeIconSize.Action))
                Box(Modifier.weight(1f).padding(horizontal = Spacing.sm, vertical = 12.dp)) {
                    if (s.query.isEmpty()) Text("Search stickers", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    BasicTextField(
                        value = s.query, onValueChange = viewModel::onQuery, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary), modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (s.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQuery("") }, modifier = Modifier.size(40.dp)) {
                        Icon(VibeIcons.Close, contentDescription = "Clear search", modifier = Modifier.size(VibeIconSize.Action))
                    }
                }
            }

            // Tabs: recent, favourites, each installed pack, then the store
            if (s.query.isEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    item { TabChip("Recent", tab == TAB_RECENT) { tab = TAB_RECENT } }
                    item { TabChip("Favourites", tab == TAB_FAVOURITES) { tab = TAB_FAVOURITES } }
                    itemsIndexed(s.packs, key = { _, p -> p.id }) { i, p -> PackTab(p, tab == FIRST_PACK_TAB + i) { tab = FIRST_PACK_TAB + i } }
                    item { TabChip("+ Store", false, onClick = onOpenStore) }
                }
            }

            val shown: List<StickerRef>? = when {
                s.query.isNotBlank() -> s.results
                tab == TAB_RECENT -> s.recent
                tab == TAB_FAVOURITES -> s.favourites
                else -> s.packs.getOrNull(tab - FIRST_PACK_TAB)?.stickers
            }
            Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                when {
                    s.loading -> CircularProgressIndicator(Modifier.size(28.dp))
                    s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.error ?: "", color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                        TextButton(onClick = viewModel::refresh) { Text("Try again") }
                    }
                    s.packs.isEmpty() && s.query.isBlank() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("You haven't added any sticker packs yet.", color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                        TextButton(onClick = onOpenStore) { Text("Open the sticker store") }
                    }
                    shown == null -> CircularProgressIndicator(Modifier.size(28.dp))
                    shown.isEmpty() -> Text(
                        when {
                            s.query.isNotBlank() -> "No stickers match \"${s.query.trim()}\"."
                            tab == TAB_RECENT -> "Stickers you send show up here."
                            tab == TAB_FAVOURITES -> "Press and hold a sticker to favourite it."
                            else -> "This pack is empty."
                        },
                        color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(76.dp), modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        items(shown, key = { it.id }) { st ->
                            val isFavourite = s.favourites.any { it.id == st.id }
                            StickerCell(
                                st, favourite = isFavourite,
                                onClick = { if (st.locked) notice = "This pack needs UniVibe Premium." else onPick(st) },
                                onLongClick = { viewModel.toggleFavourite(st) },
                            )
                        }
                    }
                }
            }
            notice?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.error, modifier = Modifier.padding(bottom = Spacing.xs)) }
        }
    }
}

@Composable
private fun StickerCell(sticker: StickerRef, favourite: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val label = (sticker.label ?: "Sticker") + (if (favourite) ", favourite" else "") + (if (sticker.locked) ", needs Premium" else "")
    Box(
        Modifier.size(76.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // Grids show the still thumbnail: a whole screen of animations would be heavy on a small phone.
        StickerImage(sticker, Modifier.size(68.dp), still = true, dimmed = sticker.locked)
        if (favourite) {
            Icon(
                VibeIcons.HeartFilled, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(12.dp),
            )
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
    }
}

@Composable
private fun PackTab(pack: StickerPack, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .semantics { role = Role.Tab; contentDescription = pack.name + if (pack.locked) ", needs Premium" else "" },
        contentAlignment = Alignment.Center,
    ) {
        val cover = pack.coverThumbUrl
        if (cover != null) {
            AsyncImage(
                model = rememberPhotoModel(cover), contentDescription = null,
                modifier = Modifier.size(26.dp), alpha = if (pack.locked) 0.45f else 1f,
            )
        } else Text(pack.name.take(1), style = MaterialTheme.typography.labelLarge)
    }
}
