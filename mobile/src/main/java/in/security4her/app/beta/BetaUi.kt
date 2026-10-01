package `in`.security4her.app.beta

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.security4her.app.*

private val NEVER = listOf(
    "Your location or places you go",
    "Phone numbers, contact names or messages",
    "Audio or video recordings",
    "Your name, Aadhaar or account details",
    "Anything you type, except a problem report you choose to send",
)

/** The beta agreement. Shown once at first launch, and again from Me → Beta testing to change choices. */
@Composable
fun BetaConsentScreen(onDone: () -> Unit, editing: Boolean = false) {
    val s by Beta.state.collectAsStateWithLifecycle()
    var cats by remember { mutableStateOf(if (editing && s.agreed) s.categories else BetaCategory.entries.toSet()) }
    var agree by remember { mutableStateOf(editing && s.agreed) }
    if (editing) BackHandler(onBack = onDone)

    Column(Modifier.fillMaxSize().background(Night).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp)) {
        if (editing) Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Back", onClick = onDone), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = Muted)
        }
        Spacer(Modifier.height(10.dp))
        Text("BETA", color = OnLilac, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Lilac).padding(horizontal = 10.dp, vertical = 4.dp))
        Spacer(Modifier.height(14.dp))
        Text("Help us test Security4Her", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "This is a beta, tested before a public launch. With your permission, the app sends anonymous test data so the team " +
                "can find crashes, errors, false alarms and security problems. Choose what to share. You can change this or delete " +
                "your data any time in Me → Beta testing.",
            color = Muted, style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))
        Text("What you can share", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1)) {
            BetaCategory.entries.forEachIndexed { i, c ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                Row(Modifier.fillMaxWidth().clickable { cats = if (c in cats) cats - c else cats + c }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.titleSmall)
                        Text(c.what, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(checked = c in cats, onCheckedChange = { on -> cats = if (on) cats + c else cats - c },
                        colors = SwitchDefaults.colors(checkedTrackColor = Lilac, checkedThumbColor = OnLilac, uncheckedTrackColor = Surface2, uncheckedBorderColor = Line))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Never collected", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        NEVER.forEach { Text("✕  $it", color = Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
        Spacer(Modifier.height(20.dp))
        Text("How it's kept safe", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        listOf(
            "You're a random tester ID (${s.tester.take(8)}…), not a name or number.",
            "Numbers, places, links and names are removed on your phone before anything is sent.",
            "Sent over an encrypted connection to a private database only the Security4Her team can read.",
            "Used only to fix the app during the beta, and deleted when the beta ends.",
            "Delete everything you've sent with one tap, any time.",
        ).forEach { Text("✓  $it", color = Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { agree = !agree }.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = agree, onCheckedChange = { agree = it }, colors = CheckboxDefaults.colors(checkedColor = Lilac, checkmarkColor = OnLilac, uncheckedColor = Muted))
            Text("I'm taking part in the Security4Her beta and agree to share the data I've chosen above, as described here.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(14.dp))
        ChunkyButton(if (editing) "Save choices" else "Agree and continue", enabled = agree && cats.isNotEmpty()) { Beta.accept(cats); onDone() }
        Spacer(Modifier.height(10.dp))
        ChunkyButton("Don't share any data", tone = Tone.Plain) { Beta.decline(); onDone() }
        Spacer(Modifier.height(8.dp))
        Text("The app works the same either way.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}

private val KINDS = listOf("bug" to "Something didn't work", "crash" to "The app closed", "false_alarm" to "False alarm",
    "security" to "Security or privacy worry", "design" to "Hard to use", "idea" to "Idea")

@Composable
fun BetaReportScreen(onClose: () -> Unit) {
    var kind by remember { mutableStateOf("bug") }
    var severity by remember { mutableStateOf("warning") }
    var text by remember { mutableStateOf("") }
    var attach by remember { mutableStateOf(true) }
    var sent by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Back", onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = Muted)
        }
        Spacer(Modifier.height(8.dp))
        Text("Report a problem", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text("Tell the team what happened. Please don't include names, numbers or addresses.", color = Muted, style = MaterialTheme.typography.bodyLarge)
        if (sent) {
            Spacer(Modifier.height(24.dp))
            Tip("Thanks, your report is saved. It's sent as soon as you're online.")
            Spacer(Modifier.height(16.dp))
            ChunkyButton("Done", onClick = onClose)
            return@Column
        }
        Spacer(Modifier.height(20.dp))
        Text("What kind of problem?", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        KINDS.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                row.forEach { (k, label) -> Chip(label, kind == k, Modifier.weight(1f)) { kind = k } }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("How bad is it?", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("info" to "Minor", "warning" to "Annoying", "error" to "Serious", "critical" to "Blocks SOS").forEach { (k, l) ->
                Chip(l, severity == k, Modifier.weight(1f)) { severity = k }
            }
        }
        Spacer(Modifier.height(16.dp))
        Field(text, { text = it.take(2000) }, "What happened, and what did you expect?")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().clickable { attach = !attach }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = attach, onCheckedChange = { attach = it }, colors = CheckboxDefaults.colors(checkedColor = Lilac, checkmarkColor = OnLilac, uncheckedColor = Muted))
            Text("Attach the app's recent errors (cleaned of personal details)", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        ChunkyButton("Send report", enabled = text.trim().length >= 5) { Beta.feedback(kind, severity, text.trim(), attach); sent = true }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Chip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Lilac else Surface1).border(1.dp, if (on) Lilac else Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) OnLilac else Ink, style = MaterialTheme.typography.labelLarge, maxLines = 2) }
}

/** Me → Beta testing. */
@Composable
fun BetaSection(onEdit: () -> Unit, onReport: () -> Unit) {
    val s by Beta.state.collectAsStateWithLifecycle()
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Beta.flush() }
    Text(
        when {
            !s.agreed -> "You're not sharing test data. You can still report problems after turning sharing on."
            else -> "Sharing ${s.categories.size} of ${BetaCategory.entries.size} kinds of test data. " +
                "${s.sent} sent" + (if (s.pending > 0) ", ${s.pending} waiting to upload" else "") + "."
        },
        color = Muted, style = MaterialTheme.typography.bodyMedium,
    )
    Text("Tester ID ${s.tester.take(8)}…  ·  ${BuildConfig.VERSION_NAME}", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    Spacer(Modifier.height(12.dp))
    if (s.agreed) { ChunkyButton("Report a problem", onClick = onReport); Spacer(Modifier.height(10.dp)) }
    ChunkyButton(if (s.agreed) "Change what I share" else "Start sharing test data", tone = Tone.Plain, onClick = onEdit)
    if (s.agreed || s.sent > 0) {
        TextButton(onClick = { msg = "Deleting…"; Beta.forget { msg = it } }) { Text("Stop sharing and delete my test data", color = SosRed) }
    }
    msg?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
}
