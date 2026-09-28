package `in`.security4her.app.sos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import `in`.security4her.app.data.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Records audio on the watch during an SOS: its mic is on the wrist, often clearer than a phone in a bag.
 * 30 s AAC pieces (24 kbps), each fingerprinted and streamed to the phone, which adds them to the evidence record.
 * Pieces that can't be delivered stay on the watch and are retried on every heartbeat.
 */
object WatchRecorder {
    private const val TAG = "S4hWatchRec"
    private const val SEG_BYTES = 90_000L // ~30 s at 24 kbps

    private data class Piece(val incident: String, val seq: Int, val file: File, val start: Long, val end: Long, val sha: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentLinkedQueue<Piece>()
    private val sending = Mutex()
    private var rec: MediaRecorder? = null
    private var cur: File? = null
    private var next: File? = null
    private var curStart = 0L
    private var seq = 0
    private var incident: String? = null

    val recording get() = rec != null

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun file(ctx: Context, id: String, n: Int) =
        File(File(ctx.filesDir, "evidence/$id").apply { mkdirs() }, "w-%04d.m4a".format(Locale.US, n))

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(ctx: Context, incidentId: String) {
        if (rec != null || !hasPermission(ctx)) return
        val app = ctx.applicationContext
        incident = incidentId
        seq = 0
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(24_000)
            r.setMaxFileSize(SEG_BYTES)
            seq += 1
            val f = file(app, incidentId, seq)
            r.setOutputFile(f)
            cur = f
            curStart = System.currentTimeMillis()
            r.setOnInfoListener { mr, what, _ ->
                when (what) {
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                        val n = file(app, incidentId, seq + 1)
                        next = n
                        runCatching { mr.setNextOutputFile(n) }
                    }
                    MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                        val done = cur
                        val s = curStart
                        seq += 1
                        cur = next
                        curStart = System.currentTimeMillis()
                        if (done != null) finished(app, incidentId, seq - 1, done, s, curStart)
                    }
                }
            }
            r.prepare()
            r.start()
            rec = r
            Log.i(TAG, "watch audio recording started for $incidentId")
        } catch (e: Exception) {
            rec = null
            Log.w(TAG, "watch audio couldn't start", e)
        }
    }

    @Synchronized
    fun stop(ctx: Context) {
        val r = rec ?: return
        rec = null
        val done = cur
        val s = curStart
        runCatching { r.stop() }
        r.release()
        val id = incident ?: return
        if (done != null && done.length() > 0) finished(ctx.applicationContext, id, seq, done, s, System.currentTimeMillis())
        cur = null
    }

    private fun finished(ctx: Context, id: String, n: Int, f: File, start: Long, end: Long) {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> md.update(input.readBytes()) }
        pending += Piece(id, n, f, start, end, md.digest().joinToString("") { "%02x".format(it) })
        scope.launch { flush(ctx) }
    }

    /** Streams undelivered pieces to the phone. Safe to call often (heartbeat). */
    suspend fun flush(ctx: Context) = sending.withLock {
        if (pending.isEmpty()) return@withLock
        val node = runCatching {
            Wearable.getCapabilityClient(ctx).getCapability(Paths.CAP_PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes.firstOrNull()
        }.getOrNull() ?: return@withLock
        val client = Wearable.getChannelClient(ctx)
        for (p in pending.toList()) {
            val ok = runCatching {
                val ch = client.openChannel(node.id, "/s4h/evidence/${p.incident}/${p.seq}/${p.start}/${p.end}/${p.sha}").await()
                client.getOutputStream(ch).await().use { out -> p.file.inputStream().use { it.copyTo(out) } }
                runCatching { client.close(ch).await() }
            }.isSuccess
            if (!ok) break
            pending.remove(p)
            p.file.delete()
        }
    }
}
