package `in`.security4her.app

import android.content.Intent
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.security4her.app.core.Settings
import `in`.security4her.app.sos.Evidence
import `in`.security4her.app.sos.EvidenceCase
import `in`.security4her.app.sos.EvidencePiece
import `in`.security4her.app.sos.Integrity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun mmss(s: Long) = "%d:%02d".format(s / 60, s % 60)

// ---------------------------------------------------------------- During an SOS

@Composable
fun EvidenceCard(sosActive: Boolean = true) {
    val ctx = LocalContext.current
    val c by Evidence.active.collectAsStateWithLifecycle()
    val wants = remember { Evidence.wantsAudio(ctx) || Evidence.wantsVideo(ctx) }
    val case = c
    if (case == null && !wants) return
    val pulse = rememberInfiniteTransition(label = "rec")
    val a by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (case?.recording == true) SosRed.copy(alpha = a) else Muted))
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    case?.recording == true -> "Recording evidence"
                    case != null || !sosActive -> "Evidence saved"
                    else -> "Starting evidence recording…"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        // After the app restarts, a finished SOS no longer has its live case; point to where the recordings are.
        if (case == null && !sosActive) {
            Spacer(Modifier.height(6.dp))
            Text("Find it in Settings → Evidence locker.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (case != null) {
            Spacer(Modifier.height(8.dp))
            val rows = listOfNotNull(
                ("Phone audio" to case.seconds("audio")).takeIf { case.audioOn || it.second > 0 },
                ("Video" to case.seconds("video")).takeIf { case.videoOn || it.second > 0 },
                ("Watch audio" to case.seconds("watch_audio")).takeIf { it.second > 0 },
            )
            rows.forEach { (label, secs) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(label, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(mmss(secs), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("${case.pieces.size} pieces · ${Evidence.human(case.bytes)} · fingerprinted", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            case.problems.forEach { Text(it, color = Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            Text(
                if (case.recording) "Saved privately on this phone, not in your gallery. Stops when you tap \"I'm safe\"."
                else "Find it later in Settings → Evidence locker.",
                color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
fun EvidenceSection(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(Settings.load(ctx)) }
    val colors = SwitchDefaults.colors(checkedTrackColor = Lilac, checkedThumbColor = OnLilac, uncheckedTrackColor = Surface2, uncheckedBorderColor = Line)
    fun set(n: Settings) { n.save(ctx); s = n }
    Section("Evidence") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Record audio during an SOS")
                Text("Starts the moment SOS starts, even in a pocket or bag.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = s.evidence, onCheckedChange = { set(s.copy(evidence = it)) }, colors = colors)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Also record video")
                Text("Back camera in 30-second clips.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = s.evidenceVideo, enabled = s.evidence, onCheckedChange = { set(s.copy(evidenceVideo = it)) }, colors = colors)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Open the evidence locker")
                Text("Recordings from past SOS alerts, with integrity checks and export.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = Lilac, fontSize = 26.sp, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

// ---------------------------------------------------------------- Locker

@Composable
fun EvidenceLockerScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var cases by remember { mutableStateOf<List<EvidenceCase>?>(null) }
    var open by remember { mutableStateOf<EvidenceCase?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { cases = withContext(Dispatchers.IO) { Evidence.cases(ctx) } }
    val o = open
    if (o != null) {
        CaseScreen(o, onBack = { open = null; reload++ })
        return
    }
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) { TextButton(onClick = onClose) { Text("‹  Back", color = Lilac) } }
        Text("Evidence locker", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            "Audio and video recorded during SOS alerts. Stored only in this app, not in your gallery.",
            color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        val list = cases
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Lilac) }
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Logo(72.dp)
                Spacer(Modifier.height(14.dp))
                Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "When an SOS starts, audio and video are recorded automatically and kept here.",
                    color = Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.incidentId }) { c -> CaseRow(c) { open = c } }
            }
        }
    }
}

@Composable
private fun CaseRow(c: EvidenceCase, onOpen: () -> Unit) {
    val fmt = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.US) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).clickable(onClick = onOpen).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmt.format(Date(c.startedAt)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (c.recording) Text("Recording", color = SosRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            listOfNotNull(
                c.seconds("audio").takeIf { it > 0 }?.let { "Audio ${mmss(it)}" },
                c.seconds("video").takeIf { it > 0 }?.let { "Video ${mmss(it)}" },
                c.seconds("watch_audio").takeIf { it > 0 }?.let { "Watch ${mmss(it)}" },
            ).joinToString(" · ").ifBlank { "No pieces yet" },
            color = Muted, style = MaterialTheme.typography.bodyMedium,
        )
        Text("${c.pieces.size} pieces · ${Evidence.human(c.bytes)}", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CaseScreen(c0: EvidenceCase, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val live by Evidence.active.collectAsStateWithLifecycle()
    val c = live?.takeIf { it.incidentId == c0.incidentId } ?: c0
    var integrity by remember { mutableStateOf<Integrity?>(null) }
    var playing by remember { mutableStateOf<Int?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) { onDispose { player[0]?.release() } }
    LaunchedEffect(c.pieces.size) { integrity = withContext(Dispatchers.IO) { Evidence.verify(ctx, c) } }

    fun fileOf(p: EvidencePiece) = File(Evidence.dir(ctx, c.incidentId), p.file)
    fun play(p: EvidencePiece) {
        player[0]?.release()
        val queue = c.pieces.filter { it.kind == p.kind && it.seq >= p.seq }.sortedBy { it.seq }
        fun start(i: Int) {
            if (i >= queue.size) { playing = null; return }
            playing = queue[i].seq
            player[0] = MediaPlayer().apply {
                setDataSource(fileOf(queue[i]).absolutePath)
                setOnCompletionListener { it.release(); start(i + 1) }
                prepare()
                start()
            }
        }
        runCatching { start(0) }.onFailure { playing = null }
    }
    fun openVideo(p: EvidencePiece) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", fileOf(p))
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) { TextButton(onClick = onBack) { Text("‹  Back", color = Lilac) } }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).format(Date(c.startedAt)), style = MaterialTheme.typography.headlineSmall)
                Text("${c.device} · ${c.pieces.size} pieces · ${Evidence.human(c.bytes)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                val i = integrity
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background((if (i == null) Surface1 else if (i.ok) Lilac.copy(alpha = 0.12f) else SosRed.copy(alpha = 0.14f))).padding(14.dp),
                ) {
                    Text(
                        when {
                            i == null -> "Checking fingerprints…"
                            i.ok -> "Intact: all ${i.checked} pieces match their fingerprints and the chain is unbroken."
                            else -> "${i.broken.size} of ${i.checked} pieces were changed or are missing (#${i.broken.joinToString(", #")})."
                        },
                        color = if (i != null && !i.ok) SosRed else Ink, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            exporting = true
                            scope.launch {
                                val zip = withContext(Dispatchers.IO) { Evidence.export(ctx, c) }
                                exporting = false
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", zip)
                                ctx.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                        "Share evidence",
                                    ),
                                )
                            }
                        },
                        enabled = !exporting && c.pieces.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Lilac, contentColor = OnLilac), shape = RoundedCornerShape(14.dp),
                    ) { Text(if (exporting) "Preparing…" else "Export case", fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(onClick = { askDelete = true }, border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(14.dp)) {
                        Text("Delete", color = Muted)
                    }
                }
                c.problems.forEach { Text(it, color = Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
            listOf("audio", "watch_audio", "video").forEach { kind ->
                val ps = c.pieces.filter { it.kind == kind }.sortedBy { it.seq }
                if (ps.isNotEmpty()) {
                    item(key = "h-$kind") {
                        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(Evidence.label(kind), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(mmss(c.seconds(kind)), color = Muted)
                        }
                    }
                    items(ps, key = { "${kind}-${it.seq}" }) { p ->
                        val on = playing == p.seq
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Surface2 else Surface1)
                                .clickable {
                                    if (kind == "video") openVideo(p)
                                    else if (on) { player[0]?.release(); player[0] = null; playing = null } else play(p)
                                }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(30.dp).clip(CircleShape).border(1.dp, if (on) SosRed else Lilac, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (on) "■" else "▶", color = if (on) SosRed else Lilac, fontSize = 12.sp) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${fmt.format(Date(p.startedAt))} – ${fmt.format(Date(p.endedAt))}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "#${p.seq} · ${Evidence.human(p.bytes)} · ${p.sha256.take(12)}…" + (p.lat?.let { " · %.4f, %.4f".format(Locale.US, it, p.lng) } ?: ""),
                                    color = Muted, fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (askDelete) {
        val blocked = Evidence.deleteBlockedReason(c)
        var pin by remember { mutableStateOf("") }
        val needsPin = Settings.load(ctx).pin.isNotBlank()
        AlertDialog(
            onDismissRequest = { askDelete = false },
            containerColor = Surface1,
            title = { Text(if (blocked != null) "Can't delete yet" else "Delete this evidence?") },
            text = {
                Column {
                    Text(blocked ?: "This permanently removes ${c.pieces.size} recordings from this phone. Export a copy first if you might need it.", color = Muted)
                    if (blocked == null && needsPin) {
                        Spacer(Modifier.height(10.dp))
                        Field(pin, { pin = it.filter(Char::isDigit).take(4) }, "Your PIN", KeyboardType.NumberPassword, secret = true)
                    }
                }
            },
            confirmButton = {
                if (blocked == null) TextButton(
                    enabled = !needsPin || pin == Settings.load(ctx).pin,
                    onClick = { askDelete = false; if (Evidence.delete(ctx, c)) onBack() },
                ) { Text("Delete", color = SosRed) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text(if (blocked != null) "OK" else "Keep", color = Muted) } },
        )
    }
}
