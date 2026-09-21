package com.example.cynos_kotlin.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File

class OnnxRuntimeManager(
    private val context: Context
) {

    private val environment = OrtEnvironment.getEnvironment()

    private lateinit var dvseSession: OrtSession
    private lateinit var gyroSession: OrtSession

    @Volatile
    var isReady: Boolean = false
        private set

    fun initialize() {
        val dvsePath = copyAssetPairToInternalStorage("dvse.onnx", "dvse.onnx.data")
        val gyroPath = copyAssetPairToInternalStorage("gyro_tcn.onnx", "gyro_tcn.onnx.data")

        dvseSession = environment.createSession(dvsePath)
        gyroSession = environment.createSession(gyroPath)

        isReady = true
    }

    private fun copyAssetPairToInternalStorage(
        modelName: String,
        dataName: String
    ): String {
        val modelFile = File(context.filesDir, modelName)
        val dataFile = File(context.filesDir, dataName)

        if (!modelFile.exists()) {
            context.assets.open(modelName).use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        if (!dataFile.exists()) {
            context.assets.open(dataName).use { input ->
                dataFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        return modelFile.absolutePath
    }

    // -----------------------------------------------------------------
    // INFERENCE
    // -----------------------------------------------------------------

    /**
     * @param gyroScaled [1][100][3] already standardised by FeatureScaler
     * @return yaw_rate_norm[0][99]
     */
    @Synchronized
    fun runGyroInference(gyroScaled: Array<Array<FloatArray>>): Float {
        val tensor = OnnxTensor.createTensor(environment, gyroScaled)
        try {
            gyroSession.run(mapOf("gyro_scaled" to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<FloatArray>
                return out[0][out[0].size - 1]
            }
        } finally {
            tensor.close()
        }
    }

    /**
     * @param accWindow  [1][100][3] scaled accelerometer
     * @param gyroWindow [1][100][3] scaled gyroscope
     * @param vrSeq      [1][10][1]  reference-velocity sequence (m/s)
     * @param v0         scalar initial velocity (m/s)
     * @return delta_v[0][9]
     */
    @Synchronized
    fun runDvseInference(
        accWindow: Array<Array<FloatArray>>,
        gyroWindow: Array<Array<FloatArray>>,
        vrSeq: Array<Array<FloatArray>>,
        v0: Float
    ): Float {
        val accTensor = OnnxTensor.createTensor(environment, accWindow)
        val gyroTensor = OnnxTensor.createTensor(environment, gyroWindow)
        val vrTensor = OnnxTensor.createTensor(environment, vrSeq)
        val v0Tensor = OnnxTensor.createTensor(environment, arrayOf(floatArrayOf(v0)))

        try {
            dvseSession.run(
                mapOf(
                    "acc_window" to accTensor,
                    "gyro_window" to gyroTensor,
                    "vr_seq" to vrTensor,
                    "v0" to v0Tensor
                )
            ).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<FloatArray>
                return out[0][0]
            }
        } finally {
            accTensor.close()
            gyroTensor.close()
            vrTensor.close()
            v0Tensor.close()
        }
    }

    // -----------------------------------------------------------------
    // DEBUG
    // -----------------------------------------------------------------

    fun printModelInfo() {
        android.util.Log.d("ONNX_TEST", "===== DVSE =====")
        dvseSession.inputInfo.forEach { (n, i) -> android.util.Log.d("ONNX_TEST", "INPUT: $n -> $i") }
        dvseSession.outputInfo.forEach { (n, i) -> android.util.Log.d("ONNX_TEST", "OUTPUT: $n -> $i") }

        android.util.Log.d("ONNX_TEST", "===== GYRO TCN =====")
        gyroSession.inputInfo.forEach { (n, i) -> android.util.Log.d("ONNX_TEST", "INPUT: $n -> $i") }
        gyroSession.outputInfo.forEach { (n, i) -> android.util.Log.d("ONNX_TEST", "OUTPUT: $n -> $i") }
    }

    fun close() {
        isReady = false
        if (::dvseSession.isInitialized) dvseSession.close()
        if (::gyroSession.isInitialized) gyroSession.close()
        environment.close()
    }
}