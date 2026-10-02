package `in`.security4her.app.sos

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/** Same samples and recognition in practice and the foreground gesture service. */
class WristTwistDetector(
    context: Context,
    strength: TwistStrength = TwistStrength.STANDARD,
    private val onSample: (WristTwistLogic.Sample) -> Unit = {},
    private val onTwist: () -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val logic = WristTwistLogic(strength)
    private var accelerationG = 1f
    private var accelerationAt = 0L
    private var reportedAt = 0L
    var lastSampleAt = 0L
        private set

    fun start(): Boolean {
        val sm = manager ?: return false
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false
        return try {
            if (!sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME) ||
                !sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)) {
                sm.unregisterListener(this)
                false
            } else true
        } catch (_: SecurityException) {
            sm.unregisterListener(this)
            false
        }
    }

    fun stop() { manager?.unregisterListener(this) }

    override fun onSensorChanged(event: SensorEvent) {
        val now = event.timestamp / 1_000_000
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = event.values
                accelerationG = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                accelerationAt = now
            }
            Sensor.TYPE_GYROSCOPE -> {
                // Sensors can be delivered in either order even when their timestamps are close.
                if (accelerationAt == 0L || abs(now - accelerationAt) > 250) return
                lastSampleAt = SystemClock.elapsedRealtime()
                val result = logic.onSample(event.values[0], event.values[1], event.values[2], accelerationG, now)
                if (result.detected || now - reportedAt >= 100) {
                    reportedAt = now
                    onSample(result)
                }
                if (result.detected) onTwist()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        fun available(context: Context): Boolean {
            val sm = context.getSystemService(SensorManager::class.java) ?: return false
            return sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null &&
                sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        }
    }
}
