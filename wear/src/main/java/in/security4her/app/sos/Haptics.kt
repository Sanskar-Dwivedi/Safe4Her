package `in`.security4her.app.sos

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {
    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    fun tick(context: Context) {
        vibrator(context)?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun sosStarted(context: Context) {
        vibrator(context)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 600), -1))
    }

    fun failure(context: Context) {
        vibrator(context)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000), -1))
    }

    fun fakeCallAccepted(context: Context) {
        vibrator(context)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 90, 120), -1))
    }
}
