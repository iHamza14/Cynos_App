package com.example.cynos_kotlin

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

class SensorViewModel(
    application: Application
) : AndroidViewModel(application), SensorEventListener {

    // ---------------------------------------------------------
    // SENSOR MANAGER
    // ---------------------------------------------------------

    private val sensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val fusedLocationClient =
        LocationServices.getFusedLocationProviderClient(application)

    private val accelerometer =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gyroscope =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val magnetometer =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)


    // ---------------------------------------------------------
    // SENSOR STATE
    // ---------------------------------------------------------

    private val _accelerometerData =
        MutableStateFlow(SensorData())

    val accelerometerData: StateFlow<SensorData> =
        _accelerometerData.asStateFlow()


    private val _gyroscopeData =
        MutableStateFlow(SensorData())

    val gyroscopeData: StateFlow<SensorData> =
        _gyroscopeData.asStateFlow()


    private val _magnetometerData =
        MutableStateFlow(SensorData())

    val magnetometerData: StateFlow<SensorData> =
        _magnetometerData.asStateFlow()


    private val _orientationData =
        MutableStateFlow(OrientationData())

    val orientationData: StateFlow<OrientationData> =
        _orientationData.asStateFlow()


    // ---------------------------------------------------------
    // LOCATION STATE
    // ---------------------------------------------------------

    private val _locationData =
        MutableStateFlow<LocationData?>(null)

    val locationData: StateFlow<LocationData?> =
        _locationData.asStateFlow()


    private val _locationPermissionGranted =
        MutableStateFlow(false)

    val locationPermissionGranted: StateFlow<Boolean> =
        _locationPermissionGranted.asStateFlow()


    fun setLocationPermissionGranted(granted: Boolean) {
        _locationPermissionGranted.value = granted
    }


    // ---------------------------------------------------------
    // ORIENTATION CALCULATION
    // ---------------------------------------------------------

    private var gravityValues =
        FloatArray(3)

    private var magneticValues =
        FloatArray(3)


    // ---------------------------------------------------------
    // GPS CALLBACK
    // ---------------------------------------------------------

    private val locationCallback =
        object : LocationCallback() {

            override fun onLocationResult(
                locationResult: LocationResult
            ) {

                val location =
                    locationResult.lastLocation

                if (location != null) {

                    _locationData.value = LocationData(

                        latitude = location.latitude,

                        longitude = location.longitude,

                        accuracy = location.accuracy,

                        altitude = location.altitude,

                        speed = location.speed,

                        heading = location.bearing
                    )
                }
            }
        }


    // ---------------------------------------------------------
    // INITIALIZATION
    // ---------------------------------------------------------

    init {
        startSensors()
    }


    // ---------------------------------------------------------
    // SENSOR START
    // ---------------------------------------------------------

    private fun startSensors() {

        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        gyroscope?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        magnetometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }


    // ---------------------------------------------------------
    // GPS START
    // ---------------------------------------------------------

    fun startLocationUpdates() {

        try {

            val locationRequest =
                LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    2000L
                ).build()


            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )


            _locationPermissionGranted.value = true

        } catch (e: SecurityException) {

            _locationPermissionGranted.value = false
        }
    }


    // ---------------------------------------------------------
    // GPS STOP
    // ---------------------------------------------------------

    fun stopLocationUpdates() {

        fusedLocationClient.removeLocationUpdates(
            locationCallback
        )
    }


    // ---------------------------------------------------------
    // SENSOR CALLBACK
    // ---------------------------------------------------------

    override fun onSensorChanged(
        event: SensorEvent?
    ) {

        if (event == null) return


        when (event.sensor.type) {

            Sensor.TYPE_ACCELEROMETER -> {

                _accelerometerData.value =
                    SensorData(
                        event.values[0],
                        event.values[1],
                        event.values[2]
                    )


                System.arraycopy(
                    event.values,
                    0,
                    gravityValues,
                    0,
                    event.values.size
                )


                updateOrientation()
            }


            Sensor.TYPE_GYROSCOPE -> {

                _gyroscopeData.value =
                    SensorData(
                        event.values[0],
                        event.values[1],
                        event.values[2]
                    )
            }


            Sensor.TYPE_MAGNETIC_FIELD -> {

                _magnetometerData.value =
                    SensorData(
                        event.values[0],
                        event.values[1],
                        event.values[2]
                    )


                System.arraycopy(
                    event.values,
                    0,
                    magneticValues,
                    0,
                    event.values.size
                )


                updateOrientation()
            }
        }
    }


    // ---------------------------------------------------------
    // ORIENTATION
    // ---------------------------------------------------------

    private fun updateOrientation() {

        val rotationMatrix =
            FloatArray(9)


        val success =
            SensorManager.getRotationMatrix(
                rotationMatrix,
                null,
                gravityValues,
                magneticValues
            )


        if (success) {

            val orientation =
                FloatArray(3)


            SensorManager.getOrientation(
                rotationMatrix,
                orientation
            )


            _orientationData.value =
                OrientationData(
                    yaw = orientation[0],
                    pitch = orientation[1],
                    roll = orientation[2]
                )
        }
    }


    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
    }


    // ---------------------------------------------------------
    // CLEANUP
    // ---------------------------------------------------------

    override fun onCleared() {

        super.onCleared()

        sensorManager.unregisterListener(this)

        stopLocationUpdates()
    }
}