package com.univibe.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.app.AppContainer
import com.univibe.app.data.campus.CampusInfo
import com.univibe.app.data.campus.CampusMenu
import com.univibe.app.data.campus.CampusScope
import com.univibe.app.data.campus.CampusScopeStore
import com.univibe.app.ui.theme.Spacing

/**
 * The campus filter, shared by Home, Discover, Events and Search so the choice carries across the app.
 *
 * It is one compact pill (your campus, All campuses, or one other campus) that opens a sheet with every campus and its
 * student count, so it stays the same size however many campuses exist. It hides itself while there is only one campus
 * to look at (or offline). [CampusScopeNote] is the one-line hint shown when the app opened on All because the home
 * campus is still small.
 */
@Composable
fun CampusScopePill(modifier: Modifier = Modifier) {
    val menu by CampusScopeStore.menu.collectAsState()
    val scope by CampusScopeStore.scope.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }

    LoadCampusMenu()

    val m = menu ?: return
    if (m.others.isEmpty()) return

    val label = scopeLabel(scope, m)
    val active = scope != CampusScope.Home
    val colors = MaterialTheme.colorScheme
    val tint = if (active) colors.primary else colors.onSurfaceVariant
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .border(1.dp, if (active) colors.primary.copy(alpha = 0.6f) else colors.outlineVariant, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClickLabel = "Change campus") { open = true }
            .semantics { contentDescription = "Campus filter, showing $label" }
            .padding(horizontal = Spacing.sm + 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (scope == CampusScope.Home) VibeIcons.School else VibeIcons.Public, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
        Icon(VibeIcons.ChevronDown, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }

    if (open) {
        CampusScopeSheet(m, scope, onPick = { CampusScopeStore.select(it); open = false }, onDismiss = { open = false })
    }
}

/** The one-line hint under the filter, shown once when the app opened on All campuses because home is quiet. */
@Composable
fun CampusScopeNote(modifier: Modifier = Modifier) {
    val menu by CampusScopeStore.menu.collectAsState()
    val scope by CampusScopeStore.scope.collectAsState()
    val autoOpened by CampusScopeStore.autoOpened.collectAsState()
    val m = menu ?: return
    if (!autoOpened || scope != CampusScope.All) return
    Row(modifier.fillMaxWidth().padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Few ${m.home.shortName} students yet, showing all campuses",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(50))
                .clickable(role = Role.Button, onClickLabel = "Dismiss", onClick = CampusScopeStore::dismissAutoNote),
            contentAlignment = Alignment.Center,
        ) { Icon(VibeIcons.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
    }
}

/** Pill on the right with the hint underneath: the layout Discover, Events and Search use. */
@Composable
fun CampusScopeBar(modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), horizontalArrangement = Arrangement.End) { CampusScopePill() }
        CampusScopeNote()
    }
}

@Composable
private fun LoadCampusMenu() {
    LaunchedEffect(Unit) {
        if (CampusScopeStore.menu.value == null) {
            runCatching { AppContainer.campusScopeRepository.load() }.getOrNull()?.let(CampusScopeStore::applyMenu)
        }
    }
}

private fun scopeLabel(scope: CampusScope, m: CampusMenu): String = when (scope) {
    CampusScope.Home -> m.home.shortName
    CampusScope.All -> "All campuses"
    is CampusScope.One -> m.others.firstOrNull { it.id == scope.id }?.shortName ?: scope.name
}

@Composable
private fun CampusScopeSheet(menu: CampusMenu, selected: CampusScope, onPick: (CampusScope) -> Unit, onDismiss: () -> Unit) {
    VibeBottomSheet(onDismiss = onDismiss) {
        Text("Campus", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.sm))
        Column(Modifier.selectableGroup()) {
            SheetRow("Your campus · ${menu.home.name}", "${menu.home.members} students", VibeIcons.School, selected == CampusScope.Home) { onPick(CampusScope.Home) }
            SheetRow("All campuses", "Everyone, across every university", VibeIcons.Public, selected == CampusScope.All) { onPick(CampusScope.All) }
            menu.others.forEach { c: CampusInfo ->
                SheetRow(c.name, "${c.members} students", VibeIcons.School, (selected as? CampusScope.One)?.id == c.id) { onPick(CampusScope.One(c.id, c.name)) }
            }
        }
    }
}

@Composable
private fun SheetRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    val tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isSelected) Icon(VibeIcons.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}
