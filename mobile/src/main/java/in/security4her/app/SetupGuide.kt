package `in`.security4her.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.security4her.app.core.Contact
import `in`.security4her.app.core.Settings
import `in`.security4her.app.core.Store
import `in`.security4her.app.sos.KnockDetector
import `in`.security4her.app.sos.PocketSos
import `in`.security4her.app.sos.Sms

// ---------------------------------------------------------------- Setup guide
// Everything an SOS depends on, one page at a time, each with a live check so she can see it's really done.

object Guide {
    fun done(ctx: Context) = Store.prefs(ctx).getBoolean("guide_done", false)
    fun seen(ctx: Context) = Store.prefs(ctx).getBoolean("guide_seen", false)
    fun markSeen(ctx: Context) = Store.prefs(ctx).edit().putBoolean("guide_seen", true).apply()
    fun finish(ctx: Context) = Store.prefs(ctx).edit().putBoolean("guide_done", true).putBoolean("guide_seen", true).apply()
}

private data class Page(val key: String, val title: String)

private val PAGES = listOf(
    Page("intro", "Your safety net"),
    Page("people", "Who gets your SOS"),
    Page("perms", "Let it work"),
    Page("background", "Keep it running"),
    Page("pin", "Safety PIN"),
    Page("test", "Test a text"),
    Page("pocket", "Pocket SOS"),
    Page("watch", "Your watch"),
    Page("extras", "More ways to stay safe"),
    Page("done", "You're set"),
)

@Composable
internal fun SetupGuideScreen(
    contacts: List<Contact>?, contactsError: String?, onContactsChanged: () -> Unit,
    settings: Settings, onSettings: (Settings) -> Unit,
    onPractice: () -> Unit, onWatchInstall: () -> Unit, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var idx by rememberSaveable { mutableIntStateOf(Store.prefs(ctx).getInt("guide_page", 0).coerceIn(0, PAGES.lastIndex)) }
    LaunchedEffect(idx) { Store.prefs(ctx).edit().putInt("guide_page", idx).apply(); Guide.markSeen(ctx) }
    val page = PAGES[idx]
    fun next() { if (idx < PAGES.lastIndex) idx++ else { Guide.finish(ctx); Store.prefs(ctx).edit().remove("guide_page").apply(); onClose() } }
    BackHandler { if (idx > 0) idx-- else onClose() }

    Column(Modifier.fillMaxSize().background(Night)) {
        LessonBar((idx + 1) / PAGES.size.toFloat(), onClose = onClose)
        Text(
            if (idx == 0 || idx == PAGES.lastIndex) "Setup guide" else "Step $idx of ${PAGES.size - 2}  ·  ${page.title}",
            color = Lilac, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 24.dp),
        )
        AnimatedContent(
            idx, label = "page", modifier = Modifier.weight(1f),
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(260)) { it / 5 * dir } + fadeIn(tween(260))) togetherWith fadeOut(tween(120))
            },
        ) { i ->
            when (PAGES[i].key) {
                // Contacts has its own list and editor; it scrolls by itself
                "people" -> ContactsScreen(contacts, contactsError, onContactsChanged)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
                    when (PAGES[i].key) {
                        "intro" -> IntroPage()
                        "perms" -> PermsPage()
                        "background" -> BackgroundPage()
                        "pin" -> PinStep(settings) { onSettings(it); next() }
                        "test" -> TestTextStep(settings, onChange = onSettings, doneLabel = "Skip for now") { next() }
                        "pocket" -> PocketPage(onPractice)
                        "watch" -> WatchPage(onWatchInstall)
                        "extras" -> ExtrasPage(settings, onSettings)
                        "done" -> DonePage(contacts)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            ChunkyButton(
                when (page.key) { "intro" -> "Let's start"; "done" -> "Finish"; else -> "Next" },
                tone = if (page.key == "done") Tone.Good else Tone.Accent,
            ) { next() }
            if (idx in 1 until PAGES.lastIndex) TextButton(onClick = { idx-- }, modifier = Modifier.fillMaxWidth()) { Text("Back", color = Muted) }
        }
    }
}

@Composable
private fun PageIntro(title: String, body: String?, pad: Boolean = true) {
    Column(if (pad) Modifier.padding(horizontal = 24.dp, vertical = 12.dp) else Modifier.padding(bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        body?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Muted, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun GuideCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(Surface1).padding(16.dp), content = content)
}

@Composable
private fun StatusDot(ok: Boolean, warn: Boolean = false) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(if (ok) Mint else if (warn) Amber.copy(alpha = 0.2f) else Surface2),
        contentAlignment = Alignment.Center,
    ) { if (ok) Icon(Icons.Filled.Check, "Done", tint = OnMint, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun IntroPage() {
    Text("Let's set up your safety net", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "About 3 minutes. Each step checks itself, so you can see it's really working. You can leave and come back: the guide " +
            "remembers where you were (Me → Setup guide).",
        color = Muted, style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(18.dp))
    listOf(
        "Who gets your SOS" to "Your trusted contacts",
        "Let it work" to "Location, texts, calls and alerts",
        "Keep it running" to "So your phone doesn't pause it",
        "Safety PIN and a test text" to "Prove texts reach people",
        "Pocket SOS" to "Knock 3 times, no unlocking",
        "Your watch" to "SOS from your wrist",
    ).forEachIndexed { i, (t, d) ->
        Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Surface1), contentAlignment = Alignment.Center) {
                Text("${i + 1}", color = Lilac, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(14.dp))
            Column { Text(t, style = MaterialTheme.typography.titleMedium); Text(d, color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(ctx: Context) = runCatching {
    ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun PermsPage() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val asked = remember { mutableStateListOf<String>() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    PageIntro("Let it work", "Allow these once. Nothing is recorded or shared unless an SOS or Safe Journey is running.", pad = false)
    Spacer(Modifier.height(8.dp))
    data class Row3(val key: String, val title: String, val why: String, val perms: Array<String>)
    val rows = buildList {
        add(Row3("loc", "Location", "So your texts and live link show where you are.", arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
        if (Build.VERSION.SDK_INT >= 29) add(Row3("bg", "Location all the time", "So your watch or Pocket SOS works with the phone locked. Choose \"Allow all the time\".", arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
        add(Row3("call", "Phone calls", "Calls your chosen number when the countdown ends.", arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)))
        if (Build.VERSION.SDK_INT >= 33) add(Row3("notif", "Notifications", "Shows the SOS countdown and its Cancel button.", arrayOf(Manifest.permission.POST_NOTIFICATIONS)))
        add(Row3("rec", "Microphone and camera", "Records evidence from the moment an SOS starts.", arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)))
    }
    rows.forEach { r ->
        val ok = remember(tick) { granted(ctx, r.perms[0]) }
        GuideCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(ok, warn = r.key in asked)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium)
                    Text(r.why, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (!ok) {
                    Spacer(Modifier.width(8.dp))
                    val needsFg = r.key == "bg" && !granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                    TextButton(onClick = {
                        if (r.key in asked && !needsFg) openAppSettings(ctx) // asked before: Android won't show the dialog again
                        else { asked += r.key; launcher.launch(if (needsFg) rows[0].perms else r.perms) }
                    }) { Text(if (r.key in asked && !needsFg) "Settings" else "Allow", color = Lilac) }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text("Texts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp))
    SmsAccessCard(onGranted = { tick++ })
}

// ---- keeping Pocket SOS and the watch link alive: battery limits, and phone makers' own app killers ----

private val brand get() = Build.MANUFACTURER.lowercase()

/** Each phone maker hides "let this app run" somewhere different. */
private fun brandTip(): String = when {
    brand in listOf("xiaomi", "redmi", "poco") -> "On Xiaomi, Redmi and POCO: turn on Autostart, then in Battery saver choose No restrictions."
    brand in listOf("oppo", "realme", "oneplus") -> "On OPPO, realme and OnePlus: allow Auto launch and background activity for Security4Her."
    brand in listOf("vivo", "iqoo") -> "On vivo and iQOO: allow Background power consumption (High) and Autostart."
    brand == "samsung" -> "On Samsung: App info → Battery → Unrestricted. Also check it isn't in Sleeping apps."
    brand in listOf("huawei", "honor") -> "On Huawei and Honor: App launch → Manage manually, and turn all three switches on."
    else -> "In App info → Battery, choose Unrestricted (or Don't optimise)."
}

private fun openMakerSettings(ctx: Context) {
    val candidates = when {
        brand in listOf("xiaomi", "redmi", "poco") -> listOf(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        brand in listOf("oppo", "realme", "oneplus") -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
        )
        brand in listOf("vivo", "iqoo") -> listOf(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
        brand in listOf("huawei", "honor") -> listOf(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
        else -> emptyList()
    }
    for (c in candidates) {
        if (runCatching { ctx.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    openAppSettings(ctx)
}

/** Battery optimisation off, plus the phone maker's own setting. Used here and when the screen-off test shows a problem. */
@Composable
internal fun BackgroundRunCard() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val unrestricted = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    GuideCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(unrestricted)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("No battery limits", style = MaterialTheme.typography.titleMedium)
                Text(if (unrestricted) "Android won't pause Security4Her to save battery." else "Without this, Android may pause SOS features after a while.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!unrestricted) {
            Spacer(Modifier.height(10.dp))
            ChunkyButton("Allow") {
                runCatching {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { openAppSettings(ctx) }
            }
        }
    }
    GuideCard {
        Text("Your phone's own setting", style = MaterialTheme.typography.titleMedium)
        Text(brandTip(), color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        ChunkyButton("Open it", tone = Tone.Plain) { openMakerSettings(ctx) }
    }
}

@Composable
private fun BackgroundPage() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    PageIntro(
        "Keep it running",
        "Phones close apps in the background to save battery. These stop that, so Pocket SOS, your watch and Safe Journey keep working with the screen off.",
        pad = false,
    )
    Spacer(Modifier.height(8.dp))
    BackgroundRunCard()
    val lockOk = remember(tick) { PocketSos.canShowOverLockScreen(ctx) }
    if (Build.VERSION.SDK_INT >= 34) GuideCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(lockOk)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Alerts over the lock screen", style = MaterialTheme.typography.titleMedium)
                Text("So the SOS countdown and its Cancel button appear without unlocking.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!lockOk) { Spacer(Modifier.height(10.dp)); ChunkyButton("Allow") { PocketSos.askForLockScreenAlerts(ctx) } }
    }
}

// ---- Pocket SOS ----

@Composable
private fun PocketPage(onPractice: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(PocketSos.enabled(ctx)) }
    PageIntro("Pocket SOS", "Can't reach your phone's screen? Knock on it 3 times through your pocket or bag. No unlocking, no looking.", pad = false)
    Spacer(Modifier.height(8.dp))
    GuideCard {
        listOf(
            "The screen must be off. With it on, knocks are ignored, so typing and tapping never set it off.",
            "Knock firmly on the phone 3 times, about half a second apart. Use your knuckles, on the screen or the back, away from the side buttons.",
            "You feel a tick per knock and a long buzz when it's armed. Then you have 5 seconds to cancel before the SOS starts.",
        ).forEachIndexed { i, t -> StepLine(i + 1, t, active = true) }
    }
    GuideCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pocket SOS", style = MaterialTheme.typography.titleMedium)
                Text(if (on) "Listening whenever the screen is off" else "Off", color = if (on) Mint else Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = on, onCheckedChange = { on = it; PocketSos.setEnabled(ctx, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Lilac, checkedThumbColor = OnLilac, uncheckedTrackColor = Surface2, uncheckedBorderColor = Line),
            )
        }
        Spacer(Modifier.height(10.dp))
        ChunkyButton(if (PocketSos.calibrated(ctx)) "Practise and adjust strength" else "Teach it your knock", tone = Tone.Plain, onClick = onPractice)
    }
    ScreenOffTestCard()
    BackTapNote()
}

/** Phones' own tap gestures (Quick Tap, Back tap) can catch knocks and open Gemini or the Assistant. */
@Composable
internal fun BackTapNote() {
    val ctx = LocalContext.current
    GuideCard {
        Text("Gemini or the Assistant opened when you knocked?", style = MaterialTheme.typography.titleMedium, color = Amber)
        Spacer(Modifier.height(4.dp))
        Text(
            "That's your phone's own tap gesture, not Security4Her. Many phones open Gemini on a double tap on the back " +
                "(Quick Tap on Pixel, Back tap on others) or when the side button is held. In Settings, search for \"Quick Tap\" " +
                "or \"Back tap\" and turn it off, or set it to something else. Then knock with the screen off, away from the side buttons.",
            color = Muted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        ChunkyButton("Open Settings", tone = Tone.Plain) {
            runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

/**
 * The real test: the background service, the screen off, her actual knocks, but never an SOS. Afterwards it says what
 * the phone felt, so "it doesn't work" turns into a specific fix (battery limits, knock harder, too slow).
 */
@Composable
internal fun ScreenOffTestCard() {
    val ctx = LocalContext.current
    val t by PocketSos.test.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { now = SystemClock.elapsedRealtime() }
    LaunchedEffect(t) { now = SystemClock.elapsedRealtime() }
    GuideCard {
        Text("Test it with the screen off", style = MaterialTheme.typography.titleMedium)
        val x = t
        val expired = x != null && !x.passed && now > x.until
        when {
            x == null -> {
                Text("Checks your phone keeps feeling knocks with the screen off, the way it will in your pocket. Nothing is sent.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                ChunkyButton("Start the test") { PocketSos.startTest(ctx) }
            }
            x.passed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(true)
                    Spacer(Modifier.width(10.dp))
                    Text("It works on this phone. All 3 knocks were felt with the screen off.", color = Mint, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { PocketSos.endTest(ctx) }, contentPadding = PaddingValues(0.dp)) { Text("Done", color = Lilac) }
            }
            !x.screenWentOff && !expired -> {
                listOf(
                    "Press the power button to turn the screen off.",
                    "Knock 3 times on the phone, firmly, about half a second apart.",
                    "Wait for the long buzz, then turn the screen on and come back here.",
                ).forEachIndexed { i, s -> StepLine(i + 1, s, active = true) }
                TextButton(onClick = { PocketSos.endTest(ctx) }, contentPadding = PaddingValues(0.dp)) { Text("Cancel", color = Muted) }
            }
            else -> {
                val (msg, fixBattery) = when {
                    !x.screenWentOff -> "The screen never turned off. Press the power button first, then knock." to false
                    x.offMs > 0 && x.samples == 0 -> "Your phone paused the motion sensor with the screen off, so knocks can't be felt. Allow Security4Her to run in the background below, then test again." to true
                    x.knocks in 1..2 -> ("Felt ${x.knocks} of 3 knocks. " + when (x.reject) {
                        KnockDetector.Reject.TooSlow -> "Knock a little faster: about half a second apart."
                        KnockDetector.Reject.TooMany -> "Stop after 3 knocks."
                        KnockDetector.Reject.Moving -> "Hold still for a second, then knock."
                        null -> "Knock a little firmer."
                    }) to false
                    x.soft > 0 -> "Felt your knocks, but they were too soft. Knock firmer, or choose Light in Practise." to false
                    else -> "Nothing was felt. Knock firmly with your knuckles, on the screen or the back." to false
                }
                Text(msg, color = Amber, style = MaterialTheme.typography.bodyMedium)
                if (x.offMs > 0) Text("Readings with the screen off: ${x.rateHz} a second.", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (fixBattery) BackgroundRunCard()
                Spacer(Modifier.height(10.dp))
                ChunkyButton("Test again") { PocketSos.startTest(ctx) }
            }
        }
    }
}

@Composable
private fun WatchPage(onWatchInstall: () -> Unit) {
    PageIntro(
        "Your watch",
        "Got a Wear OS watch? Press and hold SOS on it, or say \"HELP HELP HELP\", even with your phone in a bag. The phone installs the watch app over Wi-Fi.",
        pad = false,
    )
    Spacer(Modifier.height(8.dp))
    GuideCard {
        StepLine(1, "On the watch: Settings → Developer options → turn on Wireless debugging, then Pair new device.", active = true)
        StepLine(2, "Here: tap Install on my watch and type the 6-digit code the watch shows.", active = true)
        StepLine(3, "Open Security4Her on the watch. It links to this phone by itself and updates itself from then on.", active = true)
        Spacer(Modifier.height(10.dp))
        ChunkyButton("Install on my watch", onClick = onWatchInstall)
    }
    Text("No watch? Tap Next.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ExtrasPage(settings: Settings, onSettings: (Settings) -> Unit) {
    PageIntro("More ways to stay safe", "All on the Home screen.", pad = false)
    Spacer(Modifier.height(8.dp))
    listOf(
        "Safe Journey" to "Tell it where you're going. If you don't check in or arrive on time, your contacts are alerted with your location.",
        "Fake call" to "A realistic incoming call from Mom, Dad or any name, now or in 10 or 30 seconds. A polite way out.",
        "Live location" to "Share a live map link with anyone, for as long as you choose.",
    ).forEach { (t, d) -> GuideCard { Text(t, style = MaterialTheme.typography.titleMedium); Text(d, color = Muted, style = MaterialTheme.typography.bodySmall) } }
    GuideCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Test Mode", style = MaterialTheme.typography.titleMedium)
                Text("Try a full SOS safely: texts and the call go to your test number instead of your contacts.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = settings.testMode, onCheckedChange = { onSettings(settings.copy(testMode = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = Amber, checkedThumbColor = Night, uncheckedTrackColor = Surface2, uncheckedBorderColor = Line),
            )
        }
    }
}

@Composable
private fun DonePage(contacts: List<Contact>?) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val checks = remember(tick, contacts) {
        listOf(
            "Someone gets your SOS" to (contacts.orEmpty().any { it.receivesSos }),
            "Location" to granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION),
            "Automatic texts" to Sms.allowed(ctx),
            "Runs in the background" to ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
            "Pocket SOS" to PocketSos.enabled(ctx),
        )
    }
    Text(if (checks.all { it.second }) "You're all set" else "Nearly there", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        if (checks.all { it.second }) "Hold the SOS button on Home for 1.5 seconds in an emergency. Everything else happens by itself."
        else "These still need you. Go back to finish them now, or later from Me → Setup guide.",
        color = Muted, style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(14.dp))
    checks.forEach { (t, ok) ->
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok, warn = !ok)
            Spacer(Modifier.width(12.dp))
            Text(t, style = MaterialTheme.typography.titleMedium, color = if (ok) Ink else Amber)
        }
    }
}
