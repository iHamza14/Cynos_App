package com.example.cynos_kotlin.ml

import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Flat-earth conversion helpers. Fine over a few km. */
object Geo {
    const val M_PER_DEG_LAT = 111_320.0
    fun mPerDegLon(lat: Double): Double =
        M_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
}

/**
 * One straight piece of road between two OSM nodes, in local metres.
 */
class Segment(
    val x1: Double, val y1: Double,
    val x2: Double, val y2: Double,
    val n1: Long, val n2: Long,
    val name: String?,
    val highway: String?
) {
    val length: Double = hypot(x2 - x1, y2 - y1)
}

/** Result of projecting a point onto a segment. */
class Projection(
    val segIndex: Int,
    val x: Double,
    val y: Double,
    val dist: Double
)

/**
 * Road network in a local east/north metre frame anchored at (originLat, originLon).
 *
 * Holds a uniform grid index so "which roads are near this point" is O(1)-ish
 * instead of scanning every segment at 1 Hz.
 */
class RoadGraph(
    val originLat: Double,
    val originLon: Double
) {
    companion object {
        private const val CELL = 100.0          // grid cell size, metres
        private const val SAMPLE_STEP = 25.0    // how finely we stamp segments into cells
    }

    private val mPerLon = Geo.mPerDegLon(originLat)

    val segments = ArrayList<Segment>()

    /** cell key -> segment indices touching that cell */
    private val grid = HashMap<Long, MutableList<Int>>()

    /** OSM node id -> segment indices meeting at that node (connectivity) */
    private val nodeToSegs = HashMap<Long, MutableList<Int>>()

    // ---------------- coordinate conversion ----------------

    fun toX(lon: Double): Double = (lon - originLon) * mPerLon
    fun toY(lat: Double): Double = (lat - originLat) * Geo.M_PER_DEG_LAT
    fun toLon(x: Double): Double = originLon + x / mPerLon
    fun toLat(y: Double): Double = originLat + y / Geo.M_PER_DEG_LAT

    // ---------------- building ----------------

    fun addSegment(seg: Segment) {
        val idx = segments.size
        segments.add(seg)

        nodeToSegs.getOrPut(seg.n1) { ArrayList() }.add(idx)
        nodeToSegs.getOrPut(seg.n2) { ArrayList() }.add(idx)

        // Stamp the segment into every grid cell it passes through.
        val steps = max(1, (seg.length / SAMPLE_STEP).toInt())
        for (k in 0..steps) {
            val t = k.toDouble() / steps
            val px = seg.x1 + (seg.x2 - seg.x1) * t
            val py = seg.y1 + (seg.y2 - seg.y1) * t
            grid.getOrPut(cellKey(px, py)) { ArrayList() }.let {
                if (it.isEmpty() || it.last() != idx) it.add(idx)
            }
        }
    }

    private fun cellKey(x: Double, y: Double): Long {
        val cx = floor(x / CELL).toLong()
        val cy = floor(y / CELL).toLong()
        return (cx shl 32) xor (cy and 0xffffffffL)
    }

    // ---------------- querying ----------------

    /** Segment indices whose grid cells intersect a box of +/- radius around (x,y). */
    fun nearbySegments(x: Double, y: Double, radius: Double): List<Int> {
        val out = LinkedHashSet<Int>()
        val cx0 = floor((x - radius) / CELL).toLong()
        val cx1 = floor((x + radius) / CELL).toLong()
        val cy0 = floor((y - radius) / CELL).toLong()
        val cy1 = floor((y + radius) / CELL).toLong()
        var cx = cx0
        while (cx <= cx1) {
            var cy = cy0
            while (cy <= cy1) {
                grid[(cx shl 32) xor (cy and 0xffffffffL)]?.let { out.addAll(it) }
                cy++
            }
            cx++
        }
        return out.toList()
    }

    /** Perpendicular projection of (px,py) onto segment idx, clamped to the segment. */
    fun project(px: Double, py: Double, idx: Int): Projection {
        val s = segments[idx]
        val dx = s.x2 - s.x1
        val dy = s.y2 - s.y1
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-9) 0.0
        else (((px - s.x1) * dx + (py - s.y1) * dy) / len2).coerceIn(0.0, 1.0)
        val qx = s.x1 + dx * t
        val qy = s.y1 + dy * t
        return Projection(idx, qx, qy, hypot(px - qx, py - qy))
    }

    /** True if two segments meet at a shared OSM node (or are the same segment). */
    fun connected(a: Int, b: Int): Boolean {
        if (a == b) return true
        val sa = segments[a]
        val sb = segments[b]
        return sa.n1 == sb.n1 || sa.n1 == sb.n2 || sa.n2 == sb.n1 || sa.n2 == sb.n2
    }

    fun size(): Int = segments.size
}

/**
 * Downloads the road network around a point from the Overpass API.
 *
 * Blocking. Call this from a background thread only.
 */
object OverpassClient {

    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private const val TAG = "DR"

    /** Highway values we don't want to snap a vehicle/pedestrian track onto. */
    private val EXCLUDED = setOf("steps", "elevator", "proposed", "construction")

    fun fetchRoads(lat: Double, lon: Double, radiusM: Int = 1200): RoadGraph? {
        val query = """
            [out:json][timeout:30];
            way["highway"](around:$radiusM,$lat,$lon);
            (._;>;);
            out body;
        """.trimIndent()

        return try {
            android.util.Log.d(TAG, "Overpass: requesting roads within ${radiusM}m of $lat,$lon")

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "CynosDR/1.0")
            }

            OutputStreamWriter(conn.outputStream).use { w ->
                w.write("data=" + java.net.URLEncoder.encode(query, "UTF-8"))
            }

            val code = conn.responseCode
            if (code != 200) {
                android.util.Log.e(TAG, "Overpass HTTP $code")
                conn.disconnect()
                return null
            }

            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            conn.disconnect()

            parse(body, lat, lon)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Overpass fetch failed", e)
            null
        }
    }

    private fun parse(json: String, originLat: Double, originLon: Double): RoadGraph {
        val graph = RoadGraph(originLat, originLon)
        val root = JSONObject(json)
        val elements = root.getJSONArray("elements")

        // Pass 1: node id -> lat/lon
        val nodeLat = HashMap<Long, Double>()
        val nodeLon = HashMap<Long, Double>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            if (e.optString("type") == "node") {
                val id = e.getLong("id")
                nodeLat[id] = e.getDouble("lat")
                nodeLon[id] = e.getDouble("lon")
            }
        }

        // Pass 2: ways -> segments
        var ways = 0
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            if (e.optString("type") != "way") continue

            val tags = e.optJSONObject("tags") ?: continue
            val highway = tags.optString("highway", null) ?: continue
            if (highway in EXCLUDED) continue
            val name = tags.optString("name", null)

            val refs = e.optJSONArray("nodes") ?: continue
            ways++

            for (k in 0 until refs.length() - 1) {
                val a = refs.getLong(k)
                val b = refs.getLong(k + 1)
                val alat = nodeLat[a] ?: continue
                val alon = nodeLon[a] ?: continue
                val blat = nodeLat[b] ?: continue
                val blon = nodeLon[b] ?: continue

                graph.addSegment(
                    Segment(
                        x1 = graph.toX(alon), y1 = graph.toY(alat),
                        x2 = graph.toX(blon), y2 = graph.toY(blat),
                        n1 = a, n2 = b,
                        name = name, highway = highway
                    )
                )
            }
        }

        android.util.Log.d(TAG, "Overpass: parsed $ways ways -> ${graph.size()} segments")
        return graph
    }
}