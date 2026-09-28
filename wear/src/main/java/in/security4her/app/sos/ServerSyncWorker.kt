package `in`.security4her.app.sos

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import `in`.security4her.app.data.WatchStore
import java.util.concurrent.TimeUnit

/** Retries the direct server path once any network (Wi-Fi or phone-proxied) is available. */
class ServerSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = WatchStore.get(applicationContext)
        val incident = store.incidentNow() ?: return Result.success()
        if (incident.serverAccepted || incident.ended) return Result.success()
        if (store.linkNow() == null) return Result.success() // nothing we can authenticate with
        SosController.deliverToServer(applicationContext, incident.incidentId)
        val after = store.incidentNow()
        return when {
            after == null || after.serverAccepted || after.ended -> Result.success()
            runAttemptCount < 30 -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private const val NAME = "s4h-server-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ServerSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
