package com.example.cynos_kotlin.ml

import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure dead-reckoning state machine.
 *
 * Knows nothing about Android, sensors, ONNX or the map.
 * You feed it model outputs, it gives you heading / velocity / position.
 *
 * Conventions:
 *   heading  = degrees, 0 = North, increasing clockwise (compass convention)
 *   velocity = m/s along heading
 *   east/north = metres from the anchor point (local tangent plane)
 */
class DeadReckoningEngine {

    companion object {
        // Gyro TCN de-normalisation (from the Python reference)
        const val YAW_SCALE = 5.216348171234131f
        const val YAW_BIAS = 0.21026045083999634f

        /** Metres per degree of latitude (good to ~0.1% anywhere). */
        private const val M_PER_DEG_LAT = 111_320.0
    }

    @Volatile var heading: Float = 0f
        private set

    @Volatile var yawRateDps: Float = 0f
        private set

    @Volatile var velocity: Float = 0f
        private set

    @Volatile var east: Double = 0.0
        private set

    @Volatile var north: Double = 0.0
        private set

    @Volatile var anchored: Boolean = false
        private set

    private var originLat: Double = 0.0
    private var originLon: Double = 0.0
    private var mPerDegLon: Double = M_PER_DEG_LAT

    // -----------------------------------------------------------------
    // ANCHORING (called from GNSS)
    // -----------------------------------------------------------------

    /**
     * Reset the DR origin to a known lat/lon.
     * Position error goes to zero; heading/velocity are only overwritten
     * if you pass non-null values.
     */
    @Synchronized
    fun anchor(
        lat: Double,
        lon: Double,
        headingDeg: Float? = null,
        speedMps: Float? = null
    ) {
        originLat = lat
        originLon = lon
        mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
        east = 0.0
        north = 0.0
        headingDeg?.let { heading = wrap(it) }
        speedMps?.let { velocity = it }
        anchored = true
    }

    @Synchronized
    fun setHeading(deg: Float) {
        heading = wrap(deg)
    }

    @Synchronized
    fun setVelocity(mps: Float) {
        velocity = mps
    }

    // -----------------------------------------------------------------
    // 10 Hz: gyro TCN output -> heading
    // -----------------------------------------------------------------

    /**
     * @param yawRateNorm raw gyro_tcn output, yaw_rate_norm[0][99]
     * @param dtSec       time since previous gyro tick (nominally 0.1)
     */
    @Synchronized
    fun onYawRate(yawRateNorm: Float, dtSec: Float = 0.1f): Float {
        yawRateDps = yawRateNorm * YAW_SCALE + YAW_BIAS
        heading = wrap(heading + yawRateDps * dtSec)
        return yawRateDps
    }

    // -----------------------------------------------------------------
    // 1 Hz: DVSE output -> velocity
    // -----------------------------------------------------------------

    /**
     * @param deltaV raw dvse output, delta_v[0][9]
     */
    @Synchronized
    fun onDeltaV(deltaV: Float) {
        velocity += deltaV
        // A vehicle cannot reverse in this formulation; negative velocity
        // would flip the position 180 degrees and wreck the track.
        if (velocity < 0f) velocity = 0f
    }

    // -----------------------------------------------------------------
    // 10 Hz: integrate position
    // -----------------------------------------------------------------

    /**
     * Integrate one IMU tick. Called at 10 Hz with dtSec = 0.1 so the
     * heading used is always the freshest one (mathematically equivalent
     * to a 1 Hz step when heading is constant, but much better in turns).
     */
    @Synchronized
    fun step(dtSec: Double = 0.1) {
        if (!anchored) return
        val d = velocity * dtSec
        val rad = Math.toRadians(heading.toDouble())
        north += d * cos(rad)
        east += d * sin(rad)
    }

    // -----------------------------------------------------------------
    // OUTPUT
    // -----------------------------------------------------------------

    @Synchronized
    fun latitude(): Double = originLat + north / M_PER_DEG_LAT

    @Synchronized
    fun longitude(): Double = originLon + east / mPerDegLon

    @Synchronized
    fun reset() {
        heading = 0f
        yawRateDps = 0f
        velocity = 0f
        east = 0.0
        north = 0.0
        anchored = false
    }

    private fun wrap(deg: Float): Float {
        var h = deg % 360f
        if (h < 0f) h += 360f
        return h
    }
}