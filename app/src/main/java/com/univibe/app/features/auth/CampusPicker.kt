package com.univibe.app.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.univibe.app.domain.auth.Campus
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.theme.Spacing

/** "Which campus are you on?" for universities that have several. Draws nothing when [campuses] is empty. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CampusPicker(campuses: List<Campus>, selectedId: String?, error: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    if (campuses.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Your campus", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            campuses.forEach { c -> InterestChip(c.name, selected = selectedId == c.id, onClick = { onSelect(c.id) }) }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
