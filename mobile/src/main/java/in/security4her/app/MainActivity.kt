package `in`.security4her.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.security4her.app.core.Backend
import `in`.security4her.app.core.Contact
import `in`.security4her.app.core.Profile
import `in`.security4her.app.core.Settings
import `in`.security4her.app.core.Store
import `in`.security4her.app.core.friendly
import `in`.security4her.app.sos.Incident
import `in`.security4her.app.sos.SosEngine
import `in`.security4her.app.sos.WearBridge
import `in`.security4her.app.sos.Sms
import `in`.security4her.app.sos.LiveShare
import `in`.security4her.app.core.AppLog
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import `in`.security4her.app.sos.ShareSheet
import `in`.security4her.app.sos.Locator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import android.widget.Toast
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        `in`.security4her.app.core.Community.init(this)
        Backend.init(this)
        SosEngine.init(this)
        `in`.security4her.app.sos.Journey.init(this)
        fun has(p: String) = if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) "on" else "off"
        AppLog.info(
            "App",
            "Opened · ${if (Backend.mock) "demo mode" else "server mode"} · location ${has(Manifest.permission.ACCESS_FINE_LOCATION)}, " +
                "SMS ${has(Manifest.permission.SEND_SMS)}, calls ${has(Manifest.permission.CALL_PHONE)}",
        )
    }
}

// ---- Tokens: aubergine night, one lilac accent; red is reserved for SOS, amber for Test Mode ----
internal val Night = Color(0xFF16101E)
internal val Surface1 = Color(0xFF221A2C)
internal val Surface2 = Color(0xFF2E2439)
internal val Line = Color(0xFF3D3149)
internal val Ink = Color(0xFFF3EDF7)
internal val Muted = Color(0xFFA99BB8)
internal val Lilac = Color(0xFFC49BFF)
internal val OnLilac = Color(0xFF1E0E33)
internal val SosRed = Color(0xFFFF4D5E)
internal val Amber = Color(0xFFFFB547)
internal val LiveGreen = Color(0xFF5EE0A0) // only for "live location is on"

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun instrument(w: Int) = Font(R.font.instrument_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
internal val Instrument = FontFamily(instrument(400), instrument(500), instrument(600), instrument(700))

@Composable
private fun S4hTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Lilac, onPrimary = OnLilac, secondary = Lilac, onSecondary = OnLilac,
        background = Night, onBackground = Ink, surface = Surface1, onSurface = Ink,
        surfaceVariant = Surface2, onSurfaceVariant = Muted, surfaceContainer = Surface1,
        surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface2, surfaceContainerLow = Surface1,
        outline = Line, outlineVariant = Line, error = SosRed, onError = Color.White,
    )
    val f = Instrument
    val base = Typography()
    val type = base.copy(
        displaySmall = TextStyle(fontFamily = f, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        headlineMedium = TextStyle(fontFamily = f, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineSmall = TextStyle(fontFamily = f, fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleMedium = TextStyle(fontFamily = f, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontFamily = f, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontFamily = f, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = f, fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontFamily = f, fontSize = 12.5.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = f, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontFamily = f, fontSize = 13.sp, fontWeight = FontWeight.Medium),
    )
    MaterialTheme(colorScheme = scheme, typography = type) {
        CompositionLocalProvider(LocalContentColor provides Ink, content = content)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { S4hTheme { Root() } }
    }
}

private enum class Stage { Loading, Auth, Verify, Onboard, Main }
private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home), Contacts("Contacts", Icons.Filled.Person), Map("Map", Icons.Filled.Place), Settings("Settings", Icons.Filled.SettingsIcon)
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<Profile?>(null) }
    var stage by remember { mutableStateOf(Stage.Loading) }
    val incident by SosEngine.current.collectAsStateWithLifecycle()
    val journey by `in`.security4her.app.sos.Journey.state.collectAsStateWithLifecycle()

    fun route(p: Profile?) {
        profile = p
        stage = when {
            p == null -> Stage.Auth
            !p.verifiedFemale -> Stage.Verify
            !Store.prefs(ctx).getBoolean("onboarded", false) -> Stage.Onboard
            else -> Stage.Main
        }
        if (stage == Stage.Main) scope.launch { WearBridge.linkWatch(ctx) }
    }

    LaunchedEffect(Unit) { route(runCatching { Backend.restore() }.getOrNull()) }

    Box(Modifier.fillMaxSize().background(Night).systemBarsPadding().imePadding()) {
        val inc = incident
        when {
            inc != null && stage == Stage.Main -> ActiveSosScreen(inc)
            journey?.status == "checking" && stage == Stage.Main -> CheckInScreen(journey!!)
            stage == Stage.Loading -> Loading()
            stage == Stage.Auth -> AuthScreen(onDone = ::route)
            stage == Stage.Verify -> VerifyScreen(profile!!, onDone = ::route)
            stage == Stage.Onboard -> PermissionsScreen(onDone = {
                Store.prefs(ctx).edit().putBoolean("onboarded", true).apply()
                route(profile)
            })
            else -> MainScreen(profile!!, onSignedOut = { route(null) }, onProfile = { profile = it })
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(104.dp)
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(Modifier.size(22.dp), color = Lilac, strokeWidth = 2.dp)
        }
    }
}

@Composable
internal fun Logo(size: androidx.compose.ui.unit.Dp) {
    Image(painterResource(R.drawable.logo_cat), contentDescription = "Security4Her", modifier = Modifier.size(size))
}

@Composable
private fun Shield(size: androidx.compose.ui.unit.Dp, tint: Color = Lilac) {
    Image(painterResource(R.drawable.ic_shield), null, Modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

// ---------------------------------------------------------------- Auth

@Composable
private fun AuthScreen(onDone: (Profile) -> Unit) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 40.dp)) {
        Logo(84.dp)
        Spacer(Modifier.height(28.dp))
        Text("Security4Her", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "Knock on your watch or hold one button. Your people get your live location in seconds.",
            style = MaterialTheme.typography.bodyLarge, color = Muted,
        )
        Spacer(Modifier.height(36.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).padding(4.dp)) {
            listOf(true to "Create account", false to "Sign in").forEach { (c, label) ->
                val sel = creating == c
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) Surface2 else Color.Transparent)
                        .clickable { creating = c; error = null }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (sel) Ink else Muted, style = MaterialTheme.typography.labelLarge) }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (creating) {
            Field(name, { name = it }, "Your name")
            Spacer(Modifier.height(12.dp))
        }
        Field(email, { email = it.trim() }, "Email", KeyboardType.Email)
        Spacer(Modifier.height(12.dp))
        Field(password, { password = it }, "Password", KeyboardType.Password, secret = true)
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = SosRed, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(if (creating) "Create account" else "Sign in", busy = busy, enabled = email.contains('@') && password.length >= 6 && (!creating || name.isNotBlank())) {
            busy = true
            error = null
            scope.launch {
                try {
                    onDone(if (creating) Backend.signUp(name, email, password) else Backend.signIn(email, password))
                } catch (e: Exception) {
                    error = e.friendly()
                    AppLog.warn("Account", error!!, e)
                } finally {
                    busy = false
                }
            }
        }
        if (Backend.mock) {
            Spacer(Modifier.height(28.dp))
            Note("Demo mode: no server is set up, so your account and contacts stay on this phone.")
        }
    }
}

// ---------------------------------------------------------------- Verify

@Composable
private fun VerifyScreen(profile: Profile, onDone: (Profile) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp)) {
        Text("One check before you start", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Security4Her is only for women, which keeps the community map and alerts trustworthy. " +
                "DigiLocker confirms it once. We keep the result, never your documents.",
            style = MaterialTheme.typography.bodyLarge, color = Muted,
        )
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
                Text((profile.displayName ?: "?").take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Lilac)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(profile.displayName ?: "You", style = MaterialTheme.typography.titleMedium)
                Text("Not verified yet", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = SosRed) }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Verify with DigiLocker", busy = busy) {
            busy = true
            scope.launch {
                try {
                    // MOCK: DIGILOCKER_REAL = false — real flow opens digilocker-start in a Custom Tab (CONTRACT.md §3)
                    if (Backend.mockVerifyFemale()) onDone(Backend.profile()) else error = "Verification didn't go through. Try again."
                } catch (e: Exception) {
                    error = e.friendly()
                    AppLog.warn("Account", error!!, e)
                } finally {
                    busy = false
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Demo verification. No documents are read.", color = Muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------- Permissions

private data class Perm(val key: String, val title: String, val why: String, val granted: (Context) -> Boolean, val ask: () -> Unit)

@Composable
private fun PermissionsScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf(setOf<String>()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun request(key: String, vararg p: String) {
        asked = asked + key
        launcher.launch(arrayOf(*p))
    }

    val perms = buildList {
        add(Perm("loc", "Location", "Your texts and live link show where you are.", { has(Manifest.permission.ACCESS_FINE_LOCATION) }) {
            request("loc", Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        })
        if (Build.VERSION.SDK_INT >= 29) add(
            Perm("bg", "Location all the time", "Lets your watch start SOS while the phone is locked. Choose \"Allow all the time\".",
                { has(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                if (has(Manifest.permission.ACCESS_FINE_LOCATION)) request("bg", Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                else request("loc", Manifest.permission.ACCESS_FINE_LOCATION)
            },
        )
        add(Perm("sms", "Text messages", "Texts your contacts with your location the moment SOS starts.", { has(Manifest.permission.SEND_SMS) }) {
            request("sms", Manifest.permission.SEND_SMS)
        })
        add(Perm("sim", "Choose the right SIM", "On dual-SIM phones, sends from the SIM you use for texts.", { has(Manifest.permission.READ_PHONE_STATE) }) {
            request("sim", Manifest.permission.READ_PHONE_STATE)
        })
        add(Perm("call", "Phone calls", "Calls your chosen number when the countdown ends.", { has(Manifest.permission.CALL_PHONE) }) {
            request("call", Manifest.permission.CALL_PHONE)
        })
        if (Build.VERSION.SDK_INT >= 33) add(
            Perm("notif", "Notifications", "Shows SOS progress and the Cancel call button.", { has(Manifest.permission.POST_NOTIFICATIONS) }) {
                request("notif", Manifest.permission.POST_NOTIFICATIONS)
            },
        )
        add(Perm("battery", "Run in the background", "Stops Android from pausing SOS to save battery.",
            { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) }) {
            runCatching {
                ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }
        })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp)) {
        Text("Let SOS work when it matters", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Each of these is used only during an SOS.", color = Muted, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(20.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            key(tick) {
                perms.forEach { p ->
                    val ok = p.granted(ctx)
                    val blocked = !ok && p.key in asked && p.key != "battery"
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.title, style = MaterialTheme.typography.titleMedium)
                                Text(p.why, color = Muted, style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.width(12.dp))
                            when {
                                ok -> Text("Allowed", color = Lilac, style = MaterialTheme.typography.labelLarge)
                                blocked -> OutlinedButton(onClick = {
                                    ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                                }, border = androidx.compose.foundation.BorderStroke(1.dp, Amber)) { Text("Settings", color = Amber) }
                                else -> OutlinedButton(onClick = p.ask, border = androidx.compose.foundation.BorderStroke(1.dp, Lilac)) { Text("Allow", color = Lilac) }
                            }
                        }
                        if (blocked && p.key == "sms") {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Android blocks SMS for apps installed outside the Play Store. In Settings, tap ⋮ (top right) → " +
                                    "Allow restricted settings, then Permissions → SMS → Allow.",
                                color = Amber, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Continue", onClick = onDone)
    }
}

// ---------------------------------------------------------------- Main shell

@Composable
private fun MainScreen(profile: Profile, onSignedOut: () -> Unit, onProfile: (Profile) -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var settings by remember { mutableStateOf(Settings.load(ctx)) }
    var contacts by remember { mutableStateOf<List<Contact>?>(null) }
    var contactsError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        try {
            contacts = Backend.contacts()
            contactsError = null
        } catch (e: Exception) {
            contactsError = e.friendly()
            if (contacts == null) contacts = emptyList()
        }
    }
    var showLog by rememberSaveable { mutableStateOf(false) }
    BackHandler(tab != Tab.Home && !showLog) { tab = Tab.Home }
    if (showLog) {
        LogScreen { showLog = false }
        return
    }
    var showJourney by rememberSaveable { mutableStateOf(false) }
    if (showJourney) {
        BackHandler { showJourney = false }
        JourneySetupScreen(onClose = { showJourney = false })
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (settings.testMode) TestBanner(settings.demoNumber.isBlank()) { tab = Tab.Settings }
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.Home -> HomeScreen(profile, contacts, settings, onTab = { tab = it }, onJourney = { showJourney = true })
                Tab.Contacts -> ContactsScreen(contacts, contactsError) { reload++ }
                Tab.Map -> SafeMapScreen()
                Tab.Settings -> SettingsScreen(profile, settings, onChange = { settings = it }, onSignedOut = onSignedOut, onOpenLog = { showLog = true })
            }
        }
        TabBar(tab) { tab = it }
    }
}

@Composable
private fun TestBanner(noDemoNumber: Boolean, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Amber.copy(alpha = 0.12f)).clickable(onClick = onFix).padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Amber))
        Spacer(Modifier.width(10.dp))
        Text(
            if (noDemoNumber) "Test Mode: set a demo number so police texts and calls reach a teammate."
            else "Test Mode: police texts and calls go to your demo number, never 112.",
            color = Amber, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Surface1).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Tab.entries.forEach { t ->
            val sel = t == tab
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onTab(t) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) Lilac.copy(alpha = 0.16f) else Color.Transparent)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                ) { Icon(t.icon, contentDescription = null, tint = if (sel) Lilac else Muted, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.height(4.dp))
                Text(t.label, color = if (sel) Ink else Muted, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// ---------------------------------------------------------------- Home

private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@android.annotation.SuppressLint("MissingPermission")
private suspend fun hereNow(ctx: Context): android.location.Location? = runCatching {
    if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) return null
    val c = LocationServices.getFusedLocationProviderClient(ctx)
    withTimeoutOrNull(8_000) { c.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() } ?: c.lastLocation.await()
}.getOrNull()

private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
}

@Composable
private fun HomeScreen(profile: Profile, contacts: List<Contact>?, settings: Settings, onTab: (Tab) -> Unit, onJourney: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var watch by remember { mutableStateOf<Boolean?>(null) }
    var locOn by remember { mutableStateOf(has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) }
    var sharing by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { locOn = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) }
    LaunchedEffect(Unit) {
        while (true) {
            watch = WearBridge.watchReachable(ctx)
            delay(10_000)
        }
    }
    val alerted = contacts?.filter { it.receivesSos }.orEmpty()
    val name = profile.displayName ?: "there"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting(), color = Muted, style = MaterialTheme.typography.bodyMedium)
                Text(name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Avatar(name, 46.dp)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(Modifier.weight(1f), "Location", if (locOn) "On" else "Off", locOn)
            StatusPill(Modifier.weight(1f), "Contacts", if (contacts == null) "…" else "${alerted.size} ready", alerted.isNotEmpty()) { onTab(Tab.Contacts) }
            StatusPill(Modifier.weight(1f), "Watch", when (watch) { true -> "Linked"; false -> "Not nearby"; null -> "…" }, watch == true)
        }
        Spacer(Modifier.height(26.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SosHoldButton { SosEngine.start(ctx, trigger = "button", source = "phone") }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Hold for 1.5 seconds. Your location and a live link go out by text, then a call follows after ${settings.countdownSec} s.",
            color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        val live by LiveShare.session.collectAsStateWithLifecycle()
        fun sendLink(url: String) = ShareSheet.text(ctx, "Follow my live location: $url (updates every 20 s). $name, via Security4Her", "Send live link")
        JourneyHomeCard(onStart = onJourney)
        Spacer(Modifier.height(12.dp))
        live?.let { s ->
            LiveCard(s, onSend = { sendLink(s.url) }, onStop = { LiveShare.stop(ctx) })
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Modifier.weight(1f), Icons.Filled.Share, if (live == null) "Live location" else "Send link", LiveGreen) {
                if (!locOn) {
                    Toast.makeText(ctx, "Allow location first.", Toast.LENGTH_SHORT).show()
                    return@QuickAction
                }
                if (!Locator.enabled(ctx)) {
                    Toast.makeText(ctx, "Turn on location in quick settings first.", Toast.LENGTH_LONG).show()
                    runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    return@QuickAction
                }
                val s = live ?: LiveShare.start(ctx)
                sendLink(s.url)
            }
            QuickAction(Modifier.weight(1f), Icons.Filled.Call, "Call 112", SosRed) {
                ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
            }
            QuickAction(Modifier.weight(1f), Icons.Filled.Place, "Safe places", Lilac) { onTab(Tab.Map) }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1).padding(18.dp)) {
            Text("Who hears from you", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            when {
                contacts == null -> Text("Loading contacts", color = Muted)
                alerted.isEmpty() -> {
                    Text("No one yet. Add at least one trusted contact so your SOS reaches someone.", color = Muted)
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = { onTab(Tab.Contacts) }, border = androidx.compose.foundation.BorderStroke(1.dp, Lilac)) {
                        Text("Add contacts", color = Lilac)
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                        alerted.take(5).forEach { Avatar(it.name, 40.dp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    val police = if (settings.testMode) settings.demoNumber.ifBlank { null }?.let { "your demo number" } else "police ${settings.policeNumber}"
                    Text(
                        alerted.joinToString(", ") { it.name } + (police?.let { ", and $it" } ?: ""),
                        color = Muted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveCard(s: `in`.security4her.app.sos.LiveSession, onSend: () -> Unit, onStop: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.topic) { while (true) { now = System.currentTimeMillis(); delay(10_000) } }
    val left = ((s.until - now) / 60_000).coerceAtLeast(0)
    val breathe = rememberInfiniteTransition(label = "live")
    val a by breathe.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1).border(1.dp, LiveGreen.copy(alpha = 0.35f), RoundedCornerShape(20.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(LiveGreen.copy(alpha = a)))
            Spacer(Modifier.width(10.dp))
            Text("Sharing your live location", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("$left min left", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                s.failing -> "No internet right now. Updates resume when you're back online."
                s.pushes == 0 -> "Finding you…"
                else -> "${s.pushes} ${if (s.pushes == 1) "update" else "updates"} sent. Anyone with the link sees where you are."
            },
            color = if (s.failing) Amber else Muted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSend, colors = ButtonDefaults.buttonColors(containerColor = LiveGreen, contentColor = Color(0xFF06270F))) { Text("Send link", fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = onStop, border = androidx.compose.foundation.BorderStroke(1.dp, SosRed)) { Text("Stop sharing", color = SosRed) }
        }
    }
}

@Composable
private fun StatusPill(modifier: Modifier, label: String, value: String, ok: Boolean, onClick: (() -> Unit)? = null) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Surface1)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) Lilac else Amber))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QuickAction(modifier: Modifier, icon: ImageVector, label: String, tint: Color, busy: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Surface1).clickable(onClick = onClick).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = tint, strokeWidth = 2.dp)
            else Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SosHoldButton(onFire: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var holding by remember { mutableStateOf(false) }
    val breathe = rememberInfiniteTransition(label = "breathe")
    val halo by breathe.animateFloat(0.86f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "halo")
    Box(
        Modifier.size(256.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                holding = true
                val job = scope.launch {
                    progress.animateTo(1f, tween(1500, easing = LinearEasing))
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onFire()
                    progress.snapTo(0f)
                }
                tryAwaitRelease()
                holding = false
                if (job.isActive) {
                    job.cancel()
                    scope.launch { progress.animateTo(0f, tween(220)) }
                }
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 9.dp.toPx()
            val inset = w / 2
            val r = size.minDimension / 2
            drawCircle(SosRed.copy(alpha = 0.10f), radius = (r - inset) * (if (holding) 1f else halo))
            drawCircle(SosRed.copy(alpha = 0.18f), radius = r - inset, style = Stroke(w))
            drawArc(
                SosRed, -90f, 360f * progress.value, false,
                topLeft = Offset(inset, inset), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round),
            )
        }
        Box(Modifier.size(188.dp).clip(CircleShape).background(SosRed), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SOS", color = Color.White, fontSize = 58.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontFamily = Instrument)
                Text(if (holding) "Keep holding" else "Hold to send", color = Color.White.copy(alpha = 0.88f), fontSize = 14.sp)
            }
        }
    }
}

@Composable
internal fun Avatar(name: String, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Surface2).border(2.dp, Night, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(name.trim().take(1).uppercase(), color = Lilac, fontWeight = FontWeight.SemiBold) }
}

// ---------------------------------------------------------------- Contacts

@Composable
private fun ContactsScreen(contacts: List<Contact>?, error: String?, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Contact?>(null) }
    var busyError by remember { mutableStateOf<String?>(null) }
    val list = contacts.orEmpty()

    fun save(c: Contact) = scope.launch {
        try { Backend.saveContact(c); busyError = null } catch (e: Exception) { busyError = e.friendly() }
        onChanged()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.query(
            uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) editing = Contact(name = c.getString(0).orEmpty(), phone = c.getString(1).orEmpty(), priority = list.size + 1)
        }
    }
    fun pick() = runCatching { picker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
        .onFailure { editing = Contact(name = "", phone = "", priority = list.size + 1) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Trusted contacts", style = MaterialTheme.typography.headlineSmall)
                if (list.isNotEmpty()) Text("Texted in this order during SOS", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            if (list.isNotEmpty()) TextButton(onClick = { editing = Contact(name = "", phone = "", priority = list.size + 1) }) { Text("Add", color = Lilac) }
        }
        (busyError ?: error)?.let { Text(it, color = SosRed, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(8.dp)) }
        when {
            contacts == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Lilac) }
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(bottom = 40.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Logo(76.dp)
                Spacer(Modifier.height(18.dp))
                Text("Who should hear from you first?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Add family or friends. They get a text with your live location when SOS starts.",
                    color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(24.dp))
                PrimaryButton("Pick from phone contacts", onClick = { pick() })
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { editing = Contact(name = "", phone = "", priority = 1) }) { Text("Type a number instead", color = Lilac) }
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list.forEachIndexed { i, c ->
                    ContactRow(
                        c, order = i + 1, first = i == 0, last = i == list.lastIndex,
                        onToggle = { save(c.copy(receivesSos = it)) },
                        onMove = { dir ->
                            val other = list[i + dir]
                            scope.launch {
                                runCatching {
                                    Backend.saveContact(c.copy(priority = i + 1 + dir))
                                    Backend.saveContact(other.copy(priority = i + 1))
                                }
                                onChanged()
                            }
                        },
                        onEdit = { editing = c },
                    )
                }
                OutlinedButton(onClick = { pick() }, modifier = Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                    Text("Pick from phone contacts", color = Ink)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    editing?.let { c ->
        ContactDialog(
            c,
            onDismiss = { editing = null },
            onSave = { editing = null; save(it) },
            onDelete = if (c.id.isBlank()) null else ({
                editing = null
                scope.launch { runCatching { Backend.deleteContact(c.id) }; onChanged() }
            }),
        )
    }
}

@Composable
private fun ContactRow(c: Contact, order: Int, first: Boolean, last: Boolean, onToggle: (Boolean) -> Unit, onMove: (Int) -> Unit, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).clickable(onClick = onEdit).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$order", color = Lilac, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.width(26.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(c.relation?.ifBlank { null }, c.phone).joinToString("  ·  "), color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(
                checked = c.receivesSos, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = Lilac, checkedThumbColor = OnLilac, uncheckedTrackColor = Surface2, uncheckedBorderColor = Line),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    c.telegramChatId != null -> "Telegram linked. Gets alerts even if your phone is off."
                    c.telegramLinkCode != null -> "For alerts when your phone is off, ask them to send /start ${c.telegramLinkCode} to our Telegram bot."
                    else -> ""
                },
                color = if (c.telegramChatId != null) Lilac else Muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(start = 26.dp),
            )
            if (!first) TextButton(onClick = { onMove(-1) }) { Text("Up", color = Muted) }
            if (!last) TextButton(onClick = { onMove(1) }) { Text("Down", color = Muted) }
        }
    }
}

@Composable
private fun ContactDialog(c: Contact, onDismiss: () -> Unit, onSave: (Contact) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(c.name) }
    var phone by remember { mutableStateOf(c.phone) }
    var relation by remember { mutableStateOf(c.relation.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text(if (c.id.isBlank()) "Add contact" else "Edit contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(name, { name = it }, "Name")
                Field(phone, { phone = it }, "Phone number", KeyboardType.Phone)
                Field(relation, { relation = it }, "Relation (optional)")
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && phone.count { it.isDigit() } >= 3, onClick = {
                onSave(c.copy(name = name.trim(), phone = phone.trim(), relation = relation.trim().ifBlank { null }))
            }) { Text("Save", color = Lilac) }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Remove", color = SosRed) } }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
            }
        },
    )
}

// ---------------------------------------------------------------- Settings

@Composable
private fun SettingsScreen(profile: Profile, settings: Settings, onChange: (Settings) -> Unit, onSignedOut: () -> Unit, onOpenLog: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var linkMsg by remember { mutableStateOf<String?>(null) }
    val testStatus by Sms.testStatus.collectAsStateWithLifecycle()
    fun set(s: Settings) {
        s.save(ctx)
        onChange(s)
        if (s.testMode != settings.testMode) scope.launch { WearBridge.linkWatch(ctx) }
    }
    val switchColors = SwitchDefaults.colors(checkedTrackColor = Lilac, checkedThumbColor = OnLilac, uncheckedTrackColor = Surface2, uncheckedBorderColor = Line)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))

        Section("Test Mode") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Send police texts and calls to a demo number", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.testMode, onCheckedChange = { set(settings.copy(testMode = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Amber, checkedThumbColor = Night, uncheckedTrackColor = Surface2, uncheckedBorderColor = Line),
                )
            }
            Spacer(Modifier.height(12.dp))
            Field(settings.demoNumber, { set(settings.copy(demoNumber = it)) }, "Demo number (a teammate's phone)", KeyboardType.Phone)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { Sms.sendTest(ctx, settings.demoNumber) }, enabled = settings.demoNumber.count(Char::isDigit) >= 3,
                border = androidx.compose.foundation.BorderStroke(1.dp, Lilac),
            ) { Text("Send a test text", color = Lilac) }
            Text(
                testStatus ?: "Checks that this phone can send SOS texts, without starting an SOS.",
                color = when {
                    testStatus?.startsWith("Failed") == true -> SosRed
                    testStatus?.startsWith("Delivered") == true -> Lilac
                    else -> Muted
                },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
            )
        }
        SafeJourneySection()
        Section("Location updates") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Text my location every 5 minutes")
                    Text(
                        "During an SOS, contacts also get a fresh Google Maps link by text, so they can follow you without internet.",
                        color = Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = settings.smsUpdates, onCheckedChange = { set(settings.copy(smsUpdates = it)) }, colors = switchColors)
            }
        }
        Section("During SOS") {
            Field(settings.policeNumber, { set(settings.copy(policeNumber = it)) }, "Police text number", KeyboardType.Phone)
            Spacer(Modifier.height(10.dp))
            Field(settings.autoCallNumber, { set(settings.copy(autoCallNumber = it)) }, "Number to call (blank: your first contact)", KeyboardType.Phone)
            Spacer(Modifier.height(10.dp))
            Field(
                settings.countdownSec.toString(), { v -> v.filter(Char::isDigit).take(2).toIntOrNull()?.let { set(settings.copy(countdownSec = it)) } },
                "Seconds before the call", KeyboardType.Number,
            )
            Spacer(Modifier.height(10.dp))
            Field(
                settings.pin, { v -> set(settings.copy(pin = v.filter(Char::isDigit).take(4))) },
                "PIN to end an SOS (4 digits, optional)", KeyboardType.NumberPassword, secret = true,
            )
        }
        Section("Watch") {
            Text("Your watch links to this account automatically. Re-link if it shows the wrong name.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = {
                linkMsg = "Re-linking"
                scope.launch {
                    val r = WearBridge.linkWatch(ctx)
                    linkMsg = if (r.isSuccess) {
                        if (WearBridge.watchReachable(ctx)) "Watch re-linked." else "Link saved. The watch picks it up when it's nearby."
                    } else "Couldn't re-link. Check your connection."
                }
            }, border = androidx.compose.foundation.BorderStroke(1.dp, Lilac)) { Text("Re-link watch", color = Lilac) }
            linkMsg?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
        Section("Activity log") {
            val count = AppLog.entries.collectAsStateWithLifecycle().value.size
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenLog).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Open the activity log")
                    Text("Every SOS step, text, location fix and error ($count entries). Share it when something doesn't work.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("›", color = Lilac, fontSize = 26.sp, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Section("Account") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(profile.displayName ?: "?", 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.displayName ?: "You", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (profile.verificationMethod == "digilocker") "Verified with DigiLocker" else "Verified (demo)",
                        color = Lilac, style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { scope.launch { runCatching { Backend.signOut() }; onSignedOut() } }) { Text("Sign out", color = SosRed) }
            }
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).padding(16.dp), content = content)
    Spacer(Modifier.height(22.dp))
}

// ---------------------------------------------------------------- Active SOS

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveSosScreen(inc: Incident) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var askPin by remember { mutableStateOf(false) }
    var resending by remember { mutableStateOf(false) }
    LaunchedEffect(inc.active) { while (inc.active) { now = System.currentTimeMillis(); delay(250) } }
    BackHandler(inc.active) {}
    val secs = ((now - inc.startedAt) / 1000).coerceAtLeast(0)
    val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    val locationOn = remember(now / 3000) { Locator.enabled(ctx) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(if (inc.active) SosRed else Surface1).padding(horizontal = 24.dp, vertical = 22.dp)) {
            Text(
                when (inc.state) { "active" -> "SOS active"; "resolved" -> "You're marked safe"; else -> "SOS cancelled" },
                color = Color.White, style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (inc.active) "Running for %d:%02d".format(secs / 60, secs % 60) + if (inc.test) "  ·  Test Mode" else ""
                else if (inc.smsSent > 0) "Your contacts are getting an \"I'm safe\" text." else "No texts had gone out, so no one was told.",
                color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyLarge,
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Call
            Card {
                val left = inc.callAt?.let { kotlin.math.ceil((it - now) / 1000.0).toInt().coerceAtLeast(0) }
                Text(
                    when (inc.call) {
                        "waiting" -> if (left != null) "Calling ${inc.callNumber} in $left s" else "Preparing call"
                        "placed" -> "Call placed to ${inc.callNumber}"
                        "cancelled" -> "Auto-call cancelled"
                        "skipped" -> "No auto-call number set"
                        else -> "Couldn't place the call"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (inc.active && inc.call == "waiting" && inc.callAt != null) {
                        OutlinedButton(onClick = { SosEngine.cancelCall() }, border = androidx.compose.foundation.BorderStroke(1.dp, Ink)) {
                            Text("Cancel call", color = Ink)
                        }
                    }
                    Button(
                        onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))) },
                        colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White),
                    ) {
                        Icon(Icons.Filled.Call, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Call 112")
                    }
                }
            }
            if (inc.active && !locationOn) Card {
                Text("Location is switched off", style = MaterialTheme.typography.titleMedium, color = Amber)
                Text("Your contacts can't see where you are until it's on.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS)) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Night),
                ) { Text("Turn on location") }
            }
            // Location
            Card {
                Text("Your location", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                if (inc.lat != null && inc.lng != null) {
                    Text(
                        "%.5f, %.5f  ±%s m, fixed %s".format(Locale.US, inc.lat, inc.lng, inc.acc?.roundToInt() ?: "?", clock.format(Date(inc.fixAt ?: now))),
                        color = Muted, style = MaterialTheme.typography.bodyMedium,
                    )
                } else Text("Waiting for GPS. Texts send your location as soon as it's found.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                inc.liveUrl?.let { url ->
                    Spacer(Modifier.height(10.dp))
                    Text("Live link", style = MaterialTheme.typography.titleSmall)
                    Text(url, color = Lilac, style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            !inc.active -> "Stopped. The page now says you're safe."
                            inc.liveFailing -> "No internet right now. Updates resume when you're back online."
                            inc.livePushes == 0 -> "Starting…"
                            else -> "${inc.livePushes} ${if (inc.livePushes == 1) "update" else "updates"} sent, one every 20 s. The link is in every SOS text."
                        },
                        color = if (inc.liveFailing && inc.active) Amber else Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    if (inc.active) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(
                            onClick = { ShareSheet.text(ctx, "Follow my live location: $url", "Send live link") },
                            border = androidx.compose.foundation.BorderStroke(1.dp, LiveGreen),
                        ) { Text("Share live link", color = LiveGreen) }
                        TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text("Open", color = Ink) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (inc.active && inc.recipients.isNotEmpty()) OutlinedButton(
                        onClick = { resending = true; SosEngine.resendLocation(); scope.launch { delay(4000); resending = false } },
                        enabled = !resending, border = androidx.compose.foundation.BorderStroke(1.dp, Lilac),
                    ) { Text(if (resending) "Sending…" else "Text it again", color = Lilac) }
                    if (inc.lat != null) TextButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${inc.lat},${inc.lng}?q=${inc.lat},${inc.lng}"))) }
                    }) { Text("Open in Maps", color = Ink) }
                }
            }
            // Texts
            if (inc.recipients.isNotEmpty()) Card {
                Text("Texts", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                inc.recipients.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(r.name, 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.error ?: r.phone, color = if (r.error != null) SosRed else Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            when (r.state) { "sending" -> "Sending"; "sent" -> "Sent"; "delivered" -> "Delivered"; else -> "Failed" },
                            color = when (r.state) { "failed" -> SosRed; "sending" -> Muted; else -> Lilac },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            // Timeline
            Card {
                Text("What happened", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                inc.events.forEachIndexed { i, e ->
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(14.dp)) {
                            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(
                                when (e.kind) { "ok" -> Lilac; "fail" -> SosRed; else -> Muted },
                            ))
                            if (i < inc.events.lastIndex) Box(Modifier.width(1.dp).weight(1f).background(Line))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.padding(bottom = 12.dp)) {
                            Text(e.text, style = MaterialTheme.typography.bodyMedium)
                            Text(clock.format(Date(e.at)), color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(18.dp)) {
            if (inc.active) {
                PrimaryButton("I'm safe") {
                    if (Settings.load(ctx).pin.isBlank()) SosEngine.end("resolved") else askPin = true
                }
            } else PrimaryButton("Back to home") { SosEngine.dismiss() }
        }
    }

    if (askPin) {
        var pin by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askPin = false },
            containerColor = Surface1,
            title = { Text("Enter your PIN") },
            text = {
                Column {
                    Field(pin, { pin = it.filter(Char::isDigit).take(4); wrong = false }, "4-digit PIN", KeyboardType.NumberPassword, secret = true)
                    if (wrong) Text("That PIN doesn't match.", color = SosRed, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pin == Settings.load(ctx).pin) { askPin = false; SosEngine.end("resolved") } else wrong = true
                }) { Text("End SOS", color = Lilac) }
            },
            dismissButton = { TextButton(onClick = { askPin = false }) { Text("Keep SOS on", color = Muted) } },
        )
    }
}

@Composable
internal fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).padding(18.dp), content = content)
}

// ---------------------------------------------------------------- Activity log

@Composable
private fun LogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val all by AppLog.entries.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("All") }
    var confirmClear by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val filters = listOf("All", "Problems", "SOS", "Texts", "Location", "Live", "Map")
    val shown = remember(all, filter) {
        all.asReversed().filter { e ->
            when (filter) {
                "Problems" -> e.level == "warn" || e.level == "error"
                "Texts" -> e.tag == "SMS" || (e.tag == "SOS" && "text" in e.msg.lowercase())
                "All" -> true
                else -> e.tag == filter
            }
        }
    }
    val day = SimpleDateFormat("EEE d MMM", Locale.US)
    val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    fun dayLabel(at: Long): String {
        val d = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val t = java.util.Calendar.getInstance()
        val diff = t.get(java.util.Calendar.DAY_OF_YEAR) - d.get(java.util.Calendar.DAY_OF_YEAR) + 365 * (t.get(java.util.Calendar.YEAR) - d.get(java.util.Calendar.YEAR))
        return when (diff) { 0 -> "Today"; 1 -> "Yesterday"; else -> day.format(Date(at)) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  Back", color = Lilac) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, AppLog.export(shown.asReversed())), "Share activity log"))
            }, enabled = shown.isNotEmpty()) { Text("Share", color = Lilac) }
            TextButton(onClick = { confirmClear = true }, enabled = all.isNotEmpty()) { Text("Clear", color = SosRed) }
        }
        Text("Activity log", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            "${all.size} entries, kept only on this phone. Share them with your team when something doesn't work.",
            color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { f ->
                val sel = f == filter
                val problems = if (f == "Problems") all.count { it.level == "warn" || it.level == "error" } else 0
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(if (sel) Lilac else Surface1).clickable { filter = f }.padding(horizontal = 14.dp, vertical = 7.dp),
                ) { Text(if (problems > 0) "$f  $problems" else f, color = if (sel) OnLilac else Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
        if (shown.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Logo(72.dp)
                Spacer(Modifier.height(14.dp))
                Text(if (filter == "Problems") "No problems logged" else "Nothing logged yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (filter == "Problems") "Failed texts, missing GPS and connection errors show up here." else "Each SOS, text, location fix and live update is recorded here as it happens.",
                    color = Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            var lastDay = ""
            shown.forEachIndexed { i, e ->
                val d = dayLabel(e.at)
                if (d != lastDay) {
                    lastDay = d
                    item(key = "day-$i") {
                        Text(d, color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
                    }
                }
                item(key = "entry-$i") {
                    val c = when (e.level) { "ok" -> Lilac; "warn" -> Amber; "error" -> SosRed; else -> Muted }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp)) {
                        Text(time.format(Date(e.at)), color = Muted, fontSize = 12.sp, modifier = Modifier.width(62.dp).padding(top = 2.dp))
                        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.tag, color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(e.msg, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        containerColor = Surface1,
        title = { Text("Clear the activity log?") },
        text = { Text("This deletes all ${all.size} entries from this phone. It doesn't affect any SOS.", color = Muted) },
        confirmButton = { TextButton(onClick = { AppLog.clear(); confirmClear = false }) { Text("Clear log", color = SosRed) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep", color = Muted) } },
    )
}

// ---------------------------------------------------------------- Shared bits

@Composable
internal fun Field(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType = KeyboardType.Text, secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Lilac, unfocusedBorderColor = Line, focusedLabelColor = Lilac, unfocusedLabelColor = Muted,
            cursorColor = Lilac, focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
        ),
    )
}

@Composable
internal fun PrimaryButton(text: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Lilac, contentColor = OnLilac, disabledContainerColor = Surface2, disabledContentColor = Muted),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = OnLilac, strokeWidth = 2.dp) else Text(text)
    }
}

@Composable
internal fun Note(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text(text, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
