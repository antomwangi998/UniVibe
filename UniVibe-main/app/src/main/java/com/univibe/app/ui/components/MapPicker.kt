package com.univibe.app.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay

/**
 * Full-screen map where the person drags the map under a fixed pin and taps the button. OpenStreetMap tiles, no key.
 * "Use my location" is optional and asks for permission only when tapped.
 */
@Composable
fun MapPickerDialog(
    initialLat: Double?,
    initialLng: Double?,
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onPicked: (latitude: Double, longitude: Double) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            val start = if (initialLat != null && initialLng != null) GeoPoint(initialLat, initialLng) else GeoPoint(-1.2921, 36.8219)
            controller.setZoom(if (initialLat != null) 17.0 else 14.0)
            controller.setCenter(start)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { mapView.onResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { mapView.onPause() }
    DisposableEffect(mapView) { onDispose { mapView.onDetach() } }

    fun goToMe() {
        scope.launch {
            val fix = LocationFix.current(context)
            if (fix != null) {
                mapView.controller.setZoom(17.0)
                mapView.controller.animateTo(GeoPoint(fix.latitude, fix.longitude))
            } else {
                val why = if (LocationFix.servicesOn(context)) "Couldn't find your location. Try again outside or drag the map." else "Turn on Location in your phone's settings, or drag the map."
                Toast.makeText(context, why, Toast.LENGTH_LONG).show()
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) goToMe() else Toast.makeText(context, "Location permission is off. Drag the map to the place instead.", Toast.LENGTH_LONG).show()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        view.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
                        view.invalidate()
                    },
                )
                // The pin stays in the middle; its tip is the exact centre of the map, which is what gets picked.
                Icon(
                    VibeIcons.Place, contentDescription = "Chosen spot", tint = colors.primary,
                    modifier = Modifier.align(Alignment.Center).offset(y = (-20).dp).size(40.dp),
                )
                Row(
                    Modifier.fillMaxWidth().background(colors.background.copy(alpha = 0.92f)).padding(horizontal = Spacing.sm, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(VibeIcons.Close, contentDescription = "Close map") }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    VibeButton(
                        "Use my location",
                        onClick = { if (LocationFix.hasPermission(context)) goToMe() else permission.launch(LocationFix.permissions) },
                        style = VibeButtonStyle.Secondary, modifier = Modifier.fillMaxWidth(),
                    )
                    VibeButton(
                        confirmLabel,
                        onClick = { val c = mapView.mapCenter; onPicked(c.latitude, c.longitude) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
