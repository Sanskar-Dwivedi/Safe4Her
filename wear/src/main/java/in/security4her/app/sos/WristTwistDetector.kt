package `in`.security4her.app.sos

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/** Sensor listener for the opt-in fake-call gesture. */
class WristTwistDetector(context: Context, private val onTwist: () -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val logic = WristTwistLogic()
    private var accelerationG = 1f

    fun start(): Boolean {
        val sensorManager = manager ?: return false
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false
        if (!sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)) return false
        if (!sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)) {
            sensorManager.unregisterListener(this)
            return false
        }
        return true
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = event.values
                accelerationG = (sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH)
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (logic.onSample(event.values[0], event.values[1], accelerationG, event.timestamp / 1_000_000)) onTwist()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        fun available(context: Context): Boolean {
            val manager = context.getSystemService(SensorManager::class.java) ?: return false
            return manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null &&
                manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        }
    }
}
