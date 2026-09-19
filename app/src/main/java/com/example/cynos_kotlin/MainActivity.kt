package com.example.cynos_kotlin

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cynos_kotlin.ui.theme.Cynos_KotlinTheme

/* ---------------------------------------------------------
 * PALETTE
 * --------------------------------------------------------- */

private val BG = Color(0xFF0B0F14)
private val PANEL = Color(0xFF151B22)
private val PANEL_HI = Color(0xFF1C242E)
private val STROKE = Color(0xFF242C36)
private val TEXT_DIM = Color(0xFF8D99A6)
private val TEXT_FAINT = Color(0xFF5E6975)
private val ACCENT_RED = Color(0xFFCB4B4B)
private val ACCENT_GREEN = Color(0xFF35D07F)
private val ACCENT_AMBER = Color(0xFFF2A93B)
private val ACCENT_BLUE = Color(0xFF2F80ED)

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
                Surface(modifier = Modifier.fillMaxSize(), color = BG) {
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
    val location by viewModel.locationData.collectAsState()
    val drState by viewModel.drState.collectAsState()

    var showDeviceData by remember { mutableStateOf(false) }

    if (showDeviceData) {
        DeviceDataScreen(
            location = location,
            drState = drState,
            accelerometer = accelerometer,
            gyroscope = gyroscope,
            viewModel = viewModel,
            onBackToMap = { showDeviceData = false }
        )
    } else {
        MapHomeScreen(
            location = location,
            drState = drState,
            viewModel = viewModel,
            onOpenDeviceData = { showDeviceData = true }
        )
    }
}

/* ---------------------------------------------------------
 * MAP HOME
 * --------------------------------------------------------- */

@Composable
fun MapHomeScreen(
    location: LocationData?,
    drState: DrState,
    viewModel: SensorViewModel,
    onOpenDeviceData: () -> Unit
) {
    var panelOpen by remember { mutableStateOf(false) }
    var recenterTick by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val isRecording by viewModel.isRecording.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        OsmMapView(
            currentLocation = location,
            drState = drState,
            recenterTick = recenterTick,
            modifier = Modifier.fillMaxSize()
        )

        /* ---- top bar ---- */
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            StatusPill(drState = drState, hasFix = location != null)

            IconSurface(onClick = { panelOpen = true }) {
                MenuGlyph()
            }
        }

        /* ---- recenter / reanchor, only useful with a live fix ---- */
        if (drState.gnssFresh) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 190.dp)
            ) {
                IconSurface(
                    size = 52.dp,
                    onClick = {
                        viewModel.reanchorNow()
                        recenterTick++
                    }
                ) {
                    CrosshairGlyph()
                }
            }
        }

        /* ---- bottom readout ---- */
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(14.dp),
            shape = RoundedCornerShape(18.dp),
            color = PANEL.copy(alpha = 0.94f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MapStat("Speed", String.format("%.1f", drState.velocity), "m/s")
                    MapStat("Heading", headingToCardinal(drState.heading), "${drState.heading.toInt()}°")
                    MapStat(
                        "GPS",
                        location?.let { String.format("%.0f", it.accuracy) } ?: "--",
                        if (location != null) "m acc" else "no fix"
                    )
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = STROKE)
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    LegendChip(ACCENT_BLUE, "GNSS")
                    LegendChip(ACCENT_AMBER, "Dead reckoning")
                    LegendChip(ACCENT_GREEN, "Viterbi", dim = !drState.snapped)
                }
            }
        }

        /* ---- side panel ---- */
        AnimatedVisibility(
            visible = panelOpen,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { panelOpen = false }
            )
        }

        AnimatedVisibility(
            modifier = Modifier.align(Alignment.CenterEnd),
            visible = panelOpen,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it }
        ) {
            ControlPanel(
                drState = drState,
                viewModel = viewModel,
                onClose = { panelOpen = false },
                onOpenDeviceData = {
                    panelOpen = false
                    onOpenDeviceData()
                }
            )
        }
    }
}

/* ---------------------------------------------------------
 * SIDE CONTROL PANEL
 * --------------------------------------------------------- */

@Composable
private fun ControlPanel(
    drState: DrState,
    viewModel: SensorViewModel,
    onClose: () -> Unit,
    onOpenDeviceData: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(292.dp)
            .background(PANEL)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Controls", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            IconSurface(size = 36.dp, color = PANEL_HI, onClick = onClose) { CloseGlyph() }
        }

        Spacer(Modifier.height(22.dp))

        BlackoutToggle(drState = drState, viewModel = viewModel)

        Spacer(Modifier.height(10.dp))

        Text(
            if (drState.snapped)
                "Viterbi is correcting the track onto ${drState.roadName}."
            else if (drState.snapEnabled)
                "Viterbi is armed, waiting for a road match."
            else
                "Viterbi engages automatically when GNSS drops.",
            fontSize = 12.sp,
            color = TEXT_DIM,
            lineHeight = 17.sp
        )

        Spacer(Modifier.height(22.dp))
        HorizontalDivider(color = STROKE)
        Spacer(Modifier.height(22.dp))

        PanelButton(
            label = "Reanchor to GNSS",
            sub = if (drState.gnssFresh) "Snap DR back onto the live fix" else "Needs a live fix",
            enabled = drState.gnssFresh,
            onClick = { viewModel.reanchorNow() }
        )

        Spacer(Modifier.height(10.dp))

        PanelButton(
            label = if (drState.roadsLoading) "Loading roads…" else "Load road graph",
            sub = when {
                drState.roadsLoading -> "Downloading from Overpass"
                drState.roadsLoaded > 0 -> "${drState.roadsLoaded} segments cached"
                else -> "No graph loaded yet"
            },
            enabled = !drState.roadsLoading,
            onClick = { viewModel.loadRoadsNow() }
        )

        Spacer(Modifier.height(10.dp))

        PanelButton(
            label = "Device data",
            sub = "Sensors, DR internals, timings",
            onClick = onOpenDeviceData
        )

        Spacer(Modifier.height(10.dp))

        val isRecording by viewModel.isRecording.collectAsState()
        val context = androidx.compose.ui.platform.LocalContext.current
        PanelButton(
            label = if (isRecording) "Stop & Share CSV" else "Record CSV",
            sub = if (isRecording) "Recording telemetry..." else "Save data to a file",
            onClick = {
                viewModel.toggleRecording { uri ->
                    if (uri != null) {
                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Telemetry CSV"))
                    }
                }
            }
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "© OpenStreetMap contributors · Stadia Maps",
            fontSize = 10.sp,
            color = TEXT_FAINT,
            lineHeight = 14.sp
        )
    }
}

@Composable
private fun BlackoutToggle(drState: DrState, viewModel: SensorViewModel) {
    val on = drState.blackoutSimulated

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (on) ACCENT_RED.copy(alpha = 0.16f) else PANEL_HI,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, if (on) ACCENT_RED else STROKE
        ),
        onClick = { viewModel.setSimulatedBlackout(!on) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (on) ACCENT_RED else TEXT_FAINT, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "GNSS blackout",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    if (on) "Simulating outage — DR only" else "Tap to simulate an outage",
                    fontSize = 11.sp,
                    color = if (on) ACCENT_RED else TEXT_DIM
                )
            }
            Switch(
                checked = on,
                onCheckedChange = { viewModel.setSimulatedBlackout(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = ACCENT_RED,
                    uncheckedThumbColor = TEXT_DIM,
                    uncheckedTrackColor = PANEL
                )
            )
        }
    }
}

@Composable
private fun PanelButton(
    label: String,
    sub: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = PANEL_HI,
        enabled = enabled,
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) Color.White else TEXT_FAINT
            )
            if (sub != null) {
                Text(sub, fontSize = 11.sp, color = TEXT_DIM)
            }
        }
    }
}

/* ---------------------------------------------------------
 * MAP CHROME
 * --------------------------------------------------------- */

@Composable
private fun StatusPill(drState: DrState, hasFix: Boolean) {
    val (dot, label) = when {
        drState.blackoutSimulated -> ACCENT_RED to "Blackout · DR only"
        !hasFix -> TEXT_FAINT to "Acquiring GPS"
        drState.running -> ACCENT_GREEN to "Tracking"
        else -> ACCENT_AMBER to "Buffering ${drState.bufferFill}/100"
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = PANEL.copy(alpha = 0.94f)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(9.dp))
            Column {
                Text("Cynos", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(label, fontSize = 11.sp, color = TEXT_DIM)
            }
        }
    }
}

@Composable
private fun MapStat(label: String, value: String, sub: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), fontSize = 10.sp, color = TEXT_FAINT, letterSpacing = 1.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(sub, fontSize = 10.sp, color = TEXT_DIM)
    }
}

@Composable
private fun LegendChip(color: Color, label: String, dim: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(if (dim) color.copy(alpha = 0.3f) else color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 11.sp, color = if (dim) TEXT_FAINT else TEXT_DIM)
    }
}

@Composable
private fun IconSurface(
    size: Dp = 44.dp,
    color: Color = PANEL.copy(alpha = 0.94f),
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/* Hand-drawn glyphs: no icon artifact dependency to resolve. */

@Composable
private fun MenuGlyph() {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width
        listOf(0.18f, 0.5f, 0.82f).forEach { fy ->
            drawLine(
                color = Color.White,
                start = Offset(0f, size.height * fy),
                end = Offset(w, size.height * fy),
                strokeWidth = size.height * 0.11f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun CloseGlyph() {
    Canvas(modifier = Modifier.size(14.dp)) {
        val s = size.width
        drawLine(Color.White, Offset(0f, 0f), Offset(s, s), s * 0.13f, StrokeCap.Round)
        drawLine(Color.White, Offset(s, 0f), Offset(0f, s), s * 0.13f, StrokeCap.Round)
    }
}

@Composable
private fun CrosshairGlyph() {
    Canvas(modifier = Modifier.size(24.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        drawCircle(Color.White, radius = r * 0.55f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.14f))
        drawCircle(Color.White, radius = r * 0.16f, center = c)
        val tick = r * 0.22f
        drawLine(Color.White, Offset(c.x, 0f), Offset(c.x, tick), r * 0.14f, StrokeCap.Round)
        drawLine(Color.White, Offset(c.x, size.height - tick), Offset(c.x, size.height), r * 0.14f, StrokeCap.Round)
        drawLine(Color.White, Offset(0f, c.y), Offset(tick, c.y), r * 0.14f, StrokeCap.Round)
        drawLine(Color.White, Offset(size.width - tick, c.y), Offset(size.width, c.y), r * 0.14f, StrokeCap.Round)
    }
}

/* ---------------------------------------------------------
 * DEVICE DATA
 * --------------------------------------------------------- */

@Composable
fun DeviceDataScreen(
    location: LocationData?,
    drState: DrState,
    accelerometer: SensorData,
    gyroscope: SensorData,
    viewModel: SensorViewModel,
    onBackToMap: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSurface(size = 38.dp, color = PANEL, onClick = onBackToMap) {
                Canvas(modifier = Modifier.size(13.dp)) {
                    val s = size.width
                    drawLine(Color.White, Offset(s * 0.7f, 0f), Offset(s * 0.2f, s / 2f), s * 0.15f, StrokeCap.Round)
                    drawLine(Color.White, Offset(s * 0.2f, s / 2f), Offset(s * 0.7f, s), s * 0.15f, StrokeCap.Round)
                }
            }
            Spacer(Modifier.width(14.dp))
            Text("Device data", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(Modifier.height(20.dp))

        BlackoutToggle(drState = drState, viewModel = viewModel)

        Spacer(Modifier.height(22.dp))

        SensorCard(
            title = "Dead reckoning",
            status = when {
                drState.running -> "Running"
                drState.bufferFill < 100 -> "Buffering"
                else -> "Idle"
            },
            statusColor = if (drState.running) ACCENT_GREEN else ACCENT_AMBER
        ) {
            SensorGrid {
                SensorValue("Buffer", "${drState.bufferFill}/100")
                SensorValue("Anchored", if (drState.anchored) "Yes" else "No")
                SensorValue("GNSS", if (drState.gnssFresh) "Fresh" else "Stale")
                SensorValue("Heading", "${headingToCardinal(drState.heading)} ${drState.heading.toInt()}°")
                SensorValue("Yaw rate", String.format("%.2f °/s", drState.yawRateDps))
                SensorValue("Velocity", String.format("%.2f m/s", drState.velocity))
                SensorValue("East", String.format("%.1f m", drState.east))
                SensorValue("North", String.format("%.1f m", drState.north))
                SensorValue("Inference", "${drState.gyroMs}/${drState.dvseMs} ms")
            }
        }

        SensorCard(
            title = "Viterbi snapping",
            status = when {
                drState.roadsLoading -> "Downloading"
                drState.snapped -> "Matched"
                drState.snapEnabled -> "Armed"
                else -> "Standby"
            },
            statusColor = if (drState.snapped) ACCENT_GREEN else TEXT_FAINT
        ) {
            SensorGrid {
                SensorValue("Segments", "${drState.roadsLoaded}")
                SensorValue("Offset", String.format("%.1f m", drState.snapOffsetM))
                SensorValue("Road", drState.roadName)
            }
        }

        SensorCard(
            title = "GPS",
            status = if (location != null) "Active" else "Waiting",
            statusColor = if (location != null) ACCENT_GREEN else TEXT_FAINT
        ) {
            SensorGrid {
                SensorValue("Latitude", location?.let { String.format("%.6f", it.latitude) } ?: "--")
                SensorValue("Longitude", location?.let { String.format("%.6f", it.longitude) } ?: "--")
                SensorValue("Accuracy", location?.let { String.format("%.1f m", it.accuracy) } ?: "--")
                SensorValue("Altitude", location?.let { String.format("%.1f m", it.altitude) } ?: "--")
                SensorValue("Speed", location?.let { String.format("%.2f m/s", it.speed) } ?: "--")
                SensorValue("Bearing", location?.let { String.format("%.0f°", it.heading) } ?: "--")
            }
        }

        SensorCard(title = "Accelerometer") {
            SensorGrid {
                SensorValue("X", String.format("%.2f", accelerometer.x))
                SensorValue("Y", String.format("%.2f", accelerometer.y))
                SensorValue("Z", String.format("%.2f", accelerometer.z))
            }
        }

        SensorCard(title = "Gyroscope") {
            SensorGrid {
                SensorValue("X", String.format("%.4f", gyroscope.x))
                SensorValue("Y", String.format("%.4f", gyroscope.y))
                SensorValue("Z", String.format("%.4f", gyroscope.z))
            }
        }
    }
}

/* ---------------------------------------------------------
 * SHARED PIECES
 * --------------------------------------------------------- */

private val CARDINALS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** 0-360 compass degrees to an 8-point cardinal label. */
fun headingToCardinal(deg: Float): String {
    var d = deg % 360f
    if (d < 0f) d += 360f
    return CARDINALS[(((d + 22.5f) / 45f).toInt()) % 8]
}

@Composable
fun SensorCard(
    title: String,
    status: String? = null,
    statusColor: Color = ACCENT_GREEN,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(PANEL, RoundedCornerShape(14.dp))
            .border(1.dp, STROKE, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

                if (status != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(7.dp).background(statusColor, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(status, fontSize = 11.sp, color = TEXT_DIM)
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
            .padding(bottom = 14.dp, end = 8.dp)
    ) {
        Text(label, fontSize = 11.sp, color = TEXT_FAINT, modifier = Modifier.padding(bottom = 3.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}