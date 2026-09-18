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

            val fine =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false

            val coarse =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

            sensorViewModel.setLocationPermissionGranted(
                fine || coarse
            )

            if (fine || coarse) {
                sensorViewModel.startLocationUpdates()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorViewModel =
            androidx.lifecycle.ViewModelProvider(this)[
                SensorViewModel::class.java
            ]


        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )


        // OSMDroid configuration
        val prefs =
            android.preference.PreferenceManager
                .getDefaultSharedPreferences(this)

        org.osmdroid.config.Configuration
            .getInstance()
            .load(this, prefs)

        org.osmdroid.config.Configuration
            .getInstance()
            .userAgentValue =
            "CynosApp_SIH2026/1.0 (Contact: admin@example.com)"


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


/*
 * ---------------------------------------------------------
 * MAIN NAVIGATION
 * ---------------------------------------------------------
 *
 * Page 0 = Map
 * Page 1 = Device Data
 *
 * Navigation is done with buttons.
 * There is NO HorizontalPager anymore.
 */
@Composable
fun DashboardScreen(
    viewModel: SensorViewModel
) {

    val accelerometer by
        viewModel.accelerometerData.collectAsState()

    val gyroscope by
        viewModel.gyroscopeData.collectAsState()

    val magnetometer by
        viewModel.magnetometerData.collectAsState()

    val orientation by
        viewModel.orientationData.collectAsState()

    val location by
        viewModel.locationData.collectAsState()


    var showDeviceData by remember {
        mutableStateOf(false)
    }


    if (showDeviceData) {

        DeviceDataScreen(
            location = location,
            accelerometer = accelerometer,
            gyroscope = gyroscope,
            magnetometer = magnetometer,
            orientation = orientation,

            onBackToMap = {
                showDeviceData = false
            }
        )

    } else {

        MapHomeScreen(
            location = location,

            onOpenDeviceData = {
                showDeviceData = true
            }
        )
    }
}


/*
 * ---------------------------------------------------------
 * MAP HOME SCREEN
 * ---------------------------------------------------------
 */

@Composable
fun MapHomeScreen(
    location: LocationData?,
    onOpenDeviceData: () -> Unit
) {

    val gpsStatus =
        when {
            location == null -> "Waiting for GPS"
            else -> "GPS Active"
        }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0b0f14))
    ) {

        /*
         * MAP
         */
        OsmMapView(
            currentLocation = location,
            modifier = Modifier.fillMaxSize()
        )


        /*
         * TOP BAR
         */
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),

            horizontalArrangement =
                Arrangement.SpaceBetween,

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Column {

                Text(
                    text = "Cynos",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = gpsStatus,
                    fontSize = 13.sp,
                    color = Color.White
                )
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


        /*
         * BOTTOM GPS INFO
         */
        if (location != null) {

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

                    horizontalArrangement =
                        Arrangement.SpaceBetween
                ) {

                    MapStat(
                        label = "Speed",
                        value = String.format(
                            "%.1f m/s",
                            location.speed
                        )
                    )

                    MapStat(
                        label = "Accuracy",
                        value = String.format(
                            "%.1f m",
                            location.accuracy
                        )
                    )

                    MapStat(
                        label = "Heading",
                        value = String.format(
                            "%.0f°",
                            location.heading
                        )
                    )
                }
            }
        }
    }
}


/*
 * ---------------------------------------------------------
 * MAP STAT
 * ---------------------------------------------------------
 */

@Composable
fun MapStat(
    label: String,
    value: String
) {

    Column(
        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {

        Text(
            text = label,
            fontSize = 11.sp,
            color = Color(0xFF8D99A6)
        )

        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}


/*
 * ---------------------------------------------------------
 * DEVICE DATA SCREEN
 * ---------------------------------------------------------
 */

@Composable
fun DeviceDataScreen(
    location: LocationData?,
    accelerometer: SensorData,
    gyroscope: SensorData,
    magnetometer: SensorData,
    orientation: OrientationData,
    onBackToMap: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0b0f14))
            .verticalScroll(rememberScrollState())
            .padding(
                top = 60.dp,
                bottom = 40.dp,
                start = 16.dp,
                end = 16.dp
            )
    ) {

        /*
         * BACK TO MAP
         */
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

        Spacer(
            modifier = Modifier.height(20.dp)
        )


        Text(
            text = "Device Data",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Text(
            text = "Live device telemetry",
            fontSize = 15.sp,
            color = Color(0xFF8d99a6),

            modifier = Modifier.padding(
                top = 4.dp,
                bottom = 24.dp
            )
        )


        /*
         * GPS
         */
        SensorCard(
            title = "GPS / Location",
            status =
                if (location != null)
                    "GPS Active"
                else
                    "Waiting for GPS"
        ) {

            SensorGrid {

                SensorValue(
                    "Latitude",
                    location?.let {
                        String.format(
                            "%.6f",
                            it.latitude
                        )
                    } ?: "--"
                )

                SensorValue(
                    "Longitude",
                    location?.let {
                        String.format(
                            "%.6f",
                            it.longitude
                        )
                    } ?: "--"
                )

                SensorValue(
                    "Accuracy",
                    location?.let {
                        String.format(
                            "%.1f m",
                            it.accuracy
                        )
                    } ?: "--"
                )

                SensorValue(
                    "Altitude",
                    location?.let {
                        String.format(
                            "%.1f m",
                            it.altitude
                        )
                    } ?: "--"
                )

                SensorValue(
                    "Speed",
                    location?.let {
                        String.format(
                            "%.2f m/s",
                            it.speed
                        )
                    } ?: "--"
                )

                SensorValue(
                    "Heading",
                    location?.let {
                        String.format(
                            "%.1f°",
                            it.heading
                        )
                    } ?: "--"
                )
            }
        }


        /*
         * ACCELEROMETER
         */
        SensorCard(
            title = "Accelerometer"
        ) {

            SensorGrid {

                SensorValue(
                    "X",
                    String.format(
                        "%.2f m/s²",
                        accelerometer.x
                    )
                )

                SensorValue(
                    "Y",
                    String.format(
                        "%.2f m/s²",
                        accelerometer.y
                    )
                )

                SensorValue(
                    "Z",
                    String.format(
                        "%.2f m/s²",
                        accelerometer.z
                    )
                )
            }
        }


        /*
         * GYROSCOPE
         */
        SensorCard(
            title = "Gyroscope"
        ) {

            SensorGrid {

                SensorValue(
                    "X",
                    String.format(
                        "%.2f rad/s",
                        gyroscope.x
                    )
                )

                SensorValue(
                    "Y",
                    String.format(
                        "%.2f rad/s",
                        gyroscope.y
                    )
                )

                SensorValue(
                    "Z",
                    String.format(
                        "%.2f rad/s",
                        gyroscope.z
                    )
                )
            }
        }


        /*
         * MAGNETOMETER
         */
        SensorCard(
            title = "Magnetometer"
        ) {

            SensorGrid {

                SensorValue(
                    "X",
                    String.format(
                        "%.2f µT",
                        magnetometer.x
                    )
                )

                SensorValue(
                    "Y",
                    String.format(
                        "%.2f µT",
                        magnetometer.y
                    )
                )

                SensorValue(
                    "Z",
                    String.format(
                        "%.2f µT",
                        magnetometer.z
                    )
                )
            }
        }


        /*
         * ORIENTATION
         */
        SensorCard(
            title = "Orientation"
        ) {

            SensorGrid {

                SensorValue(
                    "Roll",
                    String.format(
                        "%.1f°",
                        orientation.roll * 180 / PI
                    )
                )

                SensorValue(
                    "Pitch",
                    String.format(
                        "%.1f°",
                        orientation.pitch * 180 / PI
                    )
                )

                SensorValue(
                    "Yaw",
                    String.format(
                        "%.1f°",
                        orientation.yaw * 180 / PI
                    )
                )
            }
        }


        Text(
            text = "Codeteymons • SIH 2026",
            fontSize = 12.sp,
            color = Color(0xFF59636e),
            textAlign = TextAlign.Center,

            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        )
    }
}


/*
 * ---------------------------------------------------------
 * SENSOR CARD
 * ---------------------------------------------------------
 */

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
            .background(
                Color(0xFF151b22),
                RoundedCornerShape(14.dp)
            )
            .border(
                1.dp,
                Color(0xFF242c36),
                RoundedCornerShape(14.dp)
            )
            .padding(18.dp)
    ) {

        Column {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),

                horizontalArrangement =
                    Arrangement.SpaceBetween,

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    text = title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )


                if (status != null) {

                    Row(
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    Color(0xFF35d07f),
                                    CircleShape
                                )
                        )

                        Spacer(
                            modifier = Modifier.width(6.dp)
                        )

                        Text(
                            text = status,
                            fontSize = 12.sp,
                            color = Color(0xFF9ba7b4)
                        )
                    }
                }
            }

            content()
        }
    }
}


/*
 * ---------------------------------------------------------
 * SENSOR GRID
 * ---------------------------------------------------------
 */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SensorGrid(
    content: @Composable FlowRowScope.() -> Unit
) {

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
fun FlowRowScope.SensorValue(
    label: String,
    value: String
) {

    Column(
        modifier = Modifier
            .weight(1f)
            .padding(bottom = 16.dp)
    ) {

        Text(
            text = label,
            fontSize = 12.sp,
            color = Color(0xFF7f8a96),

            modifier = Modifier.padding(
                bottom = 5.dp
            )
        )

        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}