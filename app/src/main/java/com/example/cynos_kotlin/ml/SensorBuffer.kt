package com.example.cynos_kotlin.ml

data class ImuSample(
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val timestampNs: Long
)

class SensorBuffer(
    private val capacity: Int = 100
) {

    private val samples = ArrayDeque<ImuSample>()

    @Synchronized
    fun add(sample: ImuSample) {
        if (samples.size >= capacity) {
            samples.removeFirst()
        }

        samples.addLast(sample)
    }

    @Synchronized
    fun isFull(): Boolean {
        return samples.size == capacity
    }

    @Synchronized
    fun getSamples(): List<ImuSample> {
        return samples.toList()
    }

    @Synchronized
    fun clear() {
        samples.clear()
    }
}