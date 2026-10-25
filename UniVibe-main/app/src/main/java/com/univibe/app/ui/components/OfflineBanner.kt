package com.univibe.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.data.net.OfflineState
import com.univibe.app.app.AppContainer

/** A small pill while the app is showing saved copies because there is no connection. It disappears by itself once the server answers again. */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    val offline by OfflineState.servedFromCache.collectAsStateWithLifecycle()
    val waiting by AppContainer.outbox.count.collectAsStateWithLifecycle()
    if (!offline && waiting == 0) return
    val text = buildString {
        if (offline) append("Offline \u00B7 showing saved content")
        if (waiting > 0) { if (isNotEmpty()) append(" \u00B7 "); append("$waiting waiting to send") }
    }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
        modifier = modifier.statusBarsPadding().padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
