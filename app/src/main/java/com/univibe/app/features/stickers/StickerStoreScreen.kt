package com.univibe.app.features.stickers

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.univibe.app.data.model.StickerCategories
import com.univibe.app.data.model.StickerPack
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeIconSize
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.rememberPhotoModel
import com.univibe.app.ui.theme.Spacing

/**
 * Browse, preview and install sticker packs. Premium packs can be previewed by anyone; installing one needs Premium,
 * which the server checks. A pack stays installed or uninstalled per person; reinstalling is one tap.
 */
@Composable
fun StickerStoreScreen(onClose: () -> Unit, viewModel: StickerStoreViewModel = viewModel()) {
    val s by viewModel.state.collectAsState()
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(VibeIcons.Back, contentDescription = "Close the sticker store", modifier = Modifier.size(VibeIconSize.Action)) }
            Text("Sticker store", style = MaterialTheme.typography.titleLarge)
        }
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            item { CategoryChip("All", s.category == null) { viewModel.selectCategory(null) } }
            items(StickerCategories, key = { it.first }) { (id, name) -> CategoryChip(name, s.category == id) { viewModel.selectCategory(id) } }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                s.loading -> CircularProgressIndicator(Modifier.size(28.dp))
                s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Spacing.md)) {
                    Text(s.error ?: "", color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    TextButton(onClick = viewModel::load) { Text("Try again") }
                }
                s.packs.isEmpty() -> Text("No sticker packs here yet.", color = colors.onSurfaceVariant, modifier = Modifier.padding(Spacing.md))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(s.packs, key = { it.id }) { pack ->
                        PackRow(pack, busy = s.busyPackId == pack.id, onOpen = { viewModel.openPack(pack) }, onInstall = { viewModel.toggleInstall(pack) })
                    }
                    item(key = "more") {
                        // Reaching the end loads the next page.
                        if (s.nextCursor != null) LaunchedEffect(s.nextCursor) { viewModel.loadMore() }
                        if (s.loadingMore) Box(Modifier.fillMaxWidth().padding(Spacing.sm), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    }
                }
            }
        }
        s.notice?.let {
            Row(Modifier.fillMaxWidth().background(colors.errorContainer).padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = colors.onErrorContainer, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::clearNotice) { Text("OK") }
            }
        }
    }

    s.detail?.let { pack ->
        VibeBottomSheet(onDismiss = viewModel::closePack) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(pack.name, style = MaterialTheme.typography.titleLarge)
                val by = pack.creatorHandle?.let { " · by @$it" } ?: " · UniVibe"
                Text("${pack.stickerCount} stickers$by", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                if (pack.description.isNotBlank()) Text(pack.description, style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    if (s.detailLoading && pack.stickers.isEmpty()) CircularProgressIndicator(Modifier.size(28.dp))
                    else LazyVerticalGrid(columns = GridCells.Adaptive(72.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        gridItems(pack.stickers, key = { it.id }) { st -> StickerImage(st, Modifier.size(72.dp), still = true) }
                    }
                }
                InstallButton(pack, busy = s.busyPackId == pack.id, onClick = { viewModel.toggleInstall(pack) }, modifier = Modifier.fillMaxWidth())
                if (pack.locked) Text("Preview is free. Installing this pack needs UniVibe Premium.", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Box(Modifier.height(Spacing.md))
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = Spacing.md),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant) }
}

@Composable
private fun PackRow(pack: StickerPack, busy: Boolean, onOpen: () -> Unit, onInstall: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainer).clickable(onClick = onOpen).padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            val cover = pack.coverThumbUrl
            if (cover != null) AsyncImage(model = rememberPhotoModel(cover), contentDescription = null, modifier = Modifier.size(48.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(pack.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${pack.stickerCount} stickers" + if (pack.premium) " · Premium" else " · Free",
                style = MaterialTheme.typography.labelMedium, color = if (pack.premium) colors.primary else colors.onSurfaceVariant,
            )
        }
        InstallButton(pack, busy, onInstall)
    }
}

@Composable
private fun InstallButton(pack: StickerPack, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    when {
        busy -> Box(modifier.height(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp)) }
        pack.installed -> OutlinedButton(onClick = onClick, modifier = modifier) { Text("Remove") }
        else -> Button(onClick = onClick, modifier = modifier) { Text(if (pack.locked) "Premium" else "Add") }
    }
}
