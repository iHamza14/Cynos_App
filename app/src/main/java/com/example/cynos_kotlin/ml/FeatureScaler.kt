package com.example.cynos_kotlin.ml

object FeatureScaler {

    private val accelMean = floatArrayOf(
        0.18976542866292767f,
        -0.22481502883917653f,
        9.813567370305023f
    )

    private val accelScale = floatArrayOf(
        2.4248186597613337f,
        2.174140844529276f,
        1.1714947744083575f
    )

    private val gyroMean = floatArrayOf(
        -0.00015408056339177915f,
        -0.0036185445994712f,
        0.0005772500905832816f
    )

    private val gyroScale = floatArrayOf(
        0.14569048962281997f,
        0.3348341915932401f,
        0.22562545934981898f
    )

    fun scaleAccelerometer(
        x: Float,
        y: Float,
        z: Float
    ): FloatArray {

        return floatArrayOf(
            (x - accelMean[0]) / accelScale[0],
            (y - accelMean[1]) / accelScale[1],
            (z - accelMean[2]) / accelScale[2]
        )
    }

    fun scaleGyroscope(
        x: Float,
        y: Float,
        z: Float
    ): FloatArray {

        return floatArrayOf(
            (x - gyroMean[0]) / gyroScale[0],
            (y - gyroMean[1]) / gyroScale[1],
            (z - gyroMean[2]) / gyroScale[2]
        )
    }
}