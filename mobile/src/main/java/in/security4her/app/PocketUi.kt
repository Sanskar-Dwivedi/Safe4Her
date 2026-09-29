package `in`.security4her.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.security4her.app.sos.KnockDetector
import `in`.security4her.app.sos.PocketSos
import kotlinx.coroutines.delay

/** Full screen over the lock screen while a pocket-triggered SOS counts down. */
@Composable
fun PocketCountdownScreen(seconds: Int) {
    val ctx = LocalContext.current
    BackHandler {} // a stray back press must not cancel an emergency
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text("SOS in", color = SosRed, style = MaterialTheme.typography.headlineMedium)
        Text("$seconds", color = SosRed, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 140.sp, lineHeight = 140.sp)
        Text(
            "Three knocks were detected on your phone. Your contacts will be alerted unless you cancel.",
            color = Muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        ChunkyButton("I'm okay, cancel", tone = Tone.Plain) { PocketSos.cancel(ctx) }
        Spacer(Modifier.height(12.dp))
        ChunkyButton("Send SOS now", tone = Tone.Danger) { PocketSos.sendNow(ctx) }
    }
}

private data class Hit(val strength: Float, val counted: Boolean)

/**
 * Learn the knock and tune it to this phone. The first time, her first three knocks set the strength (every phone's
 * sensor reads knocks differently); then she tries the real thing. A live meter shows the phone is reading her.
 * Nothing is sent from here.
 */
@Composable
fun PocketPracticeScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var threshold by remember { mutableFloatStateOf(PocketSos.loadThreshold(ctx)) }
    var tuning by remember { mutableStateOf(!PocketSos.calibrated(ctx)) }
    val tuneHits = remember { mutableStateListOf<Float>() }
    var count by remember { mutableIntStateOf(0) }
    var success by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf<String?>(null) }
    val hits = remember { mutableStateListOf<Hit>() }
    val good = remember { mutableStateListOf<Float>() } // strengths of knocks in successful triples
    var level by remember { mutableFloatStateOf(0f) }
    var rate by remember { mutableIntStateOf(-1) }
    BackHandler(onBack = onClose)

    val detRef = remember { arrayOfNulls<KnockDetector>(1) }
    fun addHit(h: Hit) { hits += h; if (hits.size > 8) hits.removeAt(0) }
    fun tune(strength: Float) {
        tuneHits += strength
        PocketSos.buzz(ctx, PocketSos.Buzz.Knock)
        if (tuneHits.size >= 3) {
            threshold = PocketSos.calibrate(ctx, tuneHits.toList())
            tuning = false; tuneHits.clear(); hits.clear(); count = 0
            hint = null
            detRef[0]?.reset()
            PocketSos.buzz(ctx, PocketSos.Buzz.Armed)
        }
    }

    val det = remember {
        KnockDetector(
            threshold = threshold,
            onKnock = { n, str ->
                if (tuning) { tune(str); return@KnockDetector }
                count = n; hint = null
                addHit(Hit(str, true))
                if (n < 3) PocketSos.buzz(ctx, PocketSos.Buzz.Knock)
            },
            onSoft = { str ->
                if (tuning) { if (str >= 2f) tune(str); return@KnockDetector }
                addHit(Hit(str, false)); hint = "Too soft. Knock a little firmer, or choose Light below."
            },
            onReject = { r ->
                if (tuning) return@KnockDetector
                count = 0
                hint = when (r) {
                    KnockDetector.Reject.TooSlow -> "Too slow. Keep the knocks closer together."
                    KnockDetector.Reject.TooMany -> "Just three knocks, then stop."
                    KnockDetector.Reject.Moving -> "Too much movement. Hold still for a second, then knock."
                }
            },
            onTriple = {
                if (tuning) return@KnockDetector
                success++; count = 3; hint = null
                good.addAll(hits.takeLast(3).filter { it.counted }.map { it.strength })
                PocketSos.buzz(ctx, PocketSos.Buzz.Armed)
            },
        ).also { detRef[0] = it }
    }

    DisposableEffect(Unit) {
        PocketSos.practicing = true
        var events = 0
        val sm = ctx.getSystemService(SensorManager::class.java)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                events++
                if (SystemClock.elapsedRealtime() < PocketSos.mutedUntil) { det.cancelJolt(); return }
                det.threshold = if (tuning) 2.8f else PocketSos.threshold // while tuning, any clear jolt is recorded as her knock
                det.feed(e.values[0], e.values[1], e.values[2], e.timestamp / 1_000_000)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc == null) rate = 0 else sm.registerListener(l, acc, 5_000)
        val counter = Thread {
            try { while (true) { Thread.sleep(1_000); rate = events; events = 0 } } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
        onDispose { sm.unregisterListener(l); counter.interrupt(); PocketSos.practicing = false }
    }
    // Live meter: follows the phone's movement, falling back smoothly
    LaunchedEffect(Unit) { while (true) { level = maxOf(det.level, level * 0.82f); delay(40) } }
    // The dots fade back after a pause, ready for the next try
    LaunchedEffect(count, success) { if (count > 0) { delay(1_800); count = 0 } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Back", onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = Muted)
        }
        Spacer(Modifier.height(8.dp))
        Text(if (tuning) "Teach it your knock" else "Practise the knock", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            if (tuning) "Hold the phone loosely or put it in your pocket, and knock on it 3 times the way you would in an emergency. " +
                "This sets how hard a knock needs to be on your phone."
            else "Knock three times with a short pause between each (about half a second). You'll feel a tick for each knock " +
                "and a strong buzz when all three count. Nothing is sent.",
            color = Muted, style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally)) {
            repeat(3) { i ->
                val lit = if (tuning) i < tuneHits.size else i < count
                val k by animateFloatAsState(if (lit) 1f else 0.7f, spring(dampingRatio = 0.4f), label = "dot$i")
                Box(Modifier.size(60.dp).graphicsLayer { scaleX = k; scaleY = k }.clip(CircleShape).background(if (lit) Lilac else Surface2))
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 34.dp), contentAlignment = Alignment.Center) {
            when {
                tuning -> Text(
                    when (tuneHits.size) { 0 -> "Knock 3 times…"; 1 -> "Got one. Two more…"; else -> "One more…" },
                    color = Muted, style = MaterialTheme.typography.titleMedium,
                )
                hint != null -> Text(hint!!, color = Amber, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                success > 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, tint = OnMint, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(if (success == 1) "That's it! Detected once." else "Detected $success times.", style = MaterialTheme.typography.titleMedium)
                }
                else -> Text("Set to your knock. Now try: knock 3 times.", color = Mint, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }

        // Live meter: proof the phone is reading her, with the line a knock has to reach
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Movement right now", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                when (rate) { -1 -> ""; 0 -> "No motion sensor data"; else -> "Reading $rate×/s" },
                color = if (rate == 0) SosRed else Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        LiveMeter(level, if (tuning) null else threshold)
        if (rate == 0) Text(
            "This phone isn't sending motion data, so knocks can't be detected. Restart the phone and try again.",
            color = SosRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
        )

        if (!tuning) {
            Spacer(Modifier.height(20.dp))
            Text("Your knocks", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            StrengthChart(hits, threshold)
            Spacer(Modifier.height(18.dp))
            Text("How hard to knock", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PocketSos.Sensitivity.entries.forEach { s ->
                    val sel = kotlin.math.abs(threshold - s.threshold) < 0.05f
                    Text(
                        s.label, color = if (sel) OnLilac else Ink, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (sel) Lilac else Surface1)
                            .clickable { PocketSos.setThreshold(ctx, s.threshold); threshold = PocketSos.threshold }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            if (PocketSos.Sensitivity.entries.none { kotlin.math.abs(threshold - it.threshold) < 0.05f }) {
                Text("Set to your own knock", color = Lilac, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(16.dp))
            ChunkyButton(if (good.size >= 3) "Set to my latest knocks" else "Teach it my knock again", tone = Tone.Plain) {
                if (good.size >= 3) { threshold = PocketSos.calibrate(ctx, good.takeLast(6)); hint = null }
                else { tuning = true; tuneHits.clear(); hint = null }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Light: gentler knocks count, better through a thick bag. Firm: only hard knocks, fewest accidental alerts.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LiveMeter(level: Float, threshold: Float?) {
    val max = maxOf((threshold ?: 4.5f) * 2f, 6f)
    val f by animateFloatAsState((level / max).coerceIn(0f, 1f), tween(60), label = "meter")
    Box(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp)).background(Surface1)) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(13.dp))
                .background(if (threshold != null && level >= threshold) Mint else Lilac.copy(alpha = 0.8f)),
        )
        if (threshold != null) {
            val at = (threshold / max).coerceIn(0f, 1f)
            Box(Modifier.fillMaxHeight().fillMaxWidth(at)) {
                Box(Modifier.fillMaxHeight().width(2.dp).align(Alignment.CenterEnd).background(Color.White.copy(alpha = 0.8f)))
            }
        }
    }
}

@Composable
private fun StrengthChart(hits: List<Hit>, threshold: Float) {
    val max = maxOf(threshold * 2f, hits.maxOfOrNull { it.strength } ?: 0f)
    val h = 110.dp
    Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(16.dp)).background(Surface1).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            if (hits.isEmpty()) Text("Your knocks show up here", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
            hits.forEach { hit ->
                val f by animateFloatAsState((hit.strength / max).coerceIn(0.05f, 1f), tween(250), label = "bar")
                Box(Modifier.weight(1f).fillMaxHeight(f).clip(RoundedCornerShape(6.dp)).background(if (hit.counted) Mint else Amber))
            }
            repeat((8 - hits.size).coerceAtLeast(0).takeIf { hits.isNotEmpty() } ?: 0) { Spacer(Modifier.weight(1f)) }
        }
        // the line a knock has to reach
        val line = (threshold / max).coerceIn(0f, 1f)
        if (hits.isNotEmpty()) Box(Modifier.fillMaxWidth().fillMaxHeight(line).align(Alignment.BottomStart)) {
            Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopStart).background(Color.White.copy(alpha = 0.7f)))
        }
    }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Mint)); Text("  Counted", color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(10.dp).clip(CircleShape).background(Amber)); Text("  Too soft", color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(width = 14.dp, height = 2.dp).background(Color.White.copy(alpha = 0.7f))); Text("  Needed", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
