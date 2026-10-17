package `in`.security4her.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import `in`.security4her.app.core.AppLog
import `in`.security4her.app.core.Contact
import `in`.security4her.app.core.Pin
import `in`.security4her.app.core.Settings
import `in`.security4her.app.core.Store
import `in`.security4her.app.sos.Incident
import `in`.security4her.app.sos.PocketSos
import `in`.security4her.app.sos.SosEngine

// ---------------------------------------------------------------- Parental controls
// A grown-up sets these behind the PIN (the same PIN that stops an SOS): who gets called and in what order, what
// happens if no one answers, check-in places, and locks on the child's own settings.

data class KidsPrefs(
    val finalCall: String = "112", // "112" | "1098" | "none": called after every grown-up, if no one answered
    val pauseSec: Int = 8, // between calls, for "They answered"
    val textPolice: Boolean = false,
    val lockGrownups: Boolean = true,
    val shareLocation: Boolean = true,
    val places: List<String> = listOf("School", "Home", "Tuition", "A friend's house", "The bus stop"),
)

object KidsSettings {
    fun load(ctx: Context): KidsPrefs = with(Store.prefs(ctx)) {
        KidsPrefs(
            finalCall = getString("kids_final_call", "112")!!,
            pauseSec = getInt("kids_pause_sec", 8),
            textPolice = getBoolean("kids_text_police", false),
            lockGrownups = getBoolean("kids_lock_grownups", true),
            shareLocation = getBoolean("kids_share_location", true),
            places = getString("kids_places", null)?.split('\n')?.filter { it.isNotBlank() } ?: KidsPrefs().places,
        )
    }

    fun save(ctx: Context, k: KidsPrefs) = Store.prefs(ctx).edit()
        .putString("kids_final_call", k.finalCall).putInt("kids_pause_sec", k.pauseSec)
        .putBoolean("kids_text_police", k.textPolice).putBoolean("kids_lock_grownups", k.lockGrownups)
        .putBoolean("kids_share_location", k.shareLocation).putString("kids_places", k.places.joinToString("\n"))
        .apply()
}

/** Asks for the grown-up PIN, or sets one first if there isn't one yet. */
@Composable
internal fun PinGate(title: String, why: String, onUnlocked: () -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    val ctx = LocalContext.current
    val setting = remember { !Pin.isSet(ctx) }
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Icon(Icons.Filled.ArrowBack, "Back", tint = Ink, modifier = Modifier.clip(CircleShape).clickable(onClick = onCancel).padding(8.dp).size(24.dp))
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(64.dp).clip(CircleShape).background(Lilac.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, null, tint = Lilac, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(if (setting) "Set a grown-up PIN" else title, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = Ink)
        Text(
            if (setting) "4 digits only a grown-up knows. It locks these settings and is needed to stop an SOS." else why,
            color = Muted, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        PinField(pin, { pin = it.filter(Char::isDigit).take(4); error = null }, if (setting) "New PIN" else "PIN")
        if (setting) { Spacer(Modifier.height(10.dp)); PinField(again, { again = it.filter(Char::isDigit).take(4); error = null }, "Type it again") }
        error?.let { Text(it, color = SosRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(20.dp))
        ChunkyButton(if (setting) "Save PIN" else "Unlock", enabled = pin.length == 4 && (!setting || again.length == 4)) {
            if (setting) {
                if (pin != again) { error = "The two PINs don't match."; return@ChunkyButton }
                Pin.set(ctx, pin); AppLog.ok("Kids", "Grown-up PIN set"); onUnlocked()
            } else when (val r = Pin.check(ctx, pin)) {
                is Pin.Check.Ok -> onUnlocked()
                else -> { error = Pin.message(r); pin = "" }
            }
        }
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) = OutlinedTextField(
    value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
    colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Lilac, unfocusedBorderColor = Line, focusedLabelColor = Lilac, unfocusedLabelColor = Muted,
        cursorColor = Lilac, focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
    ),
)

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@Composable
internal fun ParentControlsScreen(childName: String, contacts: List<Contact>?, onClose: () -> Unit, onLocker: () -> Unit = {}, onLog: () -> Unit = {}) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    var k by remember { mutableStateOf(KidsSettings.load(ctx)) }
    var s by remember { mutableStateOf(Settings.load(ctx)) }
    var pocket by remember { mutableStateOf(PocketSos.enabled(ctx)) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val callLog = remember(tick) { granted(ctx, Manifest.permission.READ_CALL_LOG) && granted(ctx, Manifest.permission.READ_PHONE_STATE) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    var newPlace by remember { mutableStateOf("") }
    var changePin by remember { mutableStateOf(false) }
    fun saveK(n: KidsPrefs) { k = n; KidsSettings.save(ctx, n) }
    fun saveS(n: Settings) { s = n; n.save(ctx) }
    if (changePin) {
        PinGate("Grown-up PIN", "", onUnlocked = { changePin = false }, onCancel = { changePin = false })
        return
    }
    val grown = contacts.orEmpty().filter { it.receivesSos }.sortedBy { it.priority }
    val order = grown.map { it.name } + when (k.finalCall) { "1098" -> listOf("Childline 1098"); "112" -> listOf("112"); else -> emptyList() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = Ink, modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(8.dp).size(24.dp))
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).background(Mint.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Lock, null, tint = Mint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("Unlocked with your PIN", color = Mint, style = MaterialTheme.typography.labelMedium)
            }
        }
        Text("Grown-up settings", fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, color = Ink)
        Text("For $childName's Kids mode. Only someone with the PIN can change these.", color = Muted, style = MaterialTheme.typography.bodyMedium)

        Section("When $childName presses SOS")
        Panel {
            Text("Who gets called, in this order", style = MaterialTheme.typography.titleSmall, color = Ink)
            Spacer(Modifier.height(10.dp))
            if (order.isEmpty()) Text("No one yet. Add a grown-up in My grown-ups.", color = Amber, style = MaterialTheme.typography.bodySmall)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                order.forEachIndexed { i, n ->
                    val last = i == order.lastIndex && k.finalCall != "none"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.clip(RoundedCornerShape(16.dp)).background(if (last) SosRed.copy(alpha = 0.16f) else Surface2).padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${i + 1}", color = if (last) SosRed else Lilac, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(n, color = Ink, style = MaterialTheme.typography.labelLarge)
                        }
                        if (i < order.lastIndex) Text("  →", color = Muted)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("The order is the order in My grown-ups. Everyone gets a text with the location first.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Panel {
            Text("If no grown-up answers, call", style = MaterialTheme.typography.titleSmall, color = Ink)
            Spacer(Modifier.height(8.dp))
            Choices(listOf("112" to "112", "1098" to "Childline 1098", "none" to "No one"), k.finalCall) { saveK(k.copy(finalCall = it)) }
            Spacer(Modifier.height(14.dp))
            Text("Time to cancel before the first call", style = MaterialTheme.typography.titleSmall, color = Ink)
            Spacer(Modifier.height(8.dp))
            Choices(listOf(5, 10, 15).map { it.toString() to "$it s" }, s.countdownSec.toString()) { saveS(s.copy(countdownSec = it.toInt())) }
            Spacer(Modifier.height(14.dp))
            Text("Wait before calling the next grown-up", style = MaterialTheme.typography.titleSmall, color = Ink)
            Spacer(Modifier.height(8.dp))
            Choices(listOf(5, 8, 15).map { it.toString() to "$it s" }, k.pauseSec.toString()) { saveK(k.copy(pauseSec = it.toInt())) }
        }
        Panel {
            Toggle("Also text the police (${s.policeNumber})", "Off: only grown-ups get the SOS text.", k.textPolice) { saveK(k.copy(textPolice = it)) }
        }
        Panel {
            Text("Know when someone answers", style = MaterialTheme.typography.titleSmall, color = Ink)
            Text(
                if (callLog) "On. The next grown-up is called only if the last one didn't pick up."
                else "Lets the app read the call log, so it moves on only when a call wasn't answered. Without it, $childName gets a few seconds to tap \"They answered\".",
                color = if (callLog) Mint else Muted, style = MaterialTheme.typography.bodySmall,
            )
            if (!callLog) {
                Spacer(Modifier.height(10.dp))
                ChunkyButton("Allow", tone = Tone.Plain) { ask.launch(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE)) }
                TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Not allowed? Open settings: ⋮ → Allow restricted settings → Permissions → Call logs", color = Lilac, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Section("Family")
        Panel {
            Text("Connect grown-ups' phones from the Family tab", style = MaterialTheme.typography.titleSmall, color = Ink)
            Text(
                "It shows who's connected, texts each grown-up the private family link, and shows a QR code to scan. " +
                    "Showing the QR code or making a new link asks for this PIN.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Locks")
        Panel {
            Toggle("PIN to change grown-ups", "$childName can see them but can't remove or edit them.", k.lockGrownups) { saveK(k.copy(lockGrownups = it)) }
            Spacer(Modifier.height(12.dp))
            Toggle("PIN to stop an SOS", "Always on: an SOS can only be ended with this PIN.", true, enabled = false) {}
            Spacer(Modifier.height(12.dp))
            Toggle("Pocket SOS", "Three presses of the power button start an SOS, even in a bag.", pocket) {
                pocket = it; PocketSos.setEnabled(ctx, it)
            }
        }

        Section("Check-ins")
        Panel {
            Toggle("Send location with check-ins", "A map pin with every \"I got there safely\".", k.shareLocation) { saveK(k.copy(shareLocation = it)) }
            Spacer(Modifier.height(14.dp))
            Text("Places $childName can check in from", style = MaterialTheme.typography.titleSmall, color = Ink)
            Spacer(Modifier.height(8.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                k.places.forEach { p ->
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(Surface2).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p, color = Ink, style = MaterialTheme.typography.labelLarge)
                        Icon(
                            Icons.Filled.Close, "Remove $p", tint = Muted,
                            modifier = Modifier.padding(start = 4.dp).size(22.dp).clip(CircleShape).clickable { saveK(k.copy(places = k.places - p)) }.padding(3.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(newPlace, { newPlace = it.take(24) }, "Add a place, e.g. Dance class") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val p = newPlace.trim()
                    if (p.isNotEmpty() && p !in k.places) saveK(k.copy(places = k.places + p))
                    newPlace = ""
                }) { Text("Add", color = Lilac) }
            }
        }

        Section("Practice")
        Panel {
            Toggle(
                "Test Mode", "Grown-ups are always called for real. Test Mode sends the 112 or 1098 call and police texts to the test number instead.",
                s.testMode,
            ) { saveS(s.copy(testMode = it)) }
            if (s.testMode) {
                Spacer(Modifier.height(10.dp))
                Field(s.demoNumber, { saveS(s.copy(demoNumber = it.filter { c -> c.isDigit() || c == '+' }.take(15))) }, "Test number", KeyboardType.Phone)
            }
        }
        Spacer(Modifier.height(10.dp))
        Section("Records")
        Panel {
            Text("Evidence locker", style = MaterialTheme.typography.titleSmall, color = Ink)
            Text("Audio and video recorded during $childName's SOS alerts, encrypted, with export for the police.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            ChunkyButton("Open the evidence locker", tone = Tone.Plain, icon = Icons.Filled.Lock, onClick = onLocker)
            Spacer(Modifier.height(14.dp))
            Text("Activity log", style = MaterialTheme.typography.titleSmall, color = Ink)
            Text("Every SOS, text, call and check-in, step by step.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            ChunkyButton("Open the activity log", tone = Tone.Plain, onClick = onLog)
        }
        TextButton(onClick = { changePin = true }) { Text("Change the grown-up PIN", color = Lilac) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(t: String) {
    Spacer(Modifier.height(22.dp))
    Text(t.uppercase(), color = Lilac, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(20.dp)).background(Surface1).padding(16.dp), content = content)
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Ink)
            Text(sub, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = on, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Mint, checkedThumbColor = OnMint, disabledCheckedTrackColor = Mint.copy(alpha = 0.5f)),
        )
    }
}

@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            val sel = key == selected
            Text(
                label, color = if (sel) OnLilac else Ink, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) Lilac else Surface2)
                    .border(1.dp, if (sel) Lilac else Line, RoundedCornerShape(16.dp))
                    .clickable { onPick(key) }.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- On the SOS screen

/** A child's SOS: each grown-up in turn, live, with "They answered" to stop the calls that would follow. */
@Composable
internal fun ChildCallCard(inc: Incident, now: Long) {
    val ctx = LocalContext.current
    Card {
        val chain = inc.chain
        val left = { at: Long -> kotlin.math.ceil((at - now) / 1000.0).toInt().coerceAtLeast(0) }
        val calling = chain.indexOfFirst { it.state == "calling" }
        val nextIdx = chain.indexOfFirst { it.state == "waiting" }
        val answered = chain.firstOrNull { it.state == "answered" }
        Text(
            when {
                !inc.active && answered == null -> if (inc.state == "cancelled") "SOS stopped, calls stopped" else "Calls stopped"
                chain.isEmpty() -> "Getting ready to call your grown-ups"
                answered != null -> "${answered.name} answered"
                inc.call == "cancelled" -> "Calls cancelled"
                inc.call == "waiting" && inc.callAt != null -> "Calling ${chain[0].name} in ${left(inc.callAt)} s"
                inc.chainNextAt != null && nextIdx >= 0 -> "Calling ${chain[nextIdx].name} in ${left(inc.chainNextAt)} s"
                calling >= 0 -> "Calling ${chain[calling].name}…"
                else -> "Called everyone on the list"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (chain.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            chain.forEachIndexed { i, st ->
                val (label, color) = when (st.state) {
                    "calling" -> "Calling…" to Amber
                    "answered" -> "Answered" to Mint
                    "no_answer" -> "No answer" to SosRed
                    "ended" -> "Call ended" to Muted
                    "failed" -> "Couldn't call" to SosRed
                    else -> "Next" to Muted
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(st.name, color = Ink, style = MaterialTheme.typography.bodyLarge)
                        Text(st.number, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(label, color = color, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (inc.active && inc.call == "waiting" && inc.callAt != null) {
                ChunkyButton("Cancel calls", Modifier.weight(1f), tone = Tone.Plain) { SosEngine.cancelCall() }
            } else if (inc.active && answered == null && inc.call != "cancelled" && chain.any { it.state != "waiting" }) {
                ChunkyButton("They answered", Modifier.weight(1f), tone = Tone.Good) { SosEngine.someoneAnswered() }
            }
            ChunkyButton("112", Modifier.weight(0.6f), tone = Tone.Danger, icon = Icons.Filled.Call) { dial(ctx, "112") }
        }
    }
}
