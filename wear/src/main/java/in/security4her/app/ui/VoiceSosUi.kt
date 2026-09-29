package `in`.security4her.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text
import `in`.security4her.app.sos.Haptics
import `in`.security4her.app.voice.VoiceListener
import `in`.security4her.app.voice.VoiceSos

// ---------------------------------------------------------------- Voice SOS screens (watch)

private val Lilac = Color(0xFFB794F4)
private val Purple = Color(0xFF5B2A86)
private val Grey = Color(0xFFB0B0B0)
private val Green = Color(0xFF43A047)
private val Amber = Color(0xFFFFB300)
private val Coral = Color(0xFFFF6F61)
private val Off = Color(0xFF333333)

@Composable
private fun T(text: String, color: Color = Color.White, size: TextUnit = 13.sp, bold: Boolean = false) =
    Text(text = text, color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)

@Composable
private fun Pill(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(color).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(label, size = 14.sp, bold = true) }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** Three dots that fill as each "help" is heard. */
@Composable
private fun HelpDots(n: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i -> Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < n) Coral else Off)) }
    }
}

/** The Voice SOS feature: its own switch, status and practice, separate from Safety Mode. */
@Composable
fun VoiceSosScreen(on: Boolean, onToggle: (Boolean) -> Unit, onBack: () -> Unit) {
    var practising by remember { mutableStateOf(false) }
    if (practising) { VoicePracticeScreen { practising = false }; return }
    val status by VoiceSos.status.collectAsStateWithLifecycle()
    val heard by VoiceSos.heard.collectAsStateWithLifecycle()

    Page {
        T("Voice SOS", Lilac, 16.sp, bold = true)
        T(
            when {
                !on -> "Off"
                status == VoiceSos.Status.Loading -> "Getting ready…"
                status == VoiceSos.Status.Listening -> "Listening for HELP HELP HELP"
                status == VoiceSos.Status.Hearing -> "Hearing you…"
                status == VoiceSos.Status.Paused -> "Paused during an SOS"
                status == VoiceSos.Status.NoPermission -> "Allow the microphone to use this"
                status == VoiceSos.Status.Failed -> "Microphone unavailable. Turn off and on again."
                else -> "Starting…"
            },
            color = when {
                !on -> Grey
                status == VoiceSos.Status.Listening || status == VoiceSos.Status.Hearing -> Green
                status == VoiceSos.Status.NoPermission || status == VoiceSos.Status.Failed -> Coral
                else -> Amber
            },
            size = 12.sp,
        )
        if (on) HelpDots(heard)
        Pill(if (on) "Voice SOS: ON" else "Voice SOS: OFF", if (on) Purple else Off) { onToggle(!on) }
        T("Say HELP HELP HELP, with short pauses if you like. The watch vibrates and gives you 5 seconds to cancel, then sends the SOS.", Grey, 11.sp)
        Pill("Practise", Off) { practising = true }
        T("Listens only while on. Nothing is recorded or sent. Uses extra battery.", Grey, 10.sp)
        Pill("Back", Off, onBack)
    }
}

/** Try the phrase without sending anything: the watch shows each "help" it hears and buzzes when all three count. */
@Composable
private fun VoicePracticeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var hits by remember { mutableIntStateOf(0) }
    val status by VoiceSos.status.collectAsStateWithLifecycle()
    val heard by VoiceSos.heard.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        VoiceSos.setPracticing(true) // the background listener steps aside; nothing can start an SOS from here
        val l = VoiceListener(ctx) { hits++; Haptics.sosStarted(ctx) }.also { it.start() }
        onDispose { l.stop(); VoiceSos.setPracticing(false) }
    }
    Page {
        T("Practise", Lilac, 16.sp, bold = true)
        T("Say HELP HELP HELP. Nothing is sent.", Grey, 12.sp)
        HelpDots(if (hits > 0 && heard == 0) 0 else heard)
        T(
            when {
                !VoiceSos.hasPermission(ctx) -> "Allow the microphone first"
                status == VoiceSos.Status.Loading -> "Getting ready…"
                status == VoiceSos.Status.Failed -> "Microphone unavailable"
                hits == 1 -> "Heard it! That would start the SOS."
                hits > 1 -> "Heard it $hits times."
                status == VoiceSos.Status.Hearing -> "Hearing you…"
                else -> "Listening…"
            },
            color = if (hits > 0) Green else Grey, size = 13.sp, bold = hits > 0,
        )
        T("Tip: say HELP three times, loud and clear. \"Help me\" works too; other words in between start the count again.", Grey, 10.sp)
        Pill("Done", Off, onBack)
    }
}
