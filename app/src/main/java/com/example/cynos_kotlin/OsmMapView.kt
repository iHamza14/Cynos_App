package com.example.cynos_kotlin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * ============================================================
 * PUT YOUR STADIA MAPS KEY HERE
 * Get one free at https://client.stadiamaps.com/signup
 * (no credit card, 200k tiles/month free)
 * ============================================================
 */
private const val STADIA_API_KEY = "0809d2df-bb7d-43b0-a6ba-918cdaf2af80"

/**
 * Stadia Maps "Alidade Smooth" — clean, modern light basemap (the style
 * most nav apps use). Free tier, needs an API key appended as a query
 * param, which is why this isn't a plain XYTileSource: that class only
 * knows how to glue together .../{z}/{x}/{y}.png and has nowhere to put
 * "?api_key=...".
 *
 * Other good free Stadia styles if you want to swap later — just change
 * the path segment below:
 *   alidade_smooth        - clean light (current)
 *   alidade_smooth_dark   - clean dark
 *   outdoors              - terrain shading, hiking-map feel
 *   stamen_toner_lite     - minimal black/white/grey, very sharp
 *
 * If you get a 401/403 here, the key is missing/wrong/not activated yet
 * (activation can take a couple of minutes after signup).
 */
private val STADIA_STYLE = object : OnlineTileSourceBase(
    "StadiaAlidadeSmooth",
    0, 20, 256, ".png",
    arrayOf("https://tiles.stadiamaps.com/tiles/alidade_smooth/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
        val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
        val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}$zoom/$x/$y.png?api_key=$STADIA_API_KEY"
    }
}

/**
 * Three independent markers:
 *   blue  = raw GNSS fix
 *   amber = dead-reckoning estimate (+ trail)
 *   green = Viterbi road-snapped position (+ trail)
 */
@Composable
fun OsmMapView(
    currentLocation: LocationData?,
    drState: DrState?,
    modifier: Modifier = Modifier,
    followDr: Boolean = true
) {
    val context = LocalContext.current

    val mapView = remember {
        MapView(context).apply {
            setTileSource(STADIA_STYLE)
            setMultiTouchControls(true)
            controller.setZoom(18.0)
        }
    }

    val gnssMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = dotDrawable(0xFF2F80ED.toInt())
            title = "GNSS"
            isEnabled = false
        }
    }

    val drMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = dotDrawable(0xFFF2A93B.toInt())
            title = "Dead reckoning"
            isEnabled = false
        }
    }

    val snapMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = dotDrawable(0xFF35D07F.toInt())
            title = "Snapped to road"
            isEnabled = false
        }
    }

    val drTrail = remember {
        Polyline().apply {
            outlinePaint.color = 0xFFF2A93B.toInt()
            outlinePaint.strokeWidth = 6f
        }
    }

    val snapTrail = remember {
        Polyline().apply {
            outlinePaint.color = 0xFF35D07F.toInt()
            outlinePaint.strokeWidth = 6f
        }
    }

    val centred = remember { booleanArrayOf(false) }

    DisposableEffect(Unit) {
        mapView.onResume()
        mapView.overlays.add(drTrail)
        mapView.overlays.add(snapTrail)
        mapView.overlays.add(gnssMarker)
        mapView.overlays.add(drMarker)
        mapView.overlays.add(snapMarker)
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->

            currentLocation?.let {
                val p = GeoPoint(it.latitude, it.longitude)
                gnssMarker.position = p
                gnssMarker.isEnabled = true
                if (!centred[0]) {
                    map.controller.setCenter(p)
                    centred[0] = true
                }
            }

            if (drState != null && drState.anchored) {
                val p = GeoPoint(drState.latitude, drState.longitude)
                drMarker.position = p
                drMarker.rotation = -drState.heading
                drMarker.isEnabled = true

                val pts = drTrail.actualPoints
                if (pts.isEmpty() || pts.last().distanceToAsDouble(p) > 1.0) {
                    drTrail.addPoint(p)
                }

                if (followDr) {
                    map.controller.setCenter(p)
                    centred[0] = true
                }
            } else {
                drMarker.isEnabled = false
            }

            if (drState != null && drState.snapped) {
                val sp = GeoPoint(drState.snappedLat, drState.snappedLon)
                snapMarker.position = sp
                snapMarker.isEnabled = true

                val spts = snapTrail.actualPoints
                if (spts.isEmpty() || spts.last().distanceToAsDouble(sp) > 1.0) {
                    snapTrail.addPoint(sp)
                }
            } else {
                snapMarker.isEnabled = false
            }

            map.invalidate()
        }
    )
}

/** Small filled circle with a white ring, used as a marker icon. */
private fun dotDrawable(color: Int, sizePx: Int = 36): Drawable {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val r = sizePx / 2f

    canvas.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = 0xFFFFFFFF.toInt()
    })
    canvas.drawCircle(r, r, r - 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
    })

    return BitmapDrawable(null, bmp)
}