package `in`.security4her.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import `in`.security4her.app.data.ActiveIncident
import `in`.security4her.app.data.DeviceLink
import `in`.security4her.app.data.PhoneStatus
import `in`.security4her.app.data.WatchStore
import `in`.security4her.app.sos.PhoneLink
import `in`.security4her.app.sos.FakeCallController
import `in`.security4her.app.sos.SosController
import `in`.security4her.app.sos.WatchUpdater
import `in`.security4her.app.sos.WristTwistDetector
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

interface WatchActions {
    fun requestPermissions()
    fun finishSetup()
    fun sosNow()
    fun cancelCountdown()
    fun imSafe()
    fun dismiss()
    fun setSafetyMode(on: Boolean)
    fun setVoiceSos(on: Boolean)
    fun setFakeCallTwist(on: Boolean)
}

private object C {
    val bg = Color(0xFF000000)
    val purple = Color(0xFF5B2A86)
    val purpleLight = Color(0xFFB794F4)
    val coral = Color(0xFFFF6F61)
    val red = Color(0xFFE53935)
    val green = Color(0xFF43A047)
    val amber = Color(0xFFFFB300)
    val grey = Color(0xFFB0B0B0)
    val white = Color.White
}

@Composable
fun WatchUi(store: WatchStore, actions: WatchActions) {
    val setupDone by store.setupDone.collectAsStateWithLifecycle(initialValue = true)
    val incident by store.incident.collectAsStateWithLifecycle(initialValue = null)
    val phoneStatus by store.phoneStatus.collectAsStateWithLifecycle(initialValue = null)
    val link by store.link.collectAsStateWithLifecycle(initialValue = null)
    val safety by store.safetyMode.collectAsStateWithLifecycle(initialValue = false)
    val voiceOn by store.voiceSos.collectAsStateWithLifecycle(initialValue = false)
    val fakeCallTwist by store.fakeCallTwist.collectAsStateWithLifecycle(initialValue = false)
    val fakeCallStatus by FakeCallController.status.collectAsStateWithLifecycle()
    var showVoice by remember { mutableStateOf(false) }
    val betaAgreed by `in`.security4her.app.beta.WatchBeta.agreed.collectAsStateWithLifecycle()
    var showBeta by remember { mutableStateOf(false) }
    val countdown by SosController.countdown.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val phoneReachable by produceState<Boolean?>(initialValue = null) {
        while (true) {
            value = PhoneLink.isPhoneReachable(context)
            delay(10_000)
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(C.bg), contentAlignment = Alignment.Center) {
            val cd = countdown
            val inc = incident
            when {
                cd != null -> CountdownScreen(cd, actions)
                inc != null -> IncidentScreen(inc, phoneStatus, link, actions)
                !setupDone -> SetupScreen(actions)
                betaAgreed == null || showBeta -> BetaScreen { showBeta = false }
                showVoice -> VoiceSosScreen(voiceOn, onToggle = { actions.setVoiceSos(it) }, onBack = { showVoice = false })
                else -> HomeScreen(link, phoneReachable, safety, voiceOn, fakeCallTwist, fakeCallStatus, actions,
                    onVoice = { showVoice = true }, onBeta = { showBeta = true })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun Screen(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Label(
    text: String,
    color: Color = C.white,
    size: TextUnit = 14.sp,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(text = text, color = color, fontSize = size, fontWeight = weight, textAlign = TextAlign.Center)
}

@Composable
private fun BrandTitle() {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(`in`.security4her.app.R.drawable.logo_security4her),
            contentDescription = null,
            modifier = Modifier.size(26.dp).clip(RoundedCornerShape(5.dp)),
        )
        Label("Security4Her", color = C.purpleLight, size = 14.sp, weight = FontWeight.Bold)
    }
}

@Composable
private fun RoundButton(label: String, color: Color, diameter: Dp, textSize: TextUnit, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(color)
            .border(3.dp, C.white.copy(alpha = 0.25f), CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Label(label, size = textSize, weight = FontWeight.Bold) }
}

@Composable
private fun PillButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Label(label, size = 15.sp, weight = FontWeight.SemiBold) }
}

private enum class Tone(val color: Color) { OK(C.green), WAIT(C.amber), FAIL(C.red), INFO(C.grey) }

@Composable
private fun StatusRow(name: String, value: String, tone: Tone) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = name, color = C.grey, fontSize = 12.sp)
        Spacer(Modifier.size(6.dp))
        Text(text = value, color = tone.color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun SetupScreen(actions: WatchActions) {
    Screen {
        BrandTitle()
        Label("Allow location and notifications so an SOS can include where you are.", size = 13.sp)
        PillButton("Allow", C.purple) { actions.requestPermissions() }
        Label(
            "Then set the Samsung Knock Knock gesture to open Security4Her. " +
                "After setup, opening the app starts a 5 s SOS countdown.",
            color = C.grey, size = 11.sp,
        )
        PillButton("Skip for now", Color(0xFF333333)) { actions.finishSetup() }
    }
}

@Composable
private fun HomeScreen(
    link: DeviceLink?, phoneReachable: Boolean?, safety: Boolean, voiceOn: Boolean,
    fakeCallTwist: Boolean, fakeCallStatus: String?, actions: WatchActions, onVoice: () -> Unit,
    onBeta: () -> Unit = {},
) {
    Screen {
        BrandTitle()
        RoundButton("SOS", C.red, 104.dp, 30.sp) { actions.sosNow() }
        PillButton(if (safety) "Safety Mode: ON" else "Safety Mode: OFF", if (safety) C.purple else Color(0xFF333333)) {
            actions.setSafetyMode(!safety)
        }
        if (safety) Label("Shake your wrist hard 3 times to start SOS", color = C.grey, size = 11.sp)
        PillButton(if (voiceOn) "Voice SOS: ON" else "Voice SOS: OFF", if (voiceOn) C.purple else Color(0xFF333333)) {
            actions.setVoiceSos(!voiceOn)
        }
        VoiceHint(voiceOn, onVoice)
        if (WristTwistDetector.available(LocalContext.current)) {
            PillButton(if (fakeCallTwist) "Fake call twist: ON" else "Fake call twist: OFF",
                if (fakeCallTwist) C.purple else Color(0xFF333333)) { actions.setFakeCallTwist(!fakeCallTwist) }
            if (fakeCallTwist) Label("Twist wrist out and back twice to ring your phone", color = C.grey, size = 11.sp)
        } else {
            Label("Fake call twist needs a gyroscope on this watch", color = C.grey, size = 11.sp)
        }
        if (fakeCallStatus != null) Label(fakeCallStatus, color = if (fakeCallStatus == "Phone alert ready") C.green else C.coral, size = 11.sp)
        StatusRow(
            "Phone",
            when (phoneReachable) { true -> "connected"; false -> "not reachable"; null -> "checking…" },
            when (phoneReachable) { true -> Tone.OK; false -> Tone.FAIL; null -> Tone.INFO },
        )
        StatusRow(
            "Account",
            link?.displayName?.ifBlank { "linked" } ?: "not linked",
            if (link != null) Tone.OK else Tone.WAIT,
        )
        if (link == null) Label("Open Security4Her on your phone and log in to link this watch.", color = C.grey, size = 11.sp)
        UpdateBlock()
        Text(text = if (`in`.security4her.app.beta.WatchBeta.agreed.value == true) "Beta test data: sharing  ›" else "Beta test data: off  ›", color = C.grey, fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Beta test data", onClick = onBeta).padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** The beta agreement on the watch: one clear yes or no. Saying no (later) deletes what was sent. */
@Composable
private fun BetaScreen(onDone: () -> Unit) {
    Screen {
        Label("Security4Her Beta", color = C.purpleLight, size = 15.sp, weight = FontWeight.Bold)
        Label("Help us test it: share anonymous crash, error, false-alarm and speed data from this watch?", size = 12.sp)
        Label("Never your location, voice, recordings, name or contacts. Turn it off any time here; that deletes what was sent.", color = C.grey, size = 10.sp)
        PillButton("I agree", C.purple) { `in`.security4her.app.beta.WatchBeta.decide(true); onDone() }
        PillButton("No thanks", Color(0xFF333333)) { `in`.security4her.app.beta.WatchBeta.decide(false); onDone() }
    }
}

/** Under the Voice SOS switch: what to say, live status, and the way into practice. */
@Composable
private fun VoiceHint(on: Boolean, onMore: () -> Unit) {
    val status by `in`.security4her.app.voice.VoiceSos.status.collectAsStateWithLifecycle()
    val (text, color) = when {
        !on -> "Say HELP HELP HELP to start SOS" to C.grey
        status == `in`.security4her.app.voice.VoiceSos.Status.Listening || status == `in`.security4her.app.voice.VoiceSos.Status.Hearing ->
            "Listening for HELP HELP HELP" to C.green
        status == `in`.security4her.app.voice.VoiceSos.Status.NoPermission -> "Allow the microphone to use this" to C.coral
        status == `in`.security4her.app.voice.VoiceSos.Status.Failed -> "Microphone busy. Turn off and on." to C.coral
        else -> "Getting ready…" to C.amber
    }
    Label(text, color = color, size = 11.sp)
    Text(
        text = "Practise  ›", color = C.purpleLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Practise Voice SOS", onClick = onMore).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Updates install by themselves; this only shows what's happening, with a manual button as a fallback. */
@Composable
private fun UpdateBlock() {
    val ctx = LocalContext.current
    val s by WatchUpdater.state.collectAsStateWithLifecycle()
    when (val x = s) {
        is WatchUpdater.State.Available -> {
            Label("Version ${x.r.name} installs by itself", color = C.grey, size = 11.sp)
            PillButton("Update now", Color(0xFF333333)) { WatchUpdater.update(ctx, x.r) }
        }
        is WatchUpdater.State.Downloading -> Label("Updating… ${(x.progress * 100).toInt()}%", color = C.purpleLight, size = 12.sp)
        is WatchUpdater.State.Installing -> Label("Installing update…", color = C.purpleLight, size = 12.sp)
        is WatchUpdater.State.Failed -> {
            Label(x.message, color = C.coral, size = 11.sp)
            x.r?.let { r -> PillButton("Try again", Color(0xFF333333)) { WatchUpdater.update(ctx, r) } }
        }
        else -> Label("Version ${WatchUpdater.versionName} · up to date", color = C.grey, size = 11.sp)
    }
}

@Composable
private fun CountdownScreen(countdown: SosController.Countdown, actions: WatchActions) {
    Screen {
        Label("SOS in", color = C.coral, size = 16.sp, weight = FontWeight.Bold)
        Label("${countdown.secondsLeft}", size = 56.sp, weight = FontWeight.Bold)
        Label(
            when (countdown.trigger) {
                "shake" -> "Shake detected"; "knock" -> "Gesture detected"; "voice" -> "Heard HELP HELP HELP"; else -> "SOS pressed"
            },
            color = C.grey, size = 12.sp,
        )
        RoundButton("Cancel", Color(0xFF424242), 84.dp, 18.sp) { actions.cancelCountdown() }
    }
}

@Composable
private fun IncidentScreen(incident: ActiveIncident, phone: PhoneStatus?, link: DeviceLink?, actions: WatchActions) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(4_000)
            confirming = false
        }
    }

    Screen {
        if (incident.ended) {
            Label("SOS ended", color = C.green, size = 18.sp, weight = FontWeight.Bold)
            Label("Location sharing stopped.", color = C.grey, size = 12.sp)
            PillButton("Done", C.purple) { actions.dismiss() }
            return@Screen
        }

        Label("SOS ACTIVE", color = C.red, size = 18.sp, weight = FontWeight.Bold)
        val serverOk = incident.serverAccepted || phone?.serverAccepted == true
        if (incident.delivered) {
            Label("Alert delivered", color = C.green, size = 14.sp, weight = FontWeight.SemiBold)
        } else {
            Label("NOT SENT – retrying", color = C.red, size = 14.sp, weight = FontWeight.Bold)
        }

        StatusRow(
            "Phone",
            when {
                incident.phoneAck -> "✓ received"
                incident.phoneNodes > 0 -> "sent, waiting…"
                incident.phoneAttempts > 0 -> "✗ not reachable"
                else -> "sending…"
            },
            when {
                incident.phoneAck -> Tone.OK
                incident.phoneAttempts > 0 && incident.phoneNodes == 0 -> Tone.FAIL
                else -> Tone.WAIT
            },
        )
        if (phone != null) {
            StatusRow(
                "SMS",
                "${phone.smsSent} sent" + if (phone.smsFailed > 0) ", ${phone.smsFailed} failed" else "",
                if (phone.smsSent > 0) Tone.OK else if (phone.smsFailed > 0) Tone.FAIL else Tone.WAIT,
            )
            StatusRow("Call", if (phone.callPlaced) "✓ placed" else "not yet", if (phone.callPlaced) Tone.OK else Tone.INFO)
        }
        StatusRow(
            "Server",
            when {
                incident.serverAccepted -> "✓ accepted"
                phone?.serverAccepted == true -> "✓ via phone"
                link == null -> "watch not linked"
                incident.serverError != null -> "✗ retrying"
                else -> "sending…"
            },
            when {
                serverOk -> Tone.OK
                link == null -> Tone.INFO
                incident.serverError != null -> Tone.FAIL
                else -> Tone.WAIT
            },
        )
        if (incident.telegramSent > 0) StatusRow("Telegram", "${incident.telegramSent} alerted", Tone.OK)
        val fix = incident.fix
        StatusRow(
            "Location",
            if (fix == null) "getting GPS…"
            else (fix.accuracyM?.let { "±${it.roundToInt()} m, " } ?: "") + ago(now - fix.at),
            if (fix == null) Tone.WAIT else Tone.OK,
        )

        Spacer(Modifier.height(4.dp))
        PillButton(if (confirming) "Tap again to confirm" else "I'm safe", if (confirming) C.green else C.purple) {
            if (confirming) actions.imSafe() else confirming = true
        }
    }
}

private fun ago(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        else -> "${s / 3600}h ago"
    }
}
