package com.eldering.detection

/** Sensor channels the detector consumes. Units are fixed so the module stays free of Android types. */
public enum class SensorKind {
    /** Accelerometer, m/s², including gravity (Android `TYPE_ACCELEROMETER`). */
    ACCEL,

    /** Gyroscope, rad/s (Android `TYPE_GYROSCOPE`). */
    GYRO,

    /** Barometric pressure in hPa, carried in [SensorSample.x]. Optional: many devices have no barometer. */
    PRESSURE,
}

/**
 * One sensor reading.
 *
 * @property tNanos sensor timestamp in nanoseconds on the `elapsedRealtimeNanos` time base
 *   (Android `SensorEvent.timestamp`), never the delivery time.
 */
public data class SensorSample(
    val tNanos: Long,
    val kind: SensorKind,
    val x: Float,
    val y: Float,
    val z: Float,
)
