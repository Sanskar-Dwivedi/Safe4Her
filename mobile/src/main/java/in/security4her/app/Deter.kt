package `in`.security4her.app

import android.app.Activity
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.security4her.app.core.AppLog
import `in`.security4her.app.core.Store
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

// ---------------------------------------------------------------- Deterrents: fake call and siren
// Two ways out of a situation that isn't an emergency yet: a believable incoming call to excuse herself, and a loud
// siren with a flashing torch to draw attention. Neither contacts anyone.

private fun Context.keepScreenOn(on: Boolean) {
    val w = (this as? Activity)?.window ?: return
    if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

private fun Context.vibrator(): Vibrator =
    if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
    else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)

// ---------------------------------------------------------------- Fake call

private enum class CallPhase { Setup, Waiting, Ringing, Talking }

@Composable
fun FakeCallScreen(onClose: () -> Unit, startImmediately: Boolean = false) {
    val ctx = LocalContext.current
    val prefs = remember { Store.prefs(ctx) }
    var caller by remember { mutableStateOf(prefs.getString("fake_caller", "Mom")!!) }
    var phase by remember { mutableStateOf(CallPhase.Setup) }
    var waitLeft by remember { mutableIntStateOf(0) }
    BackHandler { if (phase == CallPhase.Setup) onClose() else phase = CallPhase.Setup }
    DisposableEffect(Unit) { ctx.keepScreenOn(true); onDispose { ctx.keepScreenOn(false) } }

    fun ring(after: Int) {
        prefs.edit().putString("fake_caller", caller.trim().ifBlank { "Mom" }).apply()
        AppLog.info("Fake call", "Incoming call from ${caller.trim()} in $after s")
        waitLeft = after
        phase = if (after == 0) CallPhase.Ringing else CallPhase.Waiting
    }

    LaunchedEffect(startImmediately) {
        if (startImmediately) ring(0)
    }

    LaunchedEffect(phase) {
        if (phase == CallPhase.Waiting) {
            while (waitLeft > 0) { delay(1_000); waitLeft-- }
            phase = CallPhase.Ringing
        }
    }

    when (phase) {
        CallPhase.Setup -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Back", onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = Muted)
            }
            Spacer(Modifier.height(8.dp))
            Text("Fake call", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Your phone rings with a real-looking call so you can step away. Answer it and a voice talks to you. Nobody is actually called.",
                color = Muted, style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(22.dp))
            Field(caller, { caller = it.take(24) }, "Who's calling")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Mom", "Dad", "Brother", "Office").forEach { n ->
                    val sel = caller.trim().equals(n, ignoreCase = true)
                    Text(
                        n, color = if (sel) OnLilac else Ink, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Lilac else Surface2).clickable { caller = n }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            ChunkyButton("Ring now", icon = Icons.Filled.Call) { ring(0) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChunkyButton("In 10 s", Modifier.weight(1f), tone = Tone.Plain) { ring(10) }
                ChunkyButton("In 30 s", Modifier.weight(1f), tone = Tone.Plain) { ring(30) }
            }
            Spacer(Modifier.height(20.dp))
        }
        // Dim and quiet while she waits, so the phone looks idle in her hand.
        CallPhase.Waiting -> Box(Modifier.fillMaxSize().background(Color.Black).clickable { phase = CallPhase.Setup }, contentAlignment = Alignment.BottomCenter) {
            Text("Ringing in $waitLeft s · tap to cancel", color = Color.White.copy(alpha = 0.25f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(32.dp))
        }
        CallPhase.Ringing -> Ringing(caller.trim().ifBlank { "Mom" }, onAnswer = { phase = CallPhase.Talking }, onDecline = onClose)
        CallPhase.Talking -> Talking(caller.trim().ifBlank { "Mom" }, onEnd = onClose)
    }
}

@Composable
private fun CallBackdrop(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B2A3A), Color(0xFF0B0F14)))).padding(horizontal = 28.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun CallerHeader(name: String, line: String) {
    Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF3B5B7A)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(20.dp))
    Text(name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(line, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
}

@Composable
private fun RoundCallButton(color: Color, label: String, rotate: Float = 0f, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(color).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Call, label, tint = Color.White, modifier = Modifier.size(32.dp).rotate(rotate))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
    }
}

@Composable
private fun Ringing(name: String, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val tone: Ringtone? = runCatching {
            RingtoneManager.getRingtone(ctx, RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        }.getOrNull()
        tone?.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
        if (Build.VERSION.SDK_INT >= 28) tone?.isLooping = true
        runCatching { tone?.play() }
        runCatching { ctx.vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 700), 0)) }
        onDispose { runCatching { tone?.stop() }; runCatching { ctx.vibrator().cancel() } }
    }
    val pulse = rememberInfiniteTransition(label = "ring")
    val k by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "k")
    CallBackdrop {
        CallerHeader(name, "Mobile · incoming call")
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundCallButton(Color(0xFFE5484D), "Decline", rotate = 135f, onClick = onDecline)
            Box(Modifier.graphicsLayer { scaleX = k; scaleY = k }) { RoundCallButton(Color(0xFF30A46C), "Answer", onClick = onAnswer) }
        }
    }
}

@Composable
private fun Talking(name: String, onEnd: () -> Unit) {
    val ctx = LocalContext.current
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); seconds++ } }
    DisposableEffect(Unit) {
        // A caring voice on the other end, so it sounds like a real call if someone is listening.
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
                tts?.setSpeechRate(0.95f)
                tts?.speak(
                    "Hi! Where are you right now? I'm close by, I'm coming to pick you up. Stay where you are, I'll be there in five minutes. " +
                        "Keep talking to me, okay?",
                    TextToSpeech.QUEUE_FLUSH, null, "fake-call",
                )
            }
        }
        onDispose { runCatching { tts?.stop(); tts?.shutdown() } }
    }
    CallBackdrop {
        CallerHeader(name, "%02d:%02d".format(seconds / 60, seconds % 60))
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("Mute" to "🔇", "Keypad" to "🔢", "Speaker" to "🔊").forEach { (l, glyph) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(60.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Text(glyph, fontSize = 22.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(l, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
        RoundCallButton(Color(0xFFE5484D), "End", rotate = 135f, onClick = onEnd)
    }
}

// ---------------------------------------------------------------- Siren

/** A loud rising-and-falling siren on the alarm stream (plays even on silent), the screen flashing and the torch strobing. */
@Composable
fun SirenScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    DisposableEffect(Unit) {
        ctx.keepScreenOn(true)
        AppLog.info("Siren", "Siren on")
        val siren = Siren(ctx).also { it.start() }
        onDispose { siren.stop(); ctx.keepScreenOn(false); AppLog.info("Siren", "Siren off") }
    }
    val flash = rememberInfiniteTransition(label = "siren")
    val on by flash.animateFloat(0f, 1f, infiniteRepeatable(tween(250), RepeatMode.Reverse), label = "on")
    Box(
        Modifier.fillMaxSize().background(if (on > 0.5f) SosRed else Color.White).clickable(onClickLabel = "Stop siren", onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("HELP", color = if (on > 0.5f) Color.White else SosRed, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 96.sp)
            Spacer(Modifier.height(12.dp))
            Text("Tap anywhere to stop", color = if (on > 0.5f) Color.White else Color.Black, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private class Siren(private val ctx: Context) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val camera = ctx.getSystemService(CameraManager::class.java)
    private val torchId: String? = runCatching {
        camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()
    private var track: AudioTrack? = null
    private var strobe: Thread? = null
    private var savedVolume = -1

    fun start() {
        runCatching {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        track = runCatching { tone().apply { play() } }.getOrNull()
        runCatching { ctx.vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200), 0)) }
        torchId?.let { id ->
            strobe = Thread {
                var lit = false
                try {
                    while (!Thread.currentThread().isInterrupted) { lit = !lit; camera.setTorchMode(id, lit); Thread.sleep(120) }
                } catch (_: InterruptedException) {
                } catch (_: Exception) {
                } finally { runCatching { camera.setTorchMode(id, false) } }
            }.apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        runCatching { track?.stop(); track?.release() }
        track = null
        strobe?.interrupt(); strobe = null
        runCatching { ctx.vibrator().cancel() }
        if (savedVolume >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
    }

    /** One 1.2 s up-and-down sweep between 650 and 1500 Hz, looped. */
    private fun tone(): AudioTrack {
        val rate = 22_050
        val frames = (rate * 1.2).toInt()
        val pcm = ShortArray(frames)
        var phase = 0.0
        for (i in 0 until frames) {
            val t = i / frames.toDouble()
            val f = 650 + 850 * (if (t < 0.5) t * 2 else (1 - t) * 2)
            phase += 2 * PI * f / rate
            pcm[i] = (sin(phase) * Short.MAX_VALUE * 0.95).toInt().toShort()
        }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(frames * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, frames)
        t.setLoopPoints(0, frames, -1)
        return t
    }
}
