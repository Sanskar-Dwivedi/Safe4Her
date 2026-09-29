package `in`.security4her.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

// ---------------------------------------------------------------- Voice SOS
// Listens on the watch for "HELP HELP HELP" and starts the usual SOS countdown. Everything runs on the watch:
// no audio is stored or sent anywhere. It is its own feature with its own switch, separate from Safety Mode.

object VoiceSos {
    private const val TAG = "S4hVoice"

    enum class Status { Off, Loading, Listening, Hearing, Paused, NoPermission, Failed }

    private val _status = MutableStateFlow(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _heard = MutableStateFlow(0)
    /** Helps heard in a row right now (0-3), for the dots on the watch. */
    val heard: StateFlow<Int> = _heard.asStateFlow()

    private val _practicing = MutableStateFlow(false)
    /** While the practice screen is open, the background listener steps aside and nothing triggers an SOS. */
    val practicing: StateFlow<Boolean> = _practicing.asStateFlow()

    fun setPracticing(on: Boolean) { _practicing.value = on }

    /** No second trigger within 20 s of the last one, even after the listener restarts (e.g. a cancelled countdown with the TV still on). */
    private const val COOLDOWN_MS = 20_000L
    @Volatile private var lastTriggerAt = 0L

    /** Returns true if this detection may start a countdown, and starts the cooldown. */
    @Synchronized
    fun claimTrigger(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < COOLDOWN_MS) return false
        lastTriggerAt = now
        return true
    }

    internal fun report(s: Status) { _status.value = s }
    internal fun reportHeard(n: Int) { _heard.value = n }

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---- the speech model: copied out of the APK once, then loaded into memory once ----

    private const val ASSET_DIR = "model-en-us"
    @Volatile private var model: Model? = null

    @Synchronized
    fun model(ctx: Context): Model {
        model?.let { return it }
        val app = ctx.applicationContext
        val version = runCatching { app.assets.open("$ASSET_DIR/uuid").bufferedReader().use { it.readText().trim() } }.getOrDefault("model")
        val dir = File(app.filesDir, "voice/$version")
        if (!File(dir, "ready").exists()) {
            dir.deleteRecursively()
            copyAssets(app, ASSET_DIR, dir)
            File(dir, "ready").writeText("ok")
        }
        return Model(dir.absolutePath).also { model = it }
    }

    private fun copyAssets(ctx: Context, from: String, to: File) {
        val children = ctx.assets.list(from).orEmpty()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            ctx.assets.open(from).use { input -> to.outputStream().use { input.copyTo(it) } }
            return
        }
        to.mkdirs()
        for (c in children) copyAssets(ctx, "$from/$c", File(to, c))
    }
}

/**
 * Microphone → "HELP HELP HELP" detector. Runs on its own thread between [start] and [stop].
 *
 * To save battery the recogniser only runs while someone is talking: a simple level gate tracks the
 * background noise and wakes the recogniser when sound rises clearly above it (with 0.5 s of audio kept from
 * just before, so the first "help" isn't clipped). It goes back to sleep after 1.2 s of quiet.
 */
class VoiceListener(
    private val ctx: Context,
    private val onPhrase: () -> Unit,
) {
    private val logic = VoicePhraseLogic()
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning get() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "s4h-voice").apply { isDaemon = true; start() }
    }

    /** Stops and waits for the microphone to be released (so the SOS recorder can take it straight after). */
    fun stop() {
        running = false
        thread?.let { runCatching { it.join(1_500) } }
        thread = null
        VoiceSos.reportHeard(0)
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        if (!VoiceSos.hasPermission(ctx)) { VoiceSos.report(VoiceSos.Status.NoPermission); running = false; return }
        VoiceSos.report(VoiceSos.Status.Loading)
        val model = try {
            VoiceSos.model(ctx)
        } catch (e: Throwable) {
            Log.e(TAG, "speech model failed to load", e)
            VoiceSos.report(VoiceSos.Status.Failed); running = false; return
        }
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 2 * 8))
        } catch (e: Exception) {
            Log.e(TAG, "microphone unavailable", e)
            VoiceSos.report(VoiceSos.Status.Failed); running = false; return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); VoiceSos.report(VoiceSos.Status.Failed); running = false; return
        }

        val frame = ShortArray(FRAME)
        val preRoll = ArrayDeque<ShortArray>()
        val levels = ArrayDeque<Pair<Long, Float>>() // (time ms, dBFS) for word loudness
        var noiseDb = -60f
        var recognizer: Recognizer? = null
        var sessionStartMs = 0L
        var sessionFloor = noiseDb
        var lastLoudMs = 0L
        var clockMs = 0L // audio time, advanced by samples read, so it never drifts from the recogniser's timeline

        fun closeSession(final: Boolean) {
            val r = recognizer ?: return
            if (final) handle(r.finalResult, sessionStartMs, sessionFloor, levels)
            r.close()
            recognizer = null
            VoiceSos.report(VoiceSos.Status.Listening)
        }

        try {
            rec.startRecording()
            VoiceSos.report(VoiceSos.Status.Listening)
            while (running) {
                val n = rec.read(frame, 0, FRAME)
                if (n <= 0) { Thread.sleep(20); continue }
                clockMs += n * 1000L / RATE
                val db = dbfs(frame, n)
                levels.addLast(clockMs to db)
                while (levels.isNotEmpty() && clockMs - levels.first().first > 20_000) levels.removeFirst()

                val r = recognizer
                if (r == null) {
                    // Idle: learn the background, and wake when sound rises clearly above it
                    noiseDb += (db - noiseDb) * 0.02f
                    preRoll.addLast(frame.copyOf(n))
                    if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    if (db > noiseDb + WAKE_DB && db > MIN_SPEECH_DB) {
                        val fresh = Recognizer(model, RATE.toFloat(), GRAMMAR).apply { setWords(true) }
                        recognizer = fresh
                        sessionStartMs = clockMs - preRoll.sumOf { it.size } * 1000L / RATE
                        sessionFloor = noiseDb
                        lastLoudMs = clockMs
                        VoiceSos.report(VoiceSos.Status.Hearing)
                        for (buf in preRoll) if (fresh.acceptWaveForm(buf, buf.size)) handle(fresh.result, sessionStartMs, sessionFloor, levels)
                        preRoll.clear()
                    }
                } else {
                    if (db > sessionFloor + QUIET_DB) lastLoudMs = clockMs
                    if (r.acceptWaveForm(frame, n)) handle(r.result, sessionStartMs, sessionFloor, levels)
                    val quietFor = clockMs - lastLoudMs
                    val longFor = clockMs - sessionStartMs
                    if (quietFor > SLEEP_AFTER_MS || longFor > MAX_SESSION_MS) closeSession(final = true)
                }
                logic.expire(clockMs)
                VoiceSos.reportHeard(logic.count)
            }
        } catch (e: Exception) {
            Log.e(TAG, "listening stopped", e)
            VoiceSos.report(VoiceSos.Status.Failed)
        } finally {
            runCatching { recognizer?.close() }
            runCatching { rec.stop() }
            rec.release()
            if (VoiceSos.status.value != VoiceSos.Status.Failed) VoiceSos.report(VoiceSos.Status.Off)
        }
    }

    /** Turns one recogniser result into words with absolute times and loudness, and feeds the phrase logic. */
    private fun handle(json: String, sessionStartMs: Long, floorDb: Float, levels: ArrayDeque<Pair<Long, Float>>) {
        val words = runCatching { JSONObject(json).optJSONArray("result") }.getOrNull() ?: return
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            val start = sessionStartMs + (w.optDouble("start") * 1000).toLong()
            val end = sessionStartMs + (w.optDouble("end") * 1000).toLong()
            val peak = levels.filter { it.first in start..end }.maxOfOrNull { it.second } ?: -90f
            val word = VoicePhraseLogic.Word(
                text = w.optString("word"), conf = w.optDouble("conf", 0.0).toFloat(),
                startMs = start, endMs = end, loud = peak >= floorDb + LOUD_DB && peak >= MIN_SPEECH_DB,
            )
            Log.d(TAG, "heard ${word.text} conf=${"%.2f".format(word.conf)} peak=${"%.0f".format(peak)}dB floor=${"%.0f".format(floorDb)}dB")
            if (logic.onWord(word)) {
                Log.i(TAG, "HELP HELP HELP detected")
                VoiceSos.reportHeard(3)
                onPhrase()
            }
        }
    }

    private fun dbfs(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        val rms = sqrt(sum / n.coerceAtLeast(1))
        return if (rms < 1) -90f else (20 * log10(rms / 32768.0)).toFloat()
    }

    companion object {
        private const val TAG = "S4hVoice"
        private const val RATE = 16_000
        private const val FRAME = 320 // 20 ms
        private const val PRE_ROLL_FRAMES = 25 // 0.5 s
        private const val WAKE_DB = 9f // wake the recogniser this far above the background
        private const val QUIET_DB = 5f
        private const val LOUD_DB = 10f // a "help" must peak this far above the background
        private const val MIN_SPEECH_DB = -50f // ignore anything this faint however quiet the room is
        private const val SLEEP_AFTER_MS = 1_200L
        private const val MAX_SESSION_MS = 12_000L
        /**
         * What the recogniser may hear. Besides "help" it knows a few words that sound like it, so "hello hello
         * hello" or "hey hey hey" come out as themselves instead of being forced into "help". Tested on 78
         * recordings (two voices, clean and in street noise): every "HELP HELP HELP" caught, no false triggers.
         * Adding more near-words (health, hell, held…) made it miss real helps in noise, so the list stays short.
         */
        private const val GRAMMAR = "[\"help\", \"hello\", \"hey\", \"hi\", \"yelp\", \"kelp\", \"well\", \"yell\", \"[unk]\"]"
    }
}
