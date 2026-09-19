package com.example.cynos_kotlin

import android.app.Application
import android.content.Context
import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.example.cynos_kotlin.ml.DeadReckoningEngine
import com.example.cynos_kotlin.ml.FeatureScaler
import com.example.cynos_kotlin.ml.Geo
import com.example.cynos_kotlin.ml.HmmMapMatcher
import com.example.cynos_kotlin.ml.ImuSample
import com.example.cynos_kotlin.ml.OnnxRuntimeManager
import com.example.cynos_kotlin.ml.OverpassClient
import com.example.cynos_kotlin.ml.RoadGraph
import com.example.cynos_kotlin.ml.SensorBuffer
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.lifecycle.viewModelScope
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.location.GnssStatus
import android.location.LocationManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.PI

data class SensorData(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
)

data class OrientationData(
    val roll: Float = 0f,
    val pitch: Float = 0f,
    val yaw: Float = 0f
)

data class LocationData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val heading: Float = 0f
)

/** Everything the UI needs to draw the markers and the debug panel. */
data class DrState(
    val bufferFill: Int = 0,
    val running: Boolean = false,
    val anchored: Boolean = false,
    val gnssFresh: Boolean = false,
    val blackoutSimulated: Boolean = false,
    val heading: Float = 0f,
    val yawRateDps: Float = 0f,
    val velocity: Float = 0f,
    val east: Double = 0.0,
    val north: Double = 0.0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val gyroMs: Long = 0L,
    val dvseMs: Long = 0L,
    val sampleCount: Long = 0L,
    // --- road snapping ---
    val snapEnabled: Boolean = true,
    val roadsLoaded: Int = 0,
    val roadsLoading: Boolean = false,
    val snapped: Boolean = false,
    val snappedLat: Double = 0.0,
    val snappedLon: Double = 0.0,
    val snapOffsetM: Double = 0.0,
    val roadName: String = "--"
)

class SensorViewModel(
    application: Application
) : AndroidViewModel(application), SensorEventListener {

    companion object {
        private const val SAMPLE_PERIOD_NS = 100_000_000L   // 10 Hz
        private const val SENSOR_PERIOD_US = 20_000         // ask for ~50 Hz
        private const val DVSE_EVERY = 10                   // 1 Hz
        private const val GNSS_STALE_NS = 6_000_000_000L  // 6 s
        private const val ROAD_REFETCH_M = 500.0
        private const val TAG = "DR"
    }

    // -----------------------------------------------------------------
    // ANDROID PLUMBING
    // -----------------------------------------------------------------

    private val sensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val fusedLocationClient =
        LocationServices.getFusedLocationProviderClient(application)

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    /** Sensor callbacks + ONNX inference + matching. Never the main thread. */
    private val sensorThread = HandlerThread("cynos-imu").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    /** Overpass downloads, so a slow network never stalls the IMU loop. */
    private val netThread = HandlerThread("cynos-net").apply { start() }
    private val netHandler = Handler(netThread.looper)

    private val onnx = OnnxRuntimeManager(application)
    private val buffer = SensorBuffer(100)
    private val dr = DeadReckoningEngine()

    // -----------------------------------------------------------------
    // UI STATE
    // -----------------------------------------------------------------

    private val _accelerometerData = MutableStateFlow(SensorData())
    val accelerometerData: StateFlow<SensorData> = _accelerometerData.asStateFlow()

    private val _gyroscopeData = MutableStateFlow(SensorData())
    val gyroscopeData: StateFlow<SensorData> = _gyroscopeData.asStateFlow()

    private val _magnetometerData = MutableStateFlow(SensorData())
    val magnetometerData: StateFlow<SensorData> = _magnetometerData.asStateFlow()

    private val _orientationData = MutableStateFlow(OrientationData())
    val orientationData: StateFlow<OrientationData> = _orientationData.asStateFlow()

    private val _locationData = MutableStateFlow<LocationData?>(null)
    val locationData: StateFlow<LocationData?> = _locationData.asStateFlow()

    private val _drState = MutableStateFlow(DrState())
    val drState: StateFlow<DrState> = _drState.asStateFlow()

    private val _gravityData = MutableStateFlow(SensorData())
    val gravityData: StateFlow<SensorData> = _gravityData.asStateFlow()

    private val _satelliteCount = MutableStateFlow(0)
    val satelliteCount: StateFlow<Int> = _satelliteCount.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var recordingJob: Job? = null
    private var csvFile: File? = null
    private var startTimeMillis: Long = 0

    private val locationManager by lazy {
        getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            _satelliteCount.value = status.satelliteCount
        }
    }

    private val _locationPermissionGranted = MutableStateFlow(false)
    val locationPermissionGranted: StateFlow<Boolean> = _locationPermissionGranted.asStateFlow()

    fun setLocationPermissionGranted(granted: Boolean) {
        Log.d(TAG, "location permission granted = $granted")
        _locationPermissionGranted.value = granted
    }

    // -----------------------------------------------------------------
    // 10 Hz RESAMPLER STATE (sensorThread only)
    // -----------------------------------------------------------------

    private var ax = 0f; private var ay = 0f; private var az = 0f
    private var gx = 0f; private var gy = 0f; private var gz = 0f
    private var haveAccel = false
    private var haveGyro = false

    private var nextSampleNs = 0L
    private var sampleCount = 0L

    /** Reused every tick: no allocation inside the 10 Hz loop. */
    private val gyroWindow = Array(1) { Array(100) { FloatArray(3) } }
    private val accWindow = Array(1) { Array(100) { FloatArray(3) } }
    private val vrSeqTensor = Array(1) { Array(10) { FloatArray(1) } }
    private val vrHistory = FloatArray(10)
    private var vrIndex = 0

    private var lastGyroMs = 0L
    private var lastDvseMs = 0L

    // -----------------------------------------------------------------
    // GNSS STATE
    // -----------------------------------------------------------------

    @Volatile private var lastFixElapsedNs = 0L
    @Volatile private var lastGnssSpeed = 0f
    @Volatile private var lastGnssBearing = 0f
    @Volatile private var lastGnssLat = 0.0
    @Volatile private var lastGnssLon = 0.0
    @Volatile private var lastGnssAccuracy = Float.MAX_VALUE
    @Volatile private var pendingAnchor = false

    @Volatile private var simulateBlackout = false
    @Volatile private var gnssAidVelocity = true
    @Volatile private var autoReanchor = true

    fun setSimulatedBlackout(enabled: Boolean) {
        Log.d(TAG, "blackout simulation = $enabled")
        simulateBlackout = enabled
        if (!enabled) pendingAnchor = autoReanchor
    }

    fun setGnssAidVelocity(enabled: Boolean) { gnssAidVelocity = enabled }
    fun setAutoReanchor(enabled: Boolean) { autoReanchor = enabled }

    fun reanchorNow() {
        Log.d(TAG, "manual reanchor requested")
        pendingAnchor = true
    }

    private fun gnssFresh(): Boolean =
        !simulateBlackout &&
            lastFixElapsedNs != 0L &&
            (SystemClock.elapsedRealtimeNanos() - lastFixElapsedNs) < GNSS_STALE_NS

    // -----------------------------------------------------------------
    // ROAD SNAPPING STATE
    // -----------------------------------------------------------------

    @Volatile private var snapEnabled = true
    @Volatile private var roadGraph: RoadGraph? = null
    @Volatile private var matcher: HmmMapMatcher? = null
    @Volatile private var roadsLoading = false
    @Volatile private var graphCenterLat = 0.0
    @Volatile private var graphCenterLon = 0.0

    @Volatile private var snapped = false
    @Volatile private var snappedLat = 0.0
    @Volatile private var snappedLon = 0.0
    @Volatile private var snapOffsetM = 0.0
    @Volatile private var roadName = "--"

    /**
     * Viterbi snapping is an outage aid, not an always-on filter: it runs only
     * while GNSS is unavailable and switches itself off the moment a healthy
     * fix returns. Driven automatically from [applySnapPolicy]; there is no
     * manual toggle.
     */
    private fun applySnapPolicy(gnssHealthy: Boolean) {
        val want = !gnssHealthy
        if (want == snapEnabled) return

        snapEnabled = want
        matcher?.reset()

        if (want) {
            Log.d(TAG, "SNAP ON (GNSS lost)")
            if (roadGraph == null && dr.anchored) fetchRoads(dr.latitude(), dr.longitude())
        } else {
            snapped = false
            Log.d(TAG, "SNAP OFF (GNSS healthy)")
        }
    }

    /** Download the OSM road graph around the current position. */
    fun loadRoadsNow() {
        val lat = if (dr.anchored) dr.latitude() else lastGnssLat
        val lon = if (dr.anchored) dr.longitude() else lastGnssLon
        if (lat == 0.0 && lon == 0.0) {
            Log.w(TAG, "loadRoadsNow: no position yet")
            return
        }
        fetchRoads(lat, lon)
    }

    private fun fetchRoads(lat: Double, lon: Double) {
        if (roadsLoading) return
        roadsLoading = true
        publish()
        netHandler.post {
            val g = OverpassClient.fetchRoads(lat, lon, 1200)
            if (g != null && g.size() > 0) {
                roadGraph = g
                matcher = HmmMapMatcher(g)
                graphCenterLat = lat
                graphCenterLon = lon
                Log.d(TAG, "ROADS loaded: ${g.size()} segments around $lat,$lon")
            } else {
                Log.w(TAG, "ROADS load returned nothing")
            }
            roadsLoading = false
            publish()
        }
    }

    // -----------------------------------------------------------------
    // ORIENTATION
    // -----------------------------------------------------------------

    private var gravityValues = FloatArray(3)
    private var magneticValues = FloatArray(3)

    // -----------------------------------------------------------------
    // GNSS CALLBACK
    // -----------------------------------------------------------------

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation
            if (location == null) {
                Log.w(TAG, "GNSS callback with null location")
                return
            }

            Log.d(
                TAG,
                "GNSS fix lat=${"%.6f".format(location.latitude)} " +
                    "lon=${"%.6f".format(location.longitude)} " +
                    "acc=${"%.1f".format(location.accuracy)}m " +
                    "spd=${"%.2f".format(location.speed)}"
            )

            _locationData.value = LocationData(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                altitude = location.altitude,
                speed = location.speed,
                heading = location.bearing
            )

            if (simulateBlackout) {
                Log.d(TAG, "  (blackout simulated - fix discarded)")
                return
            }

            lastGnssLat = location.latitude
            lastGnssLon = location.longitude
            lastGnssAccuracy = location.accuracy
            if (location.hasSpeed()) lastGnssSpeed = location.speed
            if (location.hasBearing()) lastGnssBearing = location.bearing
            lastFixElapsedNs = SystemClock.elapsedRealtimeNanos()

            if (!dr.anchored && location.accuracy < 25f) {
                Log.d(TAG, "  -> queueing anchor")
                pendingAnchor = true
            }
        }
    }

    // -----------------------------------------------------------------
    // INIT
    // -----------------------------------------------------------------

    init {
        Log.d(TAG, "===== SensorViewModel INIT =====")
        Log.d(TAG, "accel=${accelerometer?.name} gyro=${gyroscope?.name} mag=${magnetometer?.name}")

        sensorHandler.post {
            Log.d(TAG, "ONNX: loading models on ${Thread.currentThread().name} ...")
            try {
                onnx.initialize()
                onnx.printModelInfo()
                Log.d(TAG, "ONNX: ready = ${onnx.isReady}")
            } catch (e: Exception) {
                Log.e(TAG, "ONNX: INIT FAILED", e)
            }
        }

        startSensors()
        Log.d(TAG, "===== INIT DONE =====")
    }

    private fun startSensors() {
        if (accelerometer == null) Log.e(TAG, "NO ACCELEROMETER ON THIS DEVICE")
        if (gyroscope == null) Log.e(TAG, "NO GYROSCOPE ON THIS DEVICE")

        accelerometer?.let {
            val ok = sensorManager.registerListener(this, it, SENSOR_PERIOD_US, sensorHandler)
            Log.d(TAG, "registerListener accel -> $ok")
        }
        gyroscope?.let {
            val ok = sensorManager.registerListener(this, it, SENSOR_PERIOD_US, sensorHandler)
            Log.d(TAG, "registerListener gyro -> $ok")
        }
        magnetometer?.let {
            val ok = sensorManager.registerListener(
                this, it, SensorManager.SENSOR_DELAY_UI, sensorHandler
            )
            Log.d(TAG, "registerListener mag -> $ok")
        }
        gravitySensor?.let {
            val ok = sensorManager.registerListener(
                this, it, SensorManager.SENSOR_DELAY_UI, sensorHandler
            )
            Log.d(TAG, "registerListener gravity -> $ok")
        }
    }

    fun startLocationUpdates() {
        Log.d(TAG, "startLocationUpdates()")
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(1000L)
                .build()

            fusedLocationClient.requestLocationUpdates(
                request, locationCallback, Looper.getMainLooper()
            )
            
            if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.registerGnssStatusCallback(gnssStatusCallback, null)
            }
            
            Log.d(TAG, "  requested, waiting for fixes")
            _locationPermissionGranted.value = true
        } catch (e: SecurityException) {
            Log.e(TAG, "startLocationUpdates: no permission", e)
            _locationPermissionGranted.value = false
        }
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        try { locationManager.unregisterGnssStatusCallback(gnssStatusCallback) } catch (e: Exception) {}
    }

    // -----------------------------------------------------------------
    // SENSOR CALLBACK (sensorThread)
    // -----------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                ax = event.values[0]; ay = event.values[1]; az = event.values[2]
                haveAccel = true
                _accelerometerData.value = SensorData(ax, ay, az)
                System.arraycopy(event.values, 0, gravityValues, 0, 3)
                updateOrientation()
                pump(event.timestamp)
            }

            Sensor.TYPE_GYROSCOPE -> {
                gx = event.values[0]; gy = event.values[1]; gz = event.values[2]
                haveGyro = true
                _gyroscopeData.value = SensorData(gx, gy, gz)
                pump(event.timestamp)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                _magnetometerData.value =
                    SensorData(event.values[0], event.values[1], event.values[2])
                System.arraycopy(event.values, 0, magneticValues, 0, 3)
                updateOrientation()
            }
            
            Sensor.TYPE_GRAVITY -> {
                _gravityData.value = SensorData(event.values[0], event.values[1], event.values[2])
                System.arraycopy(event.values, 0, gravityValues, 0, 3)
                updateOrientation()
            }
        }
    }

    /**
     * Zero-order-hold resampler onto a fixed 100 ms grid driven by the
     * monotonic hardware clock, so cadence never drifts with callback jitter.
     */
    private fun pump(eventTimeNs: Long) {
        if (!haveAccel || !haveGyro) return

        if (nextSampleNs == 0L) {
            nextSampleNs = eventTimeNs
            Log.d(TAG, "resampler started, both sensors live")
        }

        if (eventTimeNs - nextSampleNs > 5 * SAMPLE_PERIOD_NS) {
            Log.d(TAG, "resampler resync (gap detected)")
            nextSampleNs = eventTimeNs
        }

        while (eventTimeNs >= nextSampleNs) {
            emitSample(nextSampleNs)
            nextSampleNs += SAMPLE_PERIOD_NS
        }
    }

    private fun emitSample(tsNs: Long) {
        buffer.add(ImuSample(ax, ay, az, gx, gy, gz, tsNs))
        sampleCount++

        if (sampleCount <= 100 && sampleCount % 20 == 0L) {
            Log.d(
                TAG,
                "buffer filling: ${buffer.getSamples().size}/100 (onnxReady=${onnx.isReady})"
            )
        }

        val fresh = gnssFresh()
        applySnapPolicy(fresh)
        vrHistory[vrIndex] = if (fresh) lastGnssSpeed else dr.velocity
        vrIndex = (vrIndex + 1) % 10

        // ---- anchor from GNSS ----
        if (pendingAnchor && fresh) {
            val initialHeading = when {
                lastGnssSpeed > 1.5f -> lastGnssBearing
                dr.anchored -> dr.heading
                else -> compassHeadingDeg()
            }
            dr.anchor(lastGnssLat, lastGnssLon, initialHeading, lastGnssSpeed)
            pendingAnchor = false
            matcher?.reset()
            Log.d(TAG, "ANCHORED at $lastGnssLat,$lastGnssLon hdg=$initialHeading")

            // Prefetch the graph now, while GNSS is healthy and we still have
            // network certainty — a blackout is the worst time to discover the
            // roads haven't been downloaded yet.
            if (roadGraph == null) fetchRoads(lastGnssLat, lastGnssLon)
        }

        if (!buffer.isFull() || !onnx.isReady) {
            publish()
            return
        }

        val samples = buffer.getSamples()

        // ---- 10 Hz: gyro TCN -> heading ----
        try {
            for (i in 0 until 100) {
                val s = samples[i]
                val g = FeatureScaler.scaleGyroscope(s.gx, s.gy, s.gz)
                gyroWindow[0][i][0] = g[0]
                gyroWindow[0][i][1] = g[1]
                gyroWindow[0][i][2] = g[2]
            }
            val t0 = SystemClock.elapsedRealtime()
            val yawRateNorm = onnx.runGyroInference(gyroWindow)
            lastGyroMs = SystemClock.elapsedRealtime() - t0
            val dps = dr.onYawRate(yawRateNorm, 0.1f)

            if (sampleCount % 20 == 0L) {
                Log.d(
                    TAG,
                    "GYRO norm=${"%.5f".format(yawRateNorm)} " +
                        "dps=${"%.3f".format(dps)} " +
                        "hdg=${"%.1f".format(dr.heading)} (${lastGyroMs}ms)"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "gyro inference FAILED", e)
        }

        // ---- 1 Hz: DVSE -> velocity ----
        if (sampleCount % DVSE_EVERY == 0L) {
            try {
                for (i in 0 until 100) {
                    val s = samples[i]
                    val a = FeatureScaler.scaleAccelerometer(s.ax, s.ay, s.az)
                    accWindow[0][i][0] = a[0]
                    accWindow[0][i][1] = a[1]
                    accWindow[0][i][2] = a[2]
                }
                for (k in 0 until 10) {
                    vrSeqTensor[0][k][0] = vrHistory[(vrIndex + k) % 10]
                }

                val v0 = if (fresh && gnssAidVelocity) lastGnssSpeed else dr.velocity
                dr.setVelocity(v0)

                val t0 = SystemClock.elapsedRealtime()
                val deltaV = onnx.runDvseInference(accWindow, gyroWindow, vrSeqTensor, v0)
                lastDvseMs = SystemClock.elapsedRealtime() - t0
                dr.onDeltaV(deltaV)

                Log.d(
                    TAG,
                    "DVSE v0=${"%.2f".format(v0)} dV=${"%.4f".format(deltaV)} " +
                        "vel=${"%.2f".format(dr.velocity)} (${lastDvseMs}ms) gnssFresh=$fresh"
                )
            } catch (e: Exception) {
                Log.e(TAG, "dvse inference FAILED", e)
            }
        }

        // ---- integrate position ----
        dr.step(0.1)

        // ---- 1 Hz: Viterbi road snapping ----
        if (sampleCount % DVSE_EVERY == 0L && snapEnabled && dr.anchored) {
            runSnapping()
        }

        publish()
    }

    private fun runSnapping() {
        val lat = dr.latitude()
        val lon = dr.longitude()

        // Refetch the graph if we've wandered out of the loaded area.
        if (roadGraph != null && !roadsLoading) {
            val dx = (lon - graphCenterLon) * Geo.mPerDegLon(lat)
            val dy = (lat - graphCenterLat) * Geo.M_PER_DEG_LAT
            if (hypot(dx, dy) > ROAD_REFETCH_M) {
                Log.d(TAG, "left loaded road area, refetching graph")
                fetchRoads(lat, lon)
            }
        }

        val m = matcher ?: return
        val match = m.update(lat, lon)

        if (match == null) {
            snapped = false
            roadName = "off-road"
        } else {
            snapped = true
            snappedLat = match.lat
            snappedLon = match.lon
            snapOffsetM = match.offsetM
            roadName = match.roadName ?: "unnamed road"

            if (sampleCount % 50 == 0L) {
                Log.d(TAG, "SNAP -> $roadName offset=${"%.1f".format(match.offsetM)}m")
            }
        }
    }

    private fun publish() {
        _drState.value = DrState(
            bufferFill = buffer.getSamples().size,
            running = onnx.isReady && buffer.isFull(),
            anchored = dr.anchored,
            gnssFresh = gnssFresh(),
            blackoutSimulated = simulateBlackout,
            heading = dr.heading,
            yawRateDps = dr.yawRateDps,
            velocity = dr.velocity,
            east = dr.east,
            north = dr.north,
            latitude = dr.latitude(),
            longitude = dr.longitude(),
            gyroMs = lastGyroMs,
            dvseMs = lastDvseMs,
            sampleCount = sampleCount,
            snapEnabled = snapEnabled,
            roadsLoaded = roadGraph?.size() ?: 0,
            roadsLoading = roadsLoading,
            snapped = snapped,
            snappedLat = snappedLat,
            snappedLon = snappedLon,
            snapOffsetM = snapOffsetM,
            roadName = roadName
        )
    }

    // -----------------------------------------------------------------
    // ORIENTATION
    // -----------------------------------------------------------------

    private fun updateOrientation() {
        val rotationMatrix = FloatArray(9)
        val ok = SensorManager.getRotationMatrix(
            rotationMatrix, null, gravityValues, magneticValues
        )
        if (ok) {
            val o = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, o)
            _orientationData.value = OrientationData(yaw = o[0], pitch = o[1], roll = o[2])
        }
    }

    private fun compassHeadingDeg(): Float {
        var deg = Math.toDegrees(_orientationData.value.yaw.toDouble()).toFloat()
        if (deg < 0f) deg += 360f
        return deg
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // -----------------------------------------------------------------
    // CLEANUP
    // -----------------------------------------------------------------

    fun toggleRecording(onCompleted: (Uri?) -> Unit) {
        if (_isRecording.value) {
            // Stop Recording
            _isRecording.value = false
            recordingJob?.cancel()
            
            val uri = csvFile?.let { 
                FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.fileprovider", it)
            }
            onCompleted(uri)
        } else {
            // Start Recording
            _isRecording.value = true
            startTimeMillis = System.currentTimeMillis()
            csvFile = File(getApplication<Application>().cacheDir, "telemetry_${startTimeMillis}.csv")
            
            val header = "GPS LATITUDE (degrees),GPS LONGITUDE (degrees),GPS ALTITUDE (m),GPS SPEED (Kmh),GPS ACCURACY (m),GPS ORIENTATION (°),GPS SATELLITES IN RANGE,TIME SINCE START (ms),DATE (YYYY-MO-DD HH-MI-SS_SSS),ACCELEROMETER X (m/s²),ACCELEROMETER Y (m/s²),ACCELEROMETER Z (m/s²),GRAVITY X (m/s²),GRAVITY Y (m/s²),GRAVITY Z (m/s²),GYROSCOPE Yaw (rad/s),GYROSCOPE Pitch (rad/s),GYROSCOPE Roll (rad/s),MAGNETIC FIELD X (μT),MAGNETIC FIELD Y (μT),MAGNETIC FIELD Z (μT),ORIENTATION (Yaw) (°),ORIENTATION (Pitch) (°),ORIENTATION (Roll) (°)\n"
            
            try {
                FileWriter(csvFile, false).use { it.append(header) }
            } catch (e: Exception) { e.printStackTrace() }
            
            val sdf = SimpleDateFormat("yyyy-MM-dd HH-mm-ss_SSS", Locale.US)
            
            recordingJob = viewModelScope.launch {
                while (_isRecording.value) {
                    val loc = _locationData.value
                    val sat = _satelliteCount.value
                    val acc = _accelerometerData.value
                    val grav = _gravityData.value
                    val gyro = _gyroscopeData.value
                    val mag = _magnetometerData.value
                    val ori = _orientationData.value
                    
                    val timeSinceStart = System.currentTimeMillis() - startTimeMillis
                    val dateStr = sdf.format(Date())
                    val speedKmh = (loc?.speed ?: 0f) * 3.6f
                    
                    val row = buildString {
                        append("${loc?.latitude ?: ""},${loc?.longitude ?: ""},${loc?.altitude ?: ""},${speedKmh},${loc?.accuracy ?: ""},${loc?.heading ?: ""},${sat},")
                        append("${timeSinceStart},${dateStr},")
                        append("${acc.x},${acc.y},${acc.z},")
                        append("${grav.x},${grav.y},${grav.z},")
                        append("${gyro.x},${gyro.y},${gyro.z},")
                        append("${mag.x},${mag.y},${mag.z},")
                        append("${ori.yaw * 180 / PI},${ori.pitch * 180 / PI},${ori.roll * 180 / PI}\n")
                    }
                    
                    try {
                        FileWriter(csvFile, true).use { it.append(row) }
                    } catch (e: Exception) { e.printStackTrace() }
                    
                    delay(500) // Record every 500ms
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared")
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
        sensorHandler.post { try { onnx.close() } catch (_: Exception) {} }
        sensorThread.quitSafely()
        netThread.quitSafely()
    }
}