package com.example.cynos_kotlin

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cynos_kotlin.ui.theme.Cynos_KotlinTheme
import kotlin.math.PI

class MainActivity : ComponentActivity() {

    private lateinit var sensorViewModel: SensorViewModel

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

            sensorViewModel.setLocationPermissionGranted(fine || coarse)
            if (fine || coarse) sensorViewModel.startLocationUpdates()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid config must happen before any MapView is created.
        val prefs = android.preference.PreferenceManager
            .getDefaultSharedPreferences(this)

        org.osmdroid.config.Configuration.getInstance().apply {
            load(this@MainActivity, prefs)
            // A real, unique UA. Generic ones get 403'd by OSM's servers.
            userAgentValue = packageName
            osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        }

        sensorViewModel =
            androidx.lifecycle.ViewModelProvider(this)[SensorViewModel::class.java]

        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

        setContent {
            Cynos_KotlinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0b0f14)
                ) {
                    DashboardScreen(sensorViewModel)
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: SensorViewModel) {

    val accelerometer by viewModel.accelerometerData.collectAsState()
    val gyroscope by viewModel.gyroscopeData.collectAsState()
    val magnetometer by viewModel.magnetometerData.collectAsState()
    val orientation by viewModel.orientationData.collectAsState()
    val location by viewModel.locationData.collectAsState()
    val drState by viewModel.drState.collectAsState()

    var showDeviceData by remember { mutableStateOf(false) }

    if (showDeviceData) {
        DeviceDataScreen(
            location = location,
            drState = drState,
            accelerometer = accelerometer,
            gyroscope = gyroscope,
            magnetometer = magnetometer,
            orientation = orientation,
            viewModel = viewModel,
            onBackToMap = { showDeviceData = false }
        )
    } else {
        MapHomeScreen(
            location = location,
            drState = drState,
            onOpenDeviceData = { showDeviceData = true }
        )
    }
}

/* ---------------------------------------------------------
 * MAP HOME SCREEN
 * --------------------------------------------------------- */

@Composable
fun MapHomeScreen(
    location: LocationData?,
    drState: DrState,
    onOpenDeviceData: () -> Unit
) {
    val status = when {
        drState.blackoutSimulated -> "GNSS blackout (DR only)"
        location == null -> "Waiting for GPS"
        drState.running -> "DR running"
        else -> "GPS active - buffer ${drState.bufferFill}/100"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0b0f14))
    ) {
        OsmMapView(
            currentLocation = location,
            drState = drState,
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Cynos", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(status, fontSize = 13.sp, color = Color.White)
            }

            Button(
                onClick = onOpenDeviceData,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2B2F33),
                    contentColor = Color.White
                )
            ) {
                Text("Device Data")
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xEE151B22)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MapStat("DR speed", String.format("%.1f m/s", drState.velocity))
                MapStat("DR heading", String.format("%.0f°", drState.heading))
                MapStat("GPS acc", location?.let { String.format("%.0f m", it.accuracy) } ?: "--")
                MapStat("Road", if (drState.snapped) drState.roadName.take(10) else "--")
            }
        }
    }
}

@Composable
fun MapStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = Color(0xFF8D99A6))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/* ---------------------------------------------------------
 * DEVICE DATA SCREEN
 * --------------------------------------------------------- */

@Composable
fun DeviceDataScreen(
    location: LocationData?,
    drState: DrState,
    accelerometer: SensorData,
    gyroscope: SensorData,
    magnetometer: SensorData,
    orientation: OrientationData,
    viewModel: SensorViewModel,
    onBackToMap: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0b0f14))
            .verticalScroll(rememberScrollState())
            .padding(top = 60.dp, bottom = 40.dp, start = 16.dp, end = 16.dp)
    ) {
        Button(
            onClick = onBackToMap,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2B2F33),
                contentColor = Color.White
            )
        ) {
            Text("← Back to Map")
        }

        Spacer(Modifier.height(20.dp))

        Text("Device Data", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Live device telemetry",
            fontSize = 15.sp,
            color = Color(0xFF8d99a6),
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )

        /* ---------- DEAD RECKONING ---------- */

        SensorCard(
            title = "Dead Reckoning",
            status = when {
                drState.running -> "Running"
                drState.bufferFill < 100 -> "Buffering"
                else -> "Idle"
            }
        ) {
            SensorGrid {
                SensorValue("Buffer", "${drState.bufferFill}/100")
                SensorValue("Anchored", if (drState.anchored) "Yes" else "No")
                SensorValue("GNSS fresh", if (drState.gnssFresh) "Yes" else "No")
                SensorValue("Heading", String.format("%.1f°", drState.heading))
                SensorValue("Yaw rate", String.format("%.3f dps", drState.yawRateDps))
                SensorValue("Velocity", String.format("%.2f m/s", drState.velocity))
                SensorValue("East", String.format("%.1f m", drState.east))
                SensorValue("North", String.format("%.1f m", drState.north))
                SensorValue("Samples", "${drState.sampleCount}")
                SensorValue("DR lat", String.format("%.6f", drState.latitude))
                SensorValue("DR lon", String.format("%.6f", drState.longitude))
                SensorValue("Inference", "${drState.gyroMs}/${drState.dvseMs} ms")
            }
        }

        /* ---------- ROAD SNAPPING ---------- */

        SensorCard(
            title = "Road Snapping (Viterbi)",
            status = when {
                drState.roadsLoading -> "Downloading"
                drState.roadsLoaded == 0 -> "No graph"
                drState.snapped -> "Matched"
                else -> "Off-road"
            }
        ) {
            SensorGrid {
                SensorValue("Enabled", if (drState.snapEnabled) "Yes" else "No")
                SensorValue("Segments", "${drState.roadsLoaded}")
                SensorValue("Offset", String.format("%.1f m", drState.snapOffsetM))
                SensorValue("Road", drState.roadName)
                SensorValue("Snap lat", String.format("%.6f", drState.snappedLat))
                SensorValue("Snap lon", String.format("%.6f", drState.snappedLon))
            }
        }

        /* ---------- DEBUG CONTROLS ---------- */

        Text(
            "Debug controls",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.setSimulatedBlackout(!drState.blackoutSimulated) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor =
                        if (drState.blackoutSimulated) Color(0xFF8B2F2F) else Color(0xFF2B2F33),
                    contentColor = Color.White
                )
            ) {
                Text(if (drState.blackoutSimulated) "Blackout ON" else "Blackout OFF")
            }

            Button(
                onClick = { viewModel.reanchorNow() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2B2F33),
                    contentColor = Color.White
                )
            ) {
                Text("Reanchor")
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.loadRoadsNow() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2B2F33),
                    contentColor = Color.White
                )
            ) {
                Text("Load roads")
            }

            Button(
                onClick = { viewModel.setSnapEnabled(!drState.snapEnabled) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor =
                        if (drState.snapEnabled) Color(0xFF2F6B4F) else Color(0xFF2B2F33),
                    contentColor = Color.White
                )
            ) {
                Text(if (drState.snapEnabled) "Snap ON" else "Snap OFF")
            }
        }

        Spacer(Modifier.height(20.dp))

        /* ---------- GPS ---------- */

        SensorCard(
            title = "GPS / Location",
            status = if (location != null) "GPS Active" else "Waiting for GPS"
        ) {
            SensorGrid {
                SensorValue("Latitude", location?.let { String.format("%.6f", it.latitude) } ?: "--")
                SensorValue("Longitude", location?.let { String.format("%.6f", it.longitude) } ?: "--")
                SensorValue("Accuracy", location?.let { String.format("%.1f m", it.accuracy) } ?: "--")
                SensorValue("Altitude", location?.let { String.format("%.1f m", it.altitude) } ?: "--")
                SensorValue("Speed", location?.let { String.format("%.2f m/s", it.speed) } ?: "--")
                SensorValue("Heading", location?.let { String.format("%.1f°", it.heading) } ?: "--")
            }
        }

        SensorCard(title = "Accelerometer") {
            SensorGrid {
                SensorValue("X", String.format("%.2f m/s²", accelerometer.x))
                SensorValue("Y", String.format("%.2f m/s²", accelerometer.y))
                SensorValue("Z", String.format("%.2f m/s²", accelerometer.z))
            }
        }

        SensorCard(title = "Gyroscope") {
            SensorGrid {
                SensorValue("X", String.format("%.4f rad/s", gyroscope.x))
                SensorValue("Y", String.format("%.4f rad/s", gyroscope.y))
                SensorValue("Z", String.format("%.4f rad/s", gyroscope.z))
            }
        }

        SensorCard(title = "Magnetometer") {
            SensorGrid {
                SensorValue("X", String.format("%.2f µT", magnetometer.x))
                SensorValue("Y", String.format("%.2f µT", magnetometer.y))
                SensorValue("Z", String.format("%.2f µT", magnetometer.z))
            }
        }

        SensorCard(title = "Orientation") {
            SensorGrid {
                SensorValue("Roll", String.format("%.1f°", orientation.roll * 180 / PI))
                SensorValue("Pitch", String.format("%.1f°", orientation.pitch * 180 / PI))
                SensorValue("Yaw", String.format("%.1f°", orientation.yaw * 180 / PI))
            }
        }

        Text(
            "Codeteymons • SIH 2026  ·  Map © OpenStreetMap contributors © CARTO",
            fontSize = 11.sp,
            color = Color(0xFF59636e),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        )
    }
}

/* ---------------------------------------------------------
 * REUSABLE BITS
 * --------------------------------------------------------- */

@Composable
fun SensorCard(
    title: String,
    status: String? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .background(Color(0xFF151b22), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF242c36), RoundedCornerShape(14.dp))
            .padding(18.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

                if (status != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF35d07f), CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(status, fontSize = 12.sp, color = Color(0xFF9ba7b4))
                    }
                }
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SensorGrid(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        maxItemsInEachRow = 3
    ) {
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRowScope.SensorValue(label: String, value: String) {
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(bottom = 16.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = Color(0xFF7f8a96),
            modifier = Modifier.padding(bottom = 5.dp)
        )
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}