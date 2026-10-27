package com.univibe.app.features.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.univibe.app.domain.auth.University
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.theme.Spacing

/**
 * Which university to preselect: the only one when there is just one, otherwise the Co-operative University of Kenya,
 * otherwise nothing (the student must choose).
 */
fun defaultUniversityId(universities: List<University>): String? =
    universities.singleOrNull()?.id
        ?: universities.firstOrNull { it.name.contains("co-operative", ignoreCase = true) || it.name.contains("cooperative", ignoreCase = true) }?.id

/** A drop-down for choosing a university. Looks like the read-only university row used when there is only one. */
@Composable
fun UniversityPicker(
    universities: List<University>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var widthPx by remember { mutableStateOf(0) }
    val selected = universities.firstOrNull { it.id == selectedId }
    Box(modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.DropdownList) { open = true },
        ) {
            Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(VibeIcons.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    selected?.name ?: "Choose your university",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Spacing.md),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose university", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.width(with(LocalDensity.current) { widthPx.toDp() }),
        ) {
            universities.forEach { u ->
                DropdownMenuItem(
                    text = {
                        Text(
                            u.name,
                            color = if (u.id == selectedId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { open = false; onSelect(u.id) },
                )
            }
        }
    }
}
