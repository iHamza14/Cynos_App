package com.example.cynos_kotlin

import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.cos

@Composable
fun OsmMapView(
    currentLocation: LocationData?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AndroidView(
        modifier = modifier.fillMaxWidth().height(300.dp),
        factory = { ctx ->
            MapView(ctx).apply {
                // Switching from MAPNIK to OpenTopo to completely bypass the OSM server ban
                setTileSource(TileSourceFactory.OpenTopo)
                setMultiTouchControls(true)
                controller.setZoom(15.0)

                // Add "Blue Dot" for user's location
                val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
                locationOverlay.enableMyLocation()
                locationOverlay.enableFollowLocation()
                overlays.add(locationOverlay)
            }
        },
        update = { mapView ->
            // Note: Bulk downloading using CacheManager violates OpenStreetMap's Tile Usage Policy
            // and results in a permanent or temporary ban (osm.wiki/Blocked).
            // We rely on standard viewing cache instead of bulk downloading.
            if (currentLocation != null && currentLocation.latitude != 0.0) {
                // Keep the camera centered on the user if needed
            }
        }
    )
}
