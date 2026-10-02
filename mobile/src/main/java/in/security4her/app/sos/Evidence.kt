package `in`.security4her.app.sos

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import `in`.security4her.app.BuildConfig
import `in`.security4her.app.MainActivity
import `in`.security4her.app.R
import `in`.security4her.app.core.AppLog
import `in`.security4her.app.core.S4hJson
import `in`.security4her.app.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One recorded piece. Its fingerprint also covers the previous piece's, so a missing or edited piece breaks the chain. */
@Serializable
data class EvidencePiece(
    val seq: Int,
    val kind: String, // audio | video | watch_audio
    val file: String,
    val startedAt: Long,
    val endedAt: Long,
    val bytes: Long,
    val sha256: String,
    val chain: String, // sha256(prev.chain + sha256)
    val lat: Double? = null,
    val lng: Double? = null,
)

@Serializable
data class EvidenceCase(
    val incidentId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val trigger: String,
    val device: String,
    val app: String,
    val recording: Boolean = true,
    val audioOn: Boolean = false,
    val videoOn: Boolean = false,
    val problems: List<String> = emptyList(),
    val pieces: List<EvidencePiece> = emptyList(),
) {
    fun seconds(kind: String) = pieces.filter { it.kind == kind }.sumOf { it.endedAt - it.startedAt } / 1000
    val bytes get() = pieces.sumOf { it.bytes }
}

data class Integrity(val ok: Boolean, val checked: Int, val broken: List<Int>)

/**
 * Evidence recording during an SOS: audio in 15 s pieces (gapless) and back-camera video in 30 s clips,
 * from the moment the SOS starts, whether the phone is in a pocket or not. Watch audio arrives from the watch.
 * Everything stays in app-private storage (not in the gallery) with a fingerprint chain in manifest.json.
 */
object Evidence {
    private const val AUDIO_SEG_BYTES = 45_000L // ~15 s at 24 kbps
    private const val VIDEO_CLIP_MS = 30_000L
    private const val MAX_MS = 60 * 60_000L
    private const val LOCK_MS = 24 * 3_600_000L
    private const val ZERO = "0000000000000000000000000000000000000000000000000000000000000000"

    private val _active = MutableStateFlow<EvidenceCase?>(null)
    val active: StateFlow<EvidenceCase?> = _active.asStateFlow()
    private val io = Executors.newSingleThreadExecutor() // hashing + manifest writes, in order
    private val main = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var nextAudio: File? = null
    private var audioStart = 0L
    private var videoCapture: VideoCapture<Recorder>? = null
    private var videoRec: Recording? = null
    private var videoWanted = false
    private var provider: ProcessCameraProvider? = null
    private var seqCounter = 0
    private var stopTimer: Runnable? = null

    fun root(ctx: Context) = File(ctx.filesDir, "evidence")
    fun dir(ctx: Context, id: String) = File(root(ctx), id).apply { mkdirs() }
    private fun manifest(ctx: Context, id: String) = File(dir(ctx, id), "manifest.json")

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun wantsAudio(ctx: Context) = Settings.load(ctx).evidence && granted(ctx, Manifest.permission.RECORD_AUDIO)
    fun wantsVideo(ctx: Context) = Settings.load(ctx).evidence && Settings.load(ctx).evidenceVideo && granted(ctx, Manifest.permission.CAMERA)

    // ---- recording ----

    /**
     * Starts (or resumes) recording for [incidentId]. [micAllowed]/[camAllowed] say whether the SOS service got the
     * microphone/camera foreground types; without them Android would hand us silence or no camera.
     */
    @Synchronized
    fun start(owner: LifecycleOwner, ctx: Context, incidentId: String, trigger: String, micAllowed: Boolean, camAllowed: Boolean) {
        val app = ctx.applicationContext
        val existing = _active.value?.takeIf { it.incidentId == incidentId } ?: load(app, incidentId)
        val case = existing?.copy(recording = true, endedAt = null) ?: EvidenceCase(
            incidentId = incidentId, startedAt = System.currentTimeMillis(), trigger = trigger,
            device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            app = "Security4Her ${BuildConfig.VERSION_NAME}",
        )
        seqCounter = case.pieces.maxOfOrNull { it.seq } ?: 0
        save(app, case)
        if (recorder == null && wantsAudio(app)) {
            if (micAllowed) startAudio(app) else problem(app, "Microphone not allowed in the background. Open the app to start audio.")
        }
        if (!videoWanted && wantsVideo(app)) {
            if (camAllowed) startVideo(owner, app) else problem(app, "Camera not allowed in the background. Open the app to start video.")
        }
        if (stopTimer == null) {
            stopTimer = Runnable { AppLog.info("Evidence", "Reached the 60-minute limit"); stop(app) }.also { main.postDelayed(it, MAX_MS) }
        }
    }

    @Synchronized
    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        stopTimer?.let { main.removeCallbacks(it) }
        stopTimer = null
        stopAudio(app)
        videoWanted = false
        videoRec?.stop() // the Finalize event files the last clip
        videoRec = null
        main.postDelayed({ runCatching { provider?.unbindAll() }; provider = null; videoCapture = null }, 1_500)
        val c = _active.value ?: return
        io.execute {
            val done = (load(app, c.incidentId) ?: c).copy(recording = false, endedAt = System.currentTimeMillis())
            save(app, done)
            EvidenceBackup.writeManifest(app, done)
            AppLog.ok("Evidence", "Recording stopped: ${done.pieces.size} pieces, ${human(done.bytes)}")
        }
    }

    private fun nextFile(ctx: Context, kind: String, ext: String): File {
        val id = _active.value!!.incidentId
        return File(dir(ctx, id), "%s-%s.%s".format(Locale.US, kind, SimpleDateFormat("HHmmss-SSS", Locale.US).format(Date()), ext))
    }

    @SuppressLint("MissingPermission")
    private fun startAudio(ctx: Context) {
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(24_000) // speech stays clear; about 11 MB an hour
            r.setMaxFileSize(AUDIO_SEG_BYTES)
            val f = nextFile(ctx, "audio", "m4a")
            r.setOutputFile(f)
            audioFile = f
            audioStart = System.currentTimeMillis()
            r.setOnInfoListener { mr, what, _ ->
                when (what) {
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                        val n = nextFile(ctx, "audio", "m4a")
                        nextAudio = n
                        runCatching { mr.setNextOutputFile(n) }.onFailure { AppLog.warn("Evidence", "Couldn't roll audio file", it) }
                    }
                    MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                        val done = audioFile
                        val s = audioStart
                        audioFile = nextAudio
                        audioStart = System.currentTimeMillis()
                        if (done != null) addPiece(ctx, "audio", done, s, audioStart)
                    }
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                        AppLog.warn("Evidence", "Audio stopped at a file limit; restarting")
                        stopAudio(ctx)
                        startAudio(ctx)
                    }
                }
            }
            r.prepare()
            r.start()
            recorder = r
            update(ctx) { it.copy(audioOn = true) }
            AppLog.ok("Evidence", "Audio recording started")
        } catch (e: Exception) {
            recorder = null
            problem(ctx, "Audio couldn't start (${e.javaClass.simpleName})")
            AppLog.error("Evidence", "Audio couldn't start", e)
        }
    }

    private fun stopAudio(ctx: Context) {
        val r = recorder ?: return
        recorder = null
        val f = audioFile
        val s = audioStart
        runCatching { r.stop() }.onFailure { AppLog.warn("Evidence", "Audio stop", it) }
        r.release()
        if (f != null && f.length() > 0) addPiece(ctx, "audio", f, s, System.currentTimeMillis())
        audioFile = null
        update(ctx) { it.copy(audioOn = false) }
    }

    private fun startVideo(owner: LifecycleOwner, ctx: Context) {
        videoWanted = true
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .setTargetVideoEncodingBitRate(700_000) // 480p, faces readable; about 320 MB an hour instead of 540
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                val camera = if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                p.unbindAll()
                p.bindToLifecycle(owner, camera, vc)
                videoCapture = vc
                update(ctx) { it.copy(videoOn = true) }
                AppLog.ok("Evidence", "Video recording started (${if (camera == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"} camera)")
                nextClip(ctx)
            } catch (e: Exception) {
                videoWanted = false
                problem(ctx, "Camera couldn't start (${e.javaClass.simpleName})")
                AppLog.error("Evidence", "Camera couldn't start", e)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    @SuppressLint("MissingPermission")
    private fun nextClip(ctx: Context) {
        val vc = videoCapture ?: return
        if (!videoWanted) return
        val f = nextFile(ctx, "video", "mp4")
        val s = System.currentTimeMillis()
        videoRec = vc.output.prepareRecording(ctx, FileOutputOptions.Builder(f).setDurationLimitMillis(VIDEO_CLIP_MS).build())
            .start(ContextCompat.getMainExecutor(ctx)) { e ->
                if (e is VideoRecordEvent.Finalize) {
                    val ok = !e.hasError() || e.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                    if (ok && f.length() > 0) addPiece(ctx, "video", f, s, System.currentTimeMillis())
                    else {
                        f.delete()
                        if (videoWanted) AppLog.warn("Evidence", "A video clip failed (error ${e.error})")
                    }
                    if (videoWanted) nextClip(ctx) else update(ctx) { it.copy(videoOn = false) }
                }
            }
    }

    // ---- pieces + fingerprints ----

    private fun addPiece(ctx: Context, kind: String, file: File, start: Long, end: Long, expectedSha: String? = null) {
        val app = ctx.applicationContext
        val fix = SosEngine.current.value
        io.execute {
            val c = _active.value ?: return@execute
            val sha = sha256(file)
            if (expectedSha != null && !expectedSha.equals(sha, ignoreCase = true)) {
                AppLog.error("Evidence", "Watch audio ${file.name} arrived damaged (fingerprint mismatch)")
            }
            val prev = c.pieces.lastOrNull()?.chain ?: ZERO
            seqCounter += 1
            val piece = EvidencePiece(
                seq = seqCounter, kind = kind, file = file.name, startedAt = start, endedAt = end, bytes = file.length(),
                sha256 = sha, chain = sha256Text(prev + sha), lat = fix?.lat, lng = fix?.lng,
            )
            val next = c.copy(pieces = c.pieces + piece)
            save(app, next)
            EvidenceBackup.copy(app, next, file)
            EvidenceBackup.writeManifest(app, next)
            if (c.pieces.none { it.kind == kind }) AppLog.ok("Evidence", "First ${label(kind).lowercase()} piece saved")
        }
    }

    /** A 30 s piece recorded on the watch and copied over the watch link. */
    fun addWatchPiece(ctx: Context, incidentId: String, file: File, start: Long, end: Long, sha: String) {
        val app = ctx.applicationContext
        if (_active.value?.incidentId != incidentId) {
            val c = load(app, incidentId) ?: EvidenceCase(
                incidentId = incidentId, startedAt = start, trigger = "watch",
                device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}", app = "Security4Her ${BuildConfig.VERSION_NAME}",
            )
            _active.value = c
            seqCounter = c.pieces.maxOfOrNull { it.seq } ?: 0
        }
        addPiece(app, "watch_audio", file, start, end, sha)
    }

    fun watchFile(ctx: Context, incidentId: String, seq: Int) = File(dir(ctx, incidentId), "watch-%04d.m4a".format(Locale.US, seq))

    private fun problem(ctx: Context, text: String) = update(ctx) { if (text in it.problems) it else it.copy(problems = it.problems + text) }

    private fun update(ctx: Context, f: (EvidenceCase) -> EvidenceCase) {
        val app = ctx.applicationContext
        io.execute { _active.value?.let { save(app, f(it)) } }
    }

    private fun save(ctx: Context, c: EvidenceCase) {
        _active.value = c
        val m = manifest(ctx, c.incidentId)
        val tmp = File(m.parentFile, "manifest.tmp")
        tmp.writeText(S4hJson.encodeToString(EvidenceCase.serializer(), c))
        tmp.renameTo(m)
    }

    fun load(ctx: Context, id: String): EvidenceCase? =
        manifest(ctx, id).takeIf { it.exists() }?.let { runCatching { S4hJson.decodeFromString(EvidenceCase.serializer(), it.readText()) }.getOrNull() }

    fun cases(ctx: Context): List<EvidenceCase> =
        root(ctx).listFiles()?.filter { it.isDirectory }?.mapNotNull { load(ctx, it.name) }?.sortedByDescending { it.startedAt }.orEmpty()

    /** A piece's bytes: the app's own copy, or after the 24-hour lock only the verified copy in Downloads. */
    fun open(ctx: Context, c: EvidenceCase, p: EvidencePiece): java.io.InputStream? {
        val f = File(dir(ctx, c.incidentId), p.file)
        if (f.exists()) return f.inputStream()
        return EvidenceBackup.ref(ctx, c, p.file)?.let { EvidenceBackup.open(ctx, it) }
    }

    /** Something a player can open: a private file shared through our FileProvider, or the Downloads copy. */
    fun uri(ctx: Context, c: EvidenceCase, p: EvidencePiece): android.net.Uri? {
        val f = File(dir(ctx, c.incidentId), p.file)
        if (f.exists()) return androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val ref = EvidenceBackup.ref(ctx, c, p.file) ?: return null
        return if (ref.startsWith("content:")) android.net.Uri.parse(ref) else android.net.Uri.fromFile(File(ref))
    }

    /**
     * Saves space without touching a single recorded byte: once a case is past its 24-hour lock and every piece's
     * Downloads copy still matches its fingerprint, the app's duplicate is removed. Nothing is re-encoded, because a
     * re-encoded file would no longer match the fingerprint taken when it was recorded.
     */
    fun slim(ctx: Context) {
        if (Build.VERSION.SDK_INT < 29) return // Android 8-9 copies are plain files the app can't keep track of as safely
        val app = ctx.applicationContext
        io.execute {
            var freed = 0L
            cases(app).filter { !it.recording && System.currentTimeMillis() - (it.endedAt ?: it.startedAt) > LOCK_MS }.forEach { c ->
                c.pieces.forEach { p ->
                    val f = File(dir(app, c.incidentId), p.file)
                    val ref = EvidenceBackup.ref(app, c, p.file)
                    if (f.exists() && ref != null && EvidenceBackup.open(app, ref)?.use { sha256(it) } == p.sha256) {
                        freed += f.length(); f.delete()
                    }
                }
            }
            if (freed > 0) AppLog.info("Evidence", "Freed ${human(freed)}: older recordings now live only in Downloads (fingerprints checked)")
        }
    }

    /** Recomputes every fingerprint and the chain. */
    fun verify(ctx: Context, c: EvidenceCase): Integrity {
        var prev = ZERO
        val broken = mutableListOf<Int>()
        c.pieces.forEach { p ->
            val sha = runCatching { open(ctx, c, p)?.use { sha256(it) } }.getOrNull() ?: ""
            val chain = sha256Text(prev + p.sha256)
            if (sha != p.sha256 || chain != p.chain) broken += p.seq
            prev = p.chain
        }
        return Integrity(broken.isEmpty(), c.pieces.size, broken)
    }

    /** Zip of every file, the manifest, and a readable summary for police or a lawyer. */
    fun export(ctx: Context, c: EvidenceCase): File {
        val out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "security4her-evidence-${c.incidentId.take(8)}.zip")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US)
        val integrity = verify(ctx, c)
        val summary = buildString {
            appendLine("Security4Her evidence record")
            appendLine("SOS id: ${c.incidentId}")
            appendLine("Started: ${fmt.format(Date(c.startedAt))}")
            c.endedAt?.let { appendLine("Ended: ${fmt.format(Date(it))}") }
            appendLine("Recorded on: ${c.device} (${c.app})")
            appendLine("Integrity check at export: " + if (integrity.ok) "all ${integrity.checked} pieces intact" else "pieces ${integrity.broken} do not match")
            appendLine()
            appendLine("Each piece has a SHA-256 fingerprint. 'chain' = SHA-256(previous chain + this fingerprint),")
            appendLine("so removing, reordering or editing any piece changes every chain value after it.")
            appendLine()
            c.pieces.forEach { p ->
                appendLine(
                    "#${p.seq} ${p.kind} ${p.file}  ${fmt.format(Date(p.startedAt))} → ${fmt.format(Date(p.endedAt))}  ${p.bytes} bytes" +
                        (p.lat?.let { "  at %.5f,%.5f".format(Locale.US, it, p.lng) } ?: ""),
                )
                appendLine("    sha256 ${p.sha256}")
                appendLine("    chain  ${p.chain}")
            }
        }
        ZipOutputStream(out.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("README.txt")); z.write(summary.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest(ctx, c.incidentId).readBytes()); z.closeEntry()
            c.pieces.forEach { p ->
                open(ctx, c, p)?.use { z.putNextEntry(ZipEntry("files/${p.file}")); it.copyTo(z); z.closeEntry() }
            }
        }
        AppLog.info("Evidence", "Exported ${c.pieces.size} pieces (${human(out.length())})")
        return out
    }

    /** Evidence can't be deleted while recording or within 24 hours of the SOS, so no one can make her delete it on the spot. */
    fun deleteBlockedReason(c: EvidenceCase): String? = when {
        c.recording -> "Recording is still running."
        System.currentTimeMillis() - (c.endedAt ?: c.startedAt) < LOCK_MS -> "Evidence is locked for 24 hours after an SOS, so no one can force you to delete it."
        else -> null
    }

    fun delete(ctx: Context, c: EvidenceCase): Boolean {
        if (deleteBlockedReason(c) != null) return false
        val ok = File(root(ctx), c.incidentId).deleteRecursively()
        if (_active.value?.incidentId == c.incidentId) _active.value = null
        AppLog.info("Evidence", "Deleted evidence from ${SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(c.startedAt))}")
        return ok
    }

    /**
     * For services started while the app is on screen (Safe Journey, live sharing): also hold the microphone and camera
     * types, so an SOS that fires later in the background can still record. Falls back to location only.
     */
    fun holdForeground(s: android.app.Service, id: Int, n: android.app.Notification) {
        if (Build.VERSION.SDK_INT < 30) {
            if (Build.VERSION.SDK_INT >= 29) s.startForeground(id, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else s.startForeground(id, n)
            return
        }
        val loc = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val full = loc or (if (wantsAudio(s)) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
            (if (wantsVideo(s)) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        try {
            s.startForeground(id, n, full)
        } catch (e: Exception) {
            s.startForeground(id, n, loc)
        }
    }

    // ---- when Android blocks the mic/camera in the background ----

    /** Full-screen prompt (like an alarm) that brings the app forward so recording is allowed to start. */
    fun askToOpen(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("evidence") == null) {
            nm.createNotificationChannel(NotificationChannel("evidence", "Evidence recording", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            ctx, 30, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_START_EVIDENCE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, "evidence")
            .setSmallIcon(R.drawable.ic_notif_security4her)
            .setContentTitle("SOS active: tap to record evidence")
            .setContentText("Android needs the app open to start the microphone and camera.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(117, n) }
        AppLog.warn("Evidence", "SOS started in the background; asked to open the app so recording can start")
    }

    fun clearAsk(ctx: Context) = runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(117) }

    // ---- helpers ----
    /** Copies anything recorded before backups existed (once, in the background). */
    fun backfill(ctx: Context) {
        val app = ctx.applicationContext
        io.execute { cases(app).forEach { c -> EvidenceBackup.backfill(app, c) { p -> File(dir(app, c.incidentId), p.file) } } }
        slim(app)
    }

    fun label(kind: String) = when (kind) { "audio" -> "Phone audio"; "video" -> "Video"; else -> "Watch audio" }

    fun human(b: Long) = when {
        b < 1_000_000 -> "${b / 1000} KB"
        else -> "%.1f MB".format(Locale.US, b / 1_000_000.0)
    }

    internal fun sha256(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Text(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}


/**
 * A second copy of every piece in Download/Security4Her evidence/, outside the app's private folder. Android keeps
 * files there when the app is deleted, so uninstalling Security4Her (or someone forcing her to) can't destroy the
 * evidence. Each case folder holds the recordings plus fingerprints.txt, so anyone can check that nothing was altered.
 */
object EvidenceBackup {
    private const val ROOT = "Security4Her evidence"

    fun folder(c: EvidenceCase): String =
        ROOT + "/" + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(java.util.Date(c.startedAt)) + " " + c.incidentId.take(8)

    /** Where to point her in the app: the folder name as the Files app shows it. */
    fun where(c: EvidenceCase) = "Downloads / " + folder(c)

    private fun mime(name: String) = when {
        name.endsWith(".m4a") -> "audio/mp4"
        name.endsWith(".mp4") -> "video/mp4"
        else -> "text/plain"
    }

    private fun record(ctx: Context, c: EvidenceCase) = File(Evidence.dir(ctx, c.incidentId), "backup.json")

    private fun done(ctx: Context, c: EvidenceCase): MutableMap<String, String> = runCatching {
        val o = org.json.JSONObject(record(ctx, c).readText())
        o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun mark(ctx: Context, c: EvidenceCase, name: String, uri: String) {
        val m = done(ctx, c); m[name] = uri
        runCatching { record(ctx, c).writeText(org.json.JSONObject(m as Map<*, *>).toString()) }
    }

    fun ref(ctx: Context, c: EvidenceCase, name: String): String? = done(ctx, c)[name]

    fun open(ctx: Context, ref: String): java.io.InputStream? = runCatching {
        if (ref.startsWith("content:")) ctx.contentResolver.openInputStream(android.net.Uri.parse(ref)) else File(ref).inputStream()
    }.getOrNull()

    @Synchronized
    fun copy(ctx: Context, c: EvidenceCase, file: File) {
        if (!file.exists() || done(ctx, c).containsKey(file.name)) return
        val want = c.pieces.firstOrNull { it.file == file.name }?.sha256
        runCatching { write(ctx, c, file.name, overwrite = null) { out -> file.inputStream().use { it.copyTo(out) } } }
            .onSuccess { uri ->
                val got = open(ctx, uri)?.use { Evidence.sha256(it) }
                if (want == null || got == want) mark(ctx, c, file.name, uri)
                else AppLog.error("Evidence", "The Downloads copy of ${file.name} doesn't match its fingerprint; keeping the app's copy")
            }
            .onFailure { AppLog.warn("Evidence", "Couldn't copy ${file.name} to Downloads", it) }
    }

    @Synchronized
    fun writeManifest(ctx: Context, c: EvidenceCase) {
        val text = buildString {
            appendLine("Security4Her evidence")
            appendLine("Case ${c.incidentId}, started ${java.util.Date(c.startedAt)}, trigger: ${c.trigger}")
            appendLine("Recorded on ${c.device}, ${c.app}")
            appendLine()
            appendLine("Each file's SHA-256 fingerprint was taken the moment it was saved. If a file is changed in any way its")
            appendLine("fingerprint changes. Check one with: certutil -hashfile <file> SHA256 (Windows) or sha256sum <file>.")
            appendLine("The chain value links every piece to the one before it, so a removed or reordered piece is detectable too.")
            appendLine()
            c.pieces.forEach { p ->
                appendLine("${p.file}")
                appendLine("    ${Evidence.label(p.kind)}, ${java.util.Date(p.startedAt)} to ${java.util.Date(p.endedAt)}, ${p.bytes} bytes")
                appendLine("    sha256 ${p.sha256}")
                appendLine("    chain  ${p.chain}")
            }
        }
        val prev = done(ctx, c)["fingerprints.txt"]
        runCatching { write(ctx, c, "fingerprints.txt", overwrite = prev) { it.write(text.toByteArray()) } }
            .onSuccess { uri -> if (prev == null) mark(ctx, c, "fingerprints.txt", uri) }
            .onFailure { AppLog.warn("Evidence", "Couldn't save the fingerprint list to Downloads", it) }
    }

    fun backfill(ctx: Context, c: EvidenceCase, fileOf: (EvidencePiece) -> File) {
        val have = done(ctx, c)
        if (c.pieces.all { have.containsKey(it.file) } && have.containsKey("fingerprints.txt")) return
        c.pieces.forEach { copy(ctx, c, fileOf(it)) }
        writeManifest(ctx, c)
    }

    /** Writes into Downloads; returns a reference to the copy. [overwrite] = an earlier copy's reference to replace. */
    private fun write(ctx: Context, c: EvidenceCase, name: String, overwrite: String?, body: (java.io.OutputStream) -> Unit): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            if (overwrite != null) {
                val ok = runCatching { cr.openOutputStream(android.net.Uri.parse(overwrite), "wt")!!.use(body) }.isSuccess
                if (ok) return overwrite // else she deleted the old copy: make a new one
            }
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime(name))
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/" + folder(c))
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("Downloads refused the file")
            cr.openOutputStream(uri)!!.use(body)
            v.clear(); v.put(android.provider.MediaStore.Downloads.IS_PENDING, 0); cr.update(uri, v, null, null)
            return uri.toString()
        }
        // Android 8 and 9: plain files, which needs the storage permission
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) error("no storage permission")
        @Suppress("DEPRECATION")
        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), folder(c)).apply { mkdirs() }
        val f = File(dir, name)
        f.outputStream().use(body)
        return f.absolutePath
    }
}
