package `in`.security4her.app.sos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import `in`.security4her.app.data.Fix
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

object Locator {
    private const val TAG = "S4hLocator"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** A fresh fix if one arrives within [timeoutMs], otherwise the last known one. Never blocks longer. */
    @SuppressLint("MissingPermission")
    suspend fun quickFix(context: Context, timeoutMs: Long = 8_000): Fix? {
        if (!hasPermission(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val current = try {
            withTimeoutOrNull(timeoutMs) {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentLocation failed", e)
            null
        } finally {
            cts.cancel()
        }
        val location = current ?: try {
            client.lastLocation.await()
        } catch (e: Exception) {
            Log.w(TAG, "lastLocation failed", e)
            null
        }
        return location?.let {
            Fix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null, it.time)
        }
    }
}
