package com.univibe.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.univibe.app.app.AppContainer
import com.univibe.app.data.campus.CampusScope
import com.univibe.app.data.campus.CampusScopeStore
import com.univibe.app.ui.theme.Spacing

/**
 * The campus filter: your campus, every campus, or one other campus. It is shared by Home, Discover, Events and Search,
 * so the choice carries across the app. It hides itself while there is only one campus to look at (or offline), and it
 * says so out loud when the app opened on All campuses because the home campus is still small.
 */
@Composable
fun CampusScopeBar(modifier: Modifier = Modifier) {
    val menu by CampusScopeStore.menu.collectAsState()
    val scope by CampusScopeStore.scope.collectAsState()
    val autoOpened by CampusScopeStore.autoOpened.collectAsState()

    LaunchedEffect(Unit) {
        if (CampusScopeStore.menu.value == null) {
            runCatching { AppContainer.campusScopeRepository.load() }.getOrNull()?.let(CampusScopeStore::applyMenu)
        }
    }

    val m = menu ?: return
    if (m.others.isEmpty()) return

    Column(modifier) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "home") {
                FilterChip(
                    selected = scope == CampusScope.Home,
                    onClick = { CampusScopeStore.select(CampusScope.Home) },
                    label = { Text(m.home.shortName) },
                )
            }
            item(key = "all") {
                FilterChip(
                    selected = scope == CampusScope.All,
                    onClick = { CampusScopeStore.select(CampusScope.All) },
                    label = { Text("All campuses") },
                )
            }
            items(m.others, key = { it.id }) { c ->
                FilterChip(
                    selected = (scope as? CampusScope.One)?.id == c.id,
                    onClick = { CampusScopeStore.select(CampusScope.One(c.id, c.name)) },
                    label = { Text(c.shortName) },
                )
            }
        }
        if (autoOpened && scope == CampusScope.All) {
            Row(Modifier.padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Few ${m.home.shortName} students are here yet, so you're seeing every campus. Switch any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = CampusScopeStore::dismissAutoNote) { Text("Got it") }
            }
        }
    }
}
