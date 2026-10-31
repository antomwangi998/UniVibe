package com.univibe.app.features.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.univibe.app.data.model.CHAT_DIM_MAX
import com.univibe.app.data.model.CHAT_DIM_MIN
import com.univibe.app.data.model.ThemeAsset
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeIconSize
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.rememberPhotoModel
import com.univibe.app.ui.theme.Spacing

/**
 * Themes Studio: a two-column grid of images people can search, filter, favourite and use as their chat background.
 * Looking at an image saves nothing; "Use as chat background" is the only thing that does, and the server decides who may.
 */
@Composable
fun ThemesStudioScreen(onBack: () -> Unit, onOpenPremium: () -> Unit, viewModel: ThemesStudioViewModel = viewModel()) {
    val s by viewModel.state.collectAsState()
    val colors = MaterialTheme.colorScheme
    val gridState = rememberLazyStaggeredGridState()

    // Near the end of the list: fetch the next page.
    LaunchedEffect(gridState, s.items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (s.items.isNotEmpty() && last >= s.items.size - 4) viewModel.loadMore()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(VibeIcons.Back, contentDescription = "Back", modifier = Modifier.size(VibeIconSize.Action)) }
            Text("Themes Studio", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(horizontal = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Chip("Discover", s.tab == StudioTab.Discover) { viewModel.selectTab(StudioTab.Discover) }
            Chip("Favourites", s.tab == StudioTab.Favourites) { viewModel.selectTab(StudioTab.Favourites) }
        }
        if (s.tab == StudioTab.Discover) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm).clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceContainerHigh).padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(VibeIcons.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(VibeIconSize.Action))
                Box(Modifier.weight(1f).padding(horizontal = Spacing.sm, vertical = 12.dp)) {
                    if (s.query.isEmpty()) Text("Search themes", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    BasicTextField(
                        value = s.query, onValueChange = viewModel::onQuery, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                item { Chip("All", s.category == null) { viewModel.selectCategory(null) } }
                items(s.categories, key = { it.slug }) { c -> Chip(c.name, s.category == c.slug) { viewModel.selectCategory(c.slug) } }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                s.loading -> CircularProgressIndicator(Modifier.size(28.dp))
                s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Spacing.md)) {
                    Text(s.error ?: "", color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    TextButton(onClick = viewModel::load) { Text("Try again") }
                }
                s.items.isEmpty() -> Text(
                    if (s.tab == StudioTab.Favourites) "Images you favourite show up here." else if (s.query.isNotBlank()) "Nothing matches \"${s.query.trim()}\"." else "No themes here yet. New collections are added often.",
                    color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(Spacing.md),
                )
                else -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2), state = gridState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalItemSpacing = Spacing.sm,
                ) {
                    staggeredItems(s.items, key = { it.id }) { a -> ThemeCard(a, onOpen = { viewModel.open(a) }, onFavourite = { viewModel.toggleFavourite(a) }) }
                }
            }
        }
        s.notice?.let {
            Row(Modifier.fillMaxWidth().background(colors.secondaryContainer).padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = colors.onSecondaryContainer, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::clearNotice) { Text("OK") }
            }
        }
    }

    s.detail?.let { a ->
        VibeBottomSheet(onDismiss = viewModel::close) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ChatPreview(a, s.dim)
                Text(a.title, style = MaterialTheme.typography.titleLarge)
                val by = a.creatorHandle?.let { "by @$it" } ?: "UniVibe"
                Text("$by · ${a.categoryName}" + if (a.premium) " · Premium" else "", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                if (a.description.isNotBlank()) Text(a.description, style = MaterialTheme.typography.bodyMedium)
                if (a.sourceAttribution.isNotBlank()) Text("Source: ${a.sourceAttribution}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                if (a.canBeChatBackground && !a.locked) {
                    Text("Dim the picture", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = s.dim.toFloat(), onValueChange = { viewModel.setDim(it.toInt()) },
                        valueRange = CHAT_DIM_MIN.toFloat()..CHAT_DIM_MAX.toFloat(),
                        modifier = Modifier.semantics { contentDescription = "Dim the picture" },
                    )
                }
                if (a.locked) Text("Preview is free. Saving and using this image needs UniVibe Premium.", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    if (!a.locked) OutlinedButton(onClick = { viewModel.toggleFavourite(a) }, modifier = Modifier.weight(1f)) { Text(if (a.favourited) "Saved" else "Save") }
                    when {
                        a.locked -> Button(onClick = { viewModel.close(); onOpenPremium() }, modifier = Modifier.weight(1f)) { Text("See Premium") }
                        !a.canBeChatBackground -> Unit
                        s.applying -> Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp)) }
                        else -> Button(onClick = { viewModel.applyAsChatBackground(a) }, modifier = Modifier.weight(1f)) { Text("Use as chat background") }
                    }
                }
                Box(Modifier.height(Spacing.md))
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = Spacing.md)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant) }
}

@Composable
private fun ThemeCard(asset: ThemeAsset, onOpen: () -> Unit, onFavourite: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().aspectRatio(asset.ratio).clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh)
            .clickable(onClick = onOpen)
            .semantics { role = Role.Button; contentDescription = asset.title + if (asset.premium) ", Premium" else "" },
    ) {
        // The card shows the small preview; the full-size picture is only fetched on the detail page.
        asset.thumbUrl?.let { AsyncImage(model = rememberPhotoModel(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (asset.premium) {
            Text(
                "Premium", style = MaterialTheme.typography.labelSmall, color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (!asset.locked) {
            IconButton(onClick = onFavourite, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                Icon(
                    if (asset.favourited) VibeIcons.HeartFilled else VibeIcons.Heart,
                    contentDescription = if (asset.favourited) "Remove from favourites" else "Add to favourites",
                    tint = if (asset.favourited) colors.primary else Color.White, modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** A small mock chat on the image, so people see how it looks behind messages before they save anything. */
@Composable
private fun ChatPreview(asset: ThemeAsset, dim: Int) {
    val colors = MaterialTheme.colorScheme
    val link = if (asset.locked) asset.thumbUrl else asset.fullUrl ?: asset.thumbUrl
    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainerHigh)) {
        link?.let { AsyncImage(model = rememberPhotoModel(it), contentDescription = asset.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (asset.canBeChatBackground) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim / 100f)))
            Column(Modifier.fillMaxSize().padding(Spacing.md), verticalArrangement = Arrangement.Bottom) {
                Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh, modifier = Modifier.align(Alignment.Start)) {
                    Text("Are you coming to the hackathon?", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                Box(Modifier.height(6.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = colors.primary, modifier = Modifier.align(Alignment.End)) {
                    Text("Yes! See you there.", style = MaterialTheme.typography.bodyMedium, color = colors.onPrimary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}
