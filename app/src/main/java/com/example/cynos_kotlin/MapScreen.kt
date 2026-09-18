package com.example.cynos_kotlin

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
fun OsmMapView(
    currentLocation: LocationData?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Create ONE MapView and keep it across recompositions
    val mapView = remember {
        MapView(context).apply {

            setTileSource(TileSourceFactory.OpenTopo)

            setMultiTouchControls(true)
            setBuiltInZoomControls(false)

            controller.setZoom(17.0)

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    // ONE marker
    val locationMarker = remember {
        Marker(mapView).apply {
            title = "Current Location"

            setAnchor(
                Marker.ANCHOR_CENTER,
                Marker.ANCHOR_BOTTOM
            )
        }
    }

    // Only center camera on first GPS fix
    var hasCenteredInitially by remember {
        mutableStateOf(false)
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),

        // IMPORTANT:
        // Return the SAME MapView we created above.
        factory = {
            mapView
        },

        update = { map ->

            currentLocation?.let { location ->

                if (
                    location.latitude != 0.0 &&
                    location.longitude != 0.0
                ) {

                    val point = GeoPoint(
                        location.latitude,
                        location.longitude
                    )

                    // Add marker once
                    if (!map.overlays.contains(locationMarker)) {
                        map.overlays.add(locationMarker)
                    }

                    // Move existing marker
                    locationMarker.position = point

                    // Center only on first GPS fix
                    if (!hasCenteredInitially) {

                        map.controller.setZoom(17.0)
                        map.controller.setCenter(point)

                        hasCenteredInitially = true
                    }

                    map.invalidate()
                }
            }
        }
    )

    DisposableEffect(Unit) {

        mapView.onResume()

        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
}