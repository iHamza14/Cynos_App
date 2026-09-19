package com.example.cynos_kotlin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.pow

/**
 * ============================================================
 * PUT YOUR STADIA MAPS KEY HERE
 * Get one free at https://client.stadiamaps.com/signup
 * ============================================================
 */
private const val STADIA_API_KEY = "0809d2df-bb7d-43b0-a6ba-918cdaf2af80"

/** Marker colours, shared with the legend in MainActivity. */
val COLOR_GNSS = 0xFF2F80ED.toInt()   // blue  - ground truth
val COLOR_DR = 0xFFF2A93B.toInt()     // amber - dead reckoning
val COLOR_SNAP = 0xFF35D07F.toInt()   // green - Viterbi snapped

/**
 * osmdroid rotates markers counter-clockwise, so a compass bearing needs
 * negating. If your arrows point the wrong way on device, flip this to +1.
 */
private const val ROTATION_SIGN = -1f

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
 * Three independent arrows:
 *   blue  = raw GNSS fix (ground truth)
 *   amber = dead-reckoning estimate
 *   green = Viterbi road-snapped position (only during GNSS outage)
 *
 * The map pans and zooms freely. It follows the track until you touch it,
 * then leaves you alone until [recenterTick] changes.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun OsmMapView(
    currentLocation: LocationData?,
    drState: DrState?,
    recenterTick: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    /** Follow the track until the user drags, then hand them control. */
    val follow = remember { booleanArrayOf(true) }
    val lastTick = remember { intArrayOf(-1) }
    val lastIconPx = remember { intArrayOf(-1) }
    val lastGnssBearing = remember { floatArrayOf(0f) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(STADIA_STYLE)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(18.0)
            // Any touch means the user wants to look somewhere else.
            // Returning false leaves normal gesture handling intact.
            setOnTouchListener { _, _ ->
                follow[0] = false
                false
            }
        }
    }

    val gnssMarker = remember { plainMarker(mapView, "GNSS") }
    val drMarker = remember { plainMarker(mapView, "Dead reckoning") }
    val snapMarker = remember { plainMarker(mapView, "Snapped to road") }

    val gnssTrail = remember { trail(COLOR_GNSS, 4f) }
    val drTrail = remember { trail(COLOR_DR, 6f) }
    val snapTrail = remember { trail(COLOR_SNAP, 6f) }

    DisposableEffect(Unit) {
        mapView.onResume()
        mapView.overlays.add(gnssTrail)
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

            // ---- resize the arrows to match the current zoom ----
        val px = 140

if (px != lastIconPx[0]) {
    lastIconPx[0] = px
    gnssMarker.icon = arrowDrawable(COLOR_GNSS, px)
    drMarker.icon = arrowDrawable(COLOR_DR, px)
    snapMarker.icon = arrowDrawable(COLOR_SNAP, px)
}

            // ---- GNSS ----
            var gnssPoint: GeoPoint? = null
            currentLocation?.let {
                val p = GeoPoint(it.latitude, it.longitude)
                gnssPoint = p
                gnssMarker.position = p
                // A parked phone reports bearing 0, which would swing the arrow
                // to north for no reason. Only believe it while actually moving.
                if (it.speed > 0.5f) lastGnssBearing[0] = it.heading
                gnssMarker.rotation = ROTATION_SIGN * lastGnssBearing[0]
                gnssMarker.isEnabled = true
                addTrailPoint(gnssTrail, p)
            }

            
            // ---- dead reckoning prediction ----
// Show orange ONLY while GNSS is healthy.
var drPoint: GeoPoint? = null

if (drState != null &&
    drState.anchored &&
    drState.gnssFresh
) {
    val p = GeoPoint(drState.latitude, drState.longitude)
    drPoint = p

    drMarker.position = p
    drMarker.rotation = ROTATION_SIGN * drState.heading
    drMarker.isEnabled = true

    addTrailPoint(drTrail, p)
} else {
    drMarker.isEnabled = false
}

          // ---- Viterbi snapped ----
// Show green ONLY while GNSS is unavailable.
if (drState != null &&
    !drState.gnssFresh &&
    drState.snapped
) {
    val p = GeoPoint(
        drState.snappedLat,
        drState.snappedLon
    )

    snapMarker.position = p
    snapMarker.rotation = ROTATION_SIGN * drState.heading
    snapMarker.isEnabled = true

    addTrailPoint(snapTrail, p)
} else {
    snapMarker.isEnabled = false
}

            // ---- camera ----
            if (recenterTick != lastTick[0]) {
                lastTick[0] = recenterTick
                follow[0] = true
                (gnssPoint ?: drPoint)?.let { map.controller.animateTo(it) }
            } else if (follow[0]) {
                (drPoint ?: gnssPoint)?.let { map.controller.setCenter(it) }
            }

            map.invalidate()
        }
    )
}

/* ---------------------------------------------------------
 * MARKER / OVERLAY HELPERS
 * --------------------------------------------------------- */

private fun plainMarker(map: MapView, label: String) = Marker(map).apply {
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    title = label
    isEnabled = false
}

private fun trail(color: Int, width: Float) = Polyline().apply {
    outlinePaint.color = color
    outlinePaint.strokeWidth = width
    outlinePaint.isAntiAlias = true
}

/** Append to a trail, skipping sub-metre jitter and capping total length. */
private fun addTrailPoint(line: Polyline, p: GeoPoint) {
    val pts = line.actualPoints
    if (pts.isNotEmpty() && pts.last().distanceToAsDouble(p) <= 1.0) return
    if (pts.size > 3000) return
    line.addPoint(p)
}

/**
 * Arrow size in pixels for a zoom level: 36 px at z18, doubling every three
 * levels, clamped so it never vanishes or swallows the screen. Quantised to
 * 4 px steps so we regenerate bitmaps on real zoom changes, not every frame.
 */
private fun arrowPxForZoom(zoom: Double): Int {
    val raw = 76.0 * 2.0.pow((zoom - 18.0) / 3.0)
    val clamped = raw.coerceIn(56.0, 144.0)
    return ((clamped / 4.0).toInt()) * 4
}

/** Chevron pointing up (north at rotation 0), filled with a white outline. */
private fun arrowDrawable(color: Int, sizePx: Int): Drawable {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val n = sizePx.toFloat()

    val path = Path().apply {
        moveTo(n * 0.50f, n * 0.06f)   // tip
        lineTo(n * 0.88f, n * 0.92f)   // right wing
        lineTo(n * 0.50f, n * 0.68f)   // tail notch
        lineTo(n * 0.12f, n * 0.92f)   // left wing
        close()
    }

    // White outline first so the arrow reads against both light roads and dark parks.
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (n * 0.14f).coerceAtLeast(3f)
        strokeJoin = Paint.Join.ROUND
        this.color = 0xFFFFFFFF.toInt()
    })

    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    })

    return BitmapDrawable(null, bmp)
}