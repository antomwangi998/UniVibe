package com.univibe.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

/**
 * Small, read-only map preview of one venue (OpenStreetMap tiles via osmdroid: no API key).
 * Touches are not handled by the map itself, so it never fights the parent scroll; tapping it calls
 * [onClick], which should open the user's maps app (see [openInMaps]).
 * Swap this one composable if you later move to Google Maps or another provider.
 */
@Composable
fun VenueMap(
    latitude: Double,
    longitude: Double,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val mapView = remember {
        Configuration.getInstance().apply {
            // OSM requires an identifying user agent. Cache lives in app cache: no storage permission.
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { mapView.onResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { mapView.onPause() }
    DisposableEffect(mapView) { onDispose { mapView.onDetach() } }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(MaterialTheme.shapes.large)
            .border(0.75.dp, colors.outlineVariant.copy(alpha = 0.55f), MaterialTheme.shapes.large),
    ) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.matchParentSize(),
            update = { view ->
                val point = GeoPoint(latitude, longitude)
                view.controller.setZoom(17.0)
                view.controller.setCenter(point)
                view.overlays.clear()
                view.overlays.add(
                    Marker(view).apply {
                        position = point
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        setInfoWindow(null)
                    },
                )
                view.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
                view.invalidate()
            },
        )
        // Sits above the map so one tap anywhere opens the maps app, and TalkBack gets a real button.
        Box(
            Modifier
                .matchParentSize()
                .semantics { contentDescription = "Map of $title. Opens in your maps app." }
                .clickable(role = Role.Button, onClickLabel = "Open in Maps", onClick = onClick),
        )
    }
}

/** Opens the venue in whatever maps app is installed. No location permission, no SDK, no key. */
fun openInMaps(context: Context, latitude: Double, longitude: Double, label: String) {
    val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No maps app found on this device.", Toast.LENGTH_SHORT).show()
    }
}
