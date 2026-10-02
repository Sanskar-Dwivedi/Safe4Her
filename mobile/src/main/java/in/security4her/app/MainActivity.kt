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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
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
        `in`.security4her.app.beta.Beta.init(this)
        `in`.security4her.app.sos.Evidence.backfill(this) // older recordings get their Downloads copy too
        `in`.security4her.app.core.Community.init(this)
        Backend.init(this)
        SosEngine.init(this)
        `in`.security4her.app.sos.Journey.init(this)
        fun has(p: String) = if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) "on" else "off"
        AppLog.info(
            "App",
            "Opened · ${if (Backend.mock) "offline mode" else "server mode"} · location ${has(Manifest.permission.ACCESS_FINE_LOCATION)}, " +
                "SMS ${has(Manifest.permission.SEND_SMS)}, calls ${has(Manifest.permission.CALL_PHONE)}",
        )
    }
}

// Colour tokens live in Theme.kt and follow the palette chosen in Me → Colours.

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun instrument(w: Int) = Font(R.font.instrument_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
internal val Instrument = FontFamily(instrument(400), instrument(500), instrument(600), instrument(700))

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun baloo(w: Int) = Font(R.font.baloo2, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
/** Rounded display face for headings and the SOS button; same family as the install site. */
internal val Baloo = FontFamily(baloo(600), baloo(700), baloo(800))

@Composable
internal fun S4hTheme(content: @Composable () -> Unit) {
    val scheme = (if (Palettes.current.value.light) lightColorScheme(
        primary = Lilac, onPrimary = OnLilac, secondary = Lilac, onSecondary = OnLilac,
        background = Night, onBackground = Ink, surface = Surface1, onSurface = Ink,
        surfaceVariant = Surface2, onSurfaceVariant = Muted, surfaceContainer = Surface1,
        surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface2, surfaceContainerLow = Surface1,
        outline = Line, outlineVariant = Line, error = SosRed, onError = Color.White,
    ) else darkColorScheme(
        primary = Lilac, onPrimary = OnLilac, secondary = Lilac, onSecondary = OnLilac,
        background = Night, onBackground = Ink, surface = Surface1, onSurface = Ink,
        surfaceVariant = Surface2, onSurfaceVariant = Muted, surfaceContainer = Surface1,
        surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface2, surfaceContainerLow = Surface1,
        outline = Line, outlineVariant = Line, error = SosRed, onError = Color.White,
    ))
    val f = Instrument
    val base = Typography()
    val type = base.copy(
        displaySmall = TextStyle(fontFamily = Baloo, fontSize = 40.sp, lineHeight = 42.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineMedium = TextStyle(fontFamily = Baloo, fontSize = 32.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
        headlineSmall = TextStyle(fontFamily = Baloo, fontSize = 27.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.2).sp),
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
        Palettes.load(this)
        setContent {
            val light = Palettes.current.value.light
            LaunchedEffect(light) {
                val t = android.graphics.Color.TRANSPARENT
                if (light) enableEdgeToEdge(SystemBarStyle.light(t, t), SystemBarStyle.light(t, t))
                else enableEdgeToEdge(SystemBarStyle.dark(t), SystemBarStyle.dark(t))
            }
            S4hTheme { Root() }
        }
        handleEvidence(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEvidence(intent)
    }

    override fun onResume() {
        super.onResume()
        // The app is on screen now, so Android allows the microphone and camera: start anything the SOS is missing.
        if (SosEngine.current.value?.active != true) return
        val c = `in`.security4her.app.sos.Evidence.active.value
        val missing = (`in`.security4her.app.sos.Evidence.wantsAudio(this) && c?.audioOn != true) ||
            (`in`.security4her.app.sos.Evidence.wantsVideo(this) && c?.videoOn != true)
        if (missing) runCatching { ContextCompat.startForegroundService(this, Intent(this, `in`.security4her.app.sos.SosService::class.java)) }
    }

    private fun handleEvidence(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_EVIDENCE, false) != true) return
        `in`.security4her.app.sos.Evidence.clearAsk(this)
        AppLog.info("Evidence", "App opened from the SOS prompt; starting the microphone and camera")
    }

    companion object {
        const val EXTRA_START_EVIDENCE = "in.security4her.app.START_EVIDENCE"
    }
}

private enum class Stage { Loading, Auth, Verify, Onboard, Main }
private enum class Tab(val label: String, val icon: ImageVector? = null, val res: Int = 0) {
    Home("Home", Icons.Filled.Home), Map("Map", Icons.Filled.Place),
    People("People", Icons.Filled.Person), Me("Me", Icons.Filled.AccountCircle),
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<Profile?>(null) }
    var stage by remember { mutableStateOf(Stage.Loading) }
    val incident by SosEngine.current.collectAsStateWithLifecycle()
    val pocket by `in`.security4her.app.sos.PocketSos.countdown.collectAsStateWithLifecycle()
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
    var betaPending by remember { mutableStateOf(`in`.security4her.app.beta.Beta.needsDecision) }

    Box(Modifier.fillMaxSize().background(Night).systemBarsPadding().imePadding()) {
        val inc = incident
        when {
            pocket != null && stage == Stage.Main -> PocketCountdownScreen(pocket!!)
            inc != null && stage == Stage.Main -> ActiveSosScreen(inc)
            journey?.status == "checking" && stage == Stage.Main -> CheckInScreen(journey!!)
            betaPending && stage != Stage.Loading -> `in`.security4her.app.beta.BetaConsentScreen(onDone = { betaPending = false })
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
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.16f))
            .background(Cream),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.logo_security4her),
            contentDescription = "Security4Her",
            modifier = Modifier.fillMaxSize(),
        )
    }
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

    var welcome by rememberSaveable { mutableStateOf(true) }
    if (welcome) {
        WelcomeScreen(onStart = { creating = true; welcome = false }, onSignIn = { creating = false; welcome = false })
        return
    }
    BackHandler { welcome = true }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Back") { welcome = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = Muted)
        }
        Spacer(Modifier.height(12.dp))
        Text(if (creating) "Create your account" else "Welcome back", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            if (creating) "It takes about a minute." else "Sign in to pick up where you left off.",
            color = Muted, style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(28.dp))
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
    }
}

// ---------------------------------------------------------------- Verify

@Composable
private fun VerifyScreen(profile: Profile, onDone: (Profile) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var digilocker by remember { mutableStateOf(false) }
    if (digilocker) {
        DigiLockerDemoScreen(onCancel = { digilocker = false }) { acct ->
            busy = true
            scope.launch {
                try {
                    // MOCK: demo DigiLocker said female; record it (real flow: digilocker-start → callback, CONTRACT.md §3)
                    if (Backend.mockVerifyFemale("digilocker")) {
                        AppLog.ok("Account", "Verified as a woman through DigiLocker (${acct.name})")
                        onDone(Backend.profile())
                    } else error = "Verification didn't go through. Try again."
                } catch (e: Exception) {
                    error = e.friendly()
                    AppLog.warn("Account", error!!, e)
                } finally {
                    busy = false
                    digilocker = false
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp)) {
        Text("One quick check", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Tip(
            "Security4Her is only for women, which keeps the map and alerts trustworthy. " +
                "DigiLocker confirms it once. We keep the result, never your documents.",
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
        PrimaryButton("Verify with DigiLocker", busy = busy) { error = null; digilocker = true }
        Spacer(Modifier.height(10.dp))
        Text("Only your name and gender are shared. No documents.", color = Muted, style = MaterialTheme.typography.bodySmall,
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
        add(Perm("mic", "Microphone", "Records audio evidence from the moment an SOS starts.", { has(Manifest.permission.RECORD_AUDIO) }) {
            request("mic", Manifest.permission.RECORD_AUDIO)
        })
        add(Perm("cam", "Camera", "Records video evidence during an SOS.", { has(Manifest.permission.CAMERA) }) {
            request("cam", Manifest.permission.CAMERA)
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

    var idx by rememberSaveable {
        mutableIntStateOf(if (SmsUnlock.pending(ctx)) perms.indexOfFirst { it.key == "sms" }.coerceAtLeast(0) else 0)
    }
    val p = perms[idx.coerceAtMost(perms.lastIndex)]
    val ok = remember(tick, idx) { p.granted(ctx) }
    val blocked = !ok && p.key in asked && p.key != "battery"
    // Android 15 refuses SMS to downloaded apps outright, so offer the fix straight away instead of a doomed request
    val smsCard = !ok && p.key == "sms" && (blocked || remember(tick) { SmsUnlock.blocked(ctx) })
    fun next() { if (idx < perms.lastIndex) idx++ else onDone() }
    // Granted from the system dialog: celebrate briefly, then move on
    LaunchedEffect(tick, idx) { if (p.key in asked && p.granted(ctx)) { delay(700); next() } }
    BackHandler(idx > 0) { idx-- }

    Column(Modifier.fillMaxSize()) {
        LessonBar((idx + 1) / perms.size.toFloat(), onClose = onDone)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Step ${idx + 1} of ${perms.size}", color = Lilac, style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(36.dp))
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(Modifier.size(150.dp).clip(CircleShape).background(Lilac.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(permIcon(p.key), null, tint = Lilac, modifier = Modifier.size(70.dp))
                }
                androidx.compose.animation.AnimatedVisibility(ok, enter = androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.4f))) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Mint).border(4.dp, Night, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, "Allowed", tint = OnMint)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(p.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(p.why, color = Muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            if (smsCard) {
                Spacer(Modifier.height(18.dp))
                SmsAccessCard(onGranted = { tick++ })
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            when {
                ok -> ChunkyButton(if (idx == perms.lastIndex) "All done" else "Continue", tone = Tone.Good) { next() }
                smsCard -> {}
                blocked -> ChunkyButton("Open settings") {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }
                else -> ChunkyButton("Allow") { p.ask() }
            }
            if (!ok) TextButton(onClick = { next() }, modifier = Modifier.fillMaxWidth()) { Text("Not now", color = Muted) }
        }
    }
}

/**
 * Gets SMS allowed. Android 15 refuses it to apps installed from a download ("App was denied access"), so the first
 * choice is the one-tap fix (the app reinstalls itself the way an updater would, which lifts the block); the manual
 * route through Settings is below it, in the order Android actually requires.
 */
@Composable
internal fun SmsAccessCard(onGranted: () -> Unit = {}) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++; asked = true }
    val granted = remember(tick) { Sms.allowed(ctx) }
    val blocked = remember(tick) { SmsUnlock.blocked(ctx) }
    val needsInstall = remember(tick) { SmsUnlock.needsInstallPermission(ctx) }
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(granted) {
        if (granted) { SmsUnlock.pending(ctx); onGranted() }
    }
    fun openSettings() = runCatching {
        ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).padding(16.dp)) {
        when {
            granted -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = Mint)
                Spacer(Modifier.width(10.dp))
                Text("Texting is allowed. Every SOS text goes out by itself.", color = Mint, style = MaterialTheme.typography.titleSmall)
            }
            blocked -> {
                Text("Android is blocking texting for this app", style = MaterialTheme.typography.titleMedium, color = Amber)
                Spacer(Modifier.height(4.dp))
                Text(
                    "It does this to every app installed from a download, and shows \"App was denied access\". Security4Her can fix it by " +
                        "reinstalling itself the way an app store does. Nothing is deleted.",
                    color = Muted, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                if (needsInstall) {
                    StepLine(1, "Allow installs from Security4Her: turn on Allow from this source, then come back.", active = true)
                    StepLine(2, "Tap Fix texting, then Update when Android asks.", active = false)
                    Spacer(Modifier.height(10.dp))
                    ChunkyButton("Allow installs") { SmsUnlock.start(ctx) }
                } else {
                    StepLine(1, "Tap Fix texting, then Update when Android asks. The app restarts.", active = true)
                    StepLine(2, "Open Security4Her again and tap Allow.", active = false)
                    Spacer(Modifier.height(10.dp))
                    ChunkyButton("Fix texting", tone = Tone.Danger) { SmsUnlock.start(ctx) }
                }
            }
            else -> {
                Text("Allow texting", style = MaterialTheme.typography.titleMedium)
                Text("So an SOS texts your contacts by itself, with no tap needed.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                ChunkyButton("Allow") { ask.launch(Manifest.permission.SEND_SMS) }
                if (asked) {
                    Spacer(Modifier.height(8.dp))
                    Text("Android didn't allow it. Use the steps below.", color = Amber, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!granted) {
            TextButton(onClick = { manual = !manual }, contentPadding = PaddingValues(0.dp)) {
                Text(if (manual) "Hide the steps in Settings" else "Or do it in Settings", color = Lilac)
            }
            if (manual) {
                listOf(
                    "Tap Open settings. Go to Permissions, then SMS, then Allow. Android says the app was denied access: tap OK. This unlocks the next step.",
                    "Leave Settings, then tap Open settings again. The menu only appears on a fresh App info page.",
                    "Tap ⋮ at the top right, then Allow restricted settings, and confirm with your PIN or fingerprint.",
                    "Go to Permissions, then SMS, and choose Allow.",
                ).forEachIndexed { i, t -> StepLine(i + 1, t, active = true) }
                Spacer(Modifier.height(8.dp))
                ChunkyButton("Open settings", tone = Tone.Plain) { openSettings() }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Until then, an SOS opens Messages with your text and contacts ready, so it's one tap to send.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun StepLine(n: Int, text: String, active: Boolean) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("$n", color = if (active) Lilac else Muted, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (active) Ink else Muted)
    }
}

@Composable
private fun permIcon(key: String): ImageVector = when (key) {
    "loc", "bg" -> Icons.Filled.LocationOn
    "sms" -> Icons.Filled.Email
    "sim" -> Icons.Filled.Phone
    "call" -> Icons.Filled.Call
    "mic" -> ImageVector.vectorResource(R.drawable.ic_mic)
    "cam" -> ImageVector.vectorResource(R.drawable.ic_cam)
    "notif" -> Icons.Filled.Notifications
    else -> ImageVector.vectorResource(R.drawable.ic_battery)
}

@Composable
private fun WelcomeScreen(onStart: () -> Unit, onSignIn: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        BrandMark(128.dp)
        Spacer(Modifier.height(28.dp))
        Text("Security4Her", color = Lilac, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your safety, one press away. Free, and made for women.",
            color = Muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        ChunkyButton("Get started", onClick = onStart)
        Spacer(Modifier.height(12.dp))
        ChunkyButton("I already have an account", tone = Tone.Plain, onClick = onSignIn)
    }
}

// ---------------------------------------------------------------- Main shell

@Composable
private fun MainScreen(profile: Profile, onSignedOut: () -> Unit, onProfile: (Profile) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var settings by remember { mutableStateOf(Settings.load(ctx)) }
    var contacts by remember { mutableStateOf<List<Contact>?>(null) }
    var contactsError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var watch by remember { mutableStateOf(false) }
    var resumed by remember { mutableIntStateOf(0) }
    val tick by Progress.tick.collectAsStateWithLifecycle()
    val journey by `in`.security4her.app.sos.Journey.state.collectAsStateWithLifecycle()

    LaunchedEffect(reload) {
        try {
            contacts = Backend.contacts()
            contactsError = null
        } catch (e: Exception) {
            contactsError = e.friendly()
            if (contacts == null) contacts = emptyList()
        }
    }
    LaunchedEffect(Unit) {
        while (true) { watch = WearBridge.watchReachable(ctx); delay(10_000) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumed++; Updater.check(ctx)
        `in`.security4her.app.sos.PocketSos.ensureRunning(ctx)
    }
    LaunchedEffect(journey) { if (journey != null) Progress.mark(ctx, "journey") }
    val alerted = contacts?.filter { it.receivesSos }.orEmpty()
    val facts = remember(contacts, settings, watch, resumed, tick) {
        ReadyFacts(
            hasContact = alerted.isNotEmpty(),
            hasPerms = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && has(ctx, Manifest.permission.SEND_SMS),
            hasPin = `in`.security4her.app.core.Pin.isSet(ctx), watch = watch,
        )
    }

    var showLog by rememberSaveable { mutableStateOf(false) }
    var showLocker by rememberSaveable { mutableStateOf(false) }
    var showJourney by rememberSaveable { mutableStateOf(false) }
    var perms by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var showChat by rememberSaveable { mutableStateOf(false) }
    var showPractice by rememberSaveable { mutableStateOf(false) }
    var showWatchInstall by rememberSaveable { mutableStateOf(false) }
    var showBetaConsent by rememberSaveable { mutableStateOf(false) }
    var showBetaReport by rememberSaveable { mutableStateOf(false) }
    var showFakeCall by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(!Guide.seen(ctx)) } // opens by itself once, after sign-up
    // Back after reinstalling to unlock texting: bring the SMS card straight up
    LaunchedEffect(Unit) { if (SmsUnlock.pending(ctx)) sheet = "sms" }

    fun open(step: SetupStep) {
        when (step.key) {
            "contact" -> tab = Tab.People
            "perms" -> perms = true
            else -> sheet = step.key
        }
    }

    BackHandler(tab != Tab.Home) { tab = Tab.Home }
    when {
        showLog -> { LogScreen { showLog = false }; return }
        showLocker -> { EvidenceLockerScreen(onClose = { showLocker = false }); return }
        showJourney -> { BackHandler { showJourney = false }; JourneySetupScreen(onClose = { showJourney = false }); return }
        perms -> { PermissionsScreen(onDone = { perms = false; resumed++ }); return }
        showChat -> { ChatScreen(onClose = { showChat = false }); return }
        showPractice -> { PocketPracticeScreen(onClose = { showPractice = false }); return }
        showWatchInstall -> { WatchInstallScreen(onClose = { showWatchInstall = false }); return }
        showBetaConsent -> { `in`.security4her.app.beta.BetaConsentScreen(onDone = { showBetaConsent = false }, editing = true); return }
        showBetaReport -> { `in`.security4her.app.beta.BetaReportScreen(onClose = { showBetaReport = false }); return }
        showFakeCall -> { FakeCallScreen(onClose = { showFakeCall = false }); return }
        showGuide -> {
            SetupGuideScreen(
                contacts, contactsError, onContactsChanged = { reload++ },
                settings, onSettings = { settings = it; it.save(ctx) },
                onPractice = { showPractice = true }, onWatchInstall = { showWatchInstall = true },
                onClose = { showGuide = false; resumed++ },
            ); return
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                androidx.compose.animation.AnimatedContent(
                    tab, label = "tab",
                    transitionSpec = {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (androidx.compose.animation.slideInHorizontally(tween(260)) { it / 6 * dir } + androidx.compose.animation.fadeIn(tween(260))) togetherWith
                            androidx.compose.animation.fadeOut(tween(120))
                    },
                ) { t ->
                    when (t) {
                        Tab.Home -> HomeScreen(
                            profile, contacts, settings, facts, watch, onTab = { tab = it }, onJourney = { showJourney = true },
                            onStep = ::open, onChat = { showChat = true }, onFixSms = { sheet = "sms" },
                            onFakeCall = { showFakeCall = true }, onGuide = { showGuide = true },
                        )
                        Tab.Map -> SafeMapScreen()
                        Tab.People -> ContactsScreen(contacts, contactsError) { reload++ }
                        Tab.Me -> SettingsScreen(
                            profile, settings,
                            onChange = { settings = it }, onSignedOut = onSignedOut, onOpenLog = { showLog = true }, onOpenLocker = { showLocker = true },
                            onOpenChat = { showChat = true }, onPractice = { showPractice = true }, onWatchInstall = { showWatchInstall = true },
                            onBetaConsent = { showBetaConsent = true }, onBetaReport = { showBetaReport = true },
                            onGuide = { showGuide = true },
                        )
                    }
                }
            }
            TabBar(tab) { tab = it }
        }
        sheet?.let { key ->
            ActionSheet(onDismiss = { sheet = null }) {
                when (key) {
                    "pin" -> PinStep(settings) { settings = it; it.save(ctx); sheet = null }
                    "test_text" -> TestTextStep(settings, onChange = { settings = it; it.save(ctx) }) { sheet = null }
                    "watch" -> WatchStep(watch) { sheet = null }
                    "sms" -> {
                        SmsAccessCard(onGranted = { resumed++ })
                        TextButton(onClick = { sheet = null }, modifier = Modifier.fillMaxWidth()) { Text(if (Sms.allowed(ctx)) "Done" else "Close", color = Muted) }
                    }
                }
            }
        }
    }
}

/** Scrim plus a card that slides up from the bottom. */
@Composable
private fun ActionSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss,
        ))
        androidx.compose.animation.AnimatedVisibility(
            shown, modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically(tween(300)) { it },
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Surface1)
                    .padding(horizontal = 22.dp, vertical = 22.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun PinStep(settings: Settings, onSave: (Settings) -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf(settings.pin) }
    Text("Set a safety PIN", style = MaterialTheme.typography.headlineSmall)
    Text("Pick 4 digits only you know. They're needed before an SOS can be switched off.", color = Muted, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
        repeat(4) { i ->
            val filled = i < pin.length
            Box(
                Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(if (filled) Lilac.copy(alpha = 0.16f) else Surface2)
                    .border(2.dp, if (i == pin.length) Lilac else if (filled) Lilac.copy(alpha = 0.5f) else Line, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) { if (filled) Box(Modifier.size(14.dp).clip(CircleShape).background(Lilac)) }
        }
    }
    Spacer(Modifier.height(18.dp))
    // Big chunky keypad
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
    keys.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { k ->
                if (k.isEmpty()) Spacer(Modifier.weight(1f).height(56.dp))
                else Chunky(
                    onClick = { pin = if (k == "⌫") pin.dropLast(1) else (pin + k).take(4) },
                    modifier = Modifier.weight(1f).height(58.dp), tone = Tone.Plain, depth = 3.dp,
                ) { Text(k, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    ChunkyButton("Save PIN", enabled = pin.length == 4) {
        AppLog.ok("Settings", "Safety PIN set")
        Toast.makeText(ctx, "PIN saved", Toast.LENGTH_SHORT).show()
        onSave(settings.copy(pin = pin))
    }
}

@Composable
internal fun TestTextStep(settings: Settings, onChange: (Settings) -> Unit, doneLabel: String = "Close", onDone: () -> Unit) {
    val ctx = LocalContext.current
    val status by Sms.testStatus.collectAsStateWithLifecycle()
    Text("Send a test text", style = MaterialTheme.typography.headlineSmall)
    Text("One practice text to a friend's phone. No SOS starts; it just proves texts work from here.", color = Muted, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
    Field(settings.demoNumber, { onChange(settings.copy(demoNumber = it)) }, "Friend's number", KeyboardType.Phone)
    status?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = when { it.startsWith("Failed") -> SosRed; it.startsWith("Delivered") || it.startsWith("Sent") -> Mint; else -> Muted },
            style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(16.dp))
    val canSms = Sms.allowed(ctx)
    if (!canSms) { Spacer(Modifier.height(4.dp)); SmsAccessCard(); Spacer(Modifier.height(12.dp)) }
    ChunkyButton(if (canSms) "Send test text" else "Send it from Messages", enabled = settings.demoNumber.count(Char::isDigit) >= 3) {
        if (canSms) Sms.sendTest(ctx, settings.demoNumber)
        else Sms.compose(ctx, listOf(settings.demoNumber), "Security4Her test: this phone can text you in an SOS. No action needed.")
        Progress.mark(ctx, "test_text")
    }
    TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(doneLabel, color = Muted) }
}

@Composable
private fun WatchStep(linked: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    Text(if (linked) "Your watch is linked" else "Link your watch", style = MaterialTheme.typography.headlineSmall)
    Text(
        if (linked) "Press and hold SOS on it any time, even with your phone in a bag."
        else "Install Security4Her on your Wear OS watch and open it. It links to this phone on its own.",
        color = Muted, style = MaterialTheme.typography.bodyMedium,
    )
    msg?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Muted, style = MaterialTheme.typography.bodyMedium) }
    Spacer(Modifier.height(16.dp))
    if (linked) ChunkyButton("Great", tone = Tone.Good) { Progress.mark(ctx, "watch"); onDone() }
    else {
        ChunkyButton("Check for my watch") {
            msg = "Looking for your watch…"
            scope.launch {
                WearBridge.linkWatch(ctx)
                val ok = WearBridge.watchReachable(ctx)
                msg = if (ok) "Found it! Your watch is linked." else "No watch nearby yet. Open the app on the watch and try again."
                if (ok) Progress.mark(ctx, "watch")
            }
        }
        TextButton(onClick = { Progress.mark(ctx, "watch"); onDone() }, modifier = Modifier.fillMaxWidth()) { Text("I don't have a watch", color = Muted) }
    }
}

@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(28.dp)).background(Surface1)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            val sel = t == tab
            val bg by androidx.compose.animation.animateColorAsState(if (sel) Lilac else Color.Transparent, tween(260), label = "tabBg")
            val fg by androidx.compose.animation.animateColorAsState(if (sel) OnLilac else Muted, tween(260), label = "tabFg")
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(bg)
                    .clickable(onClickLabel = t.label) { if (!sel) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTab(t) }
                    .animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 500f))
                    .padding(horizontal = if (sel) 18.dp else 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon ?: ImageVector.vectorResource(t.res), contentDescription = if (sel) null else t.label, tint = fg, modifier = Modifier.size(22.dp))
                if (sel) {
                    Spacer(Modifier.width(8.dp))
                    Text(t.label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
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

private fun tipFor(s: SetupStep) = when (s.key) {
    "contact" -> "Your SOS needs someone to reach. Add a trusted contact."
    "perms" -> "Allow location and texts so your SOS can say where you are."
    "pin" -> "Set a PIN so only you can switch an SOS off."
    "test_text" -> "Send one test text to check this phone can reach people."
    "watch" -> "Got a Wear OS watch? Link it for SOS from your wrist."
    else -> s.title
}

@Composable
private fun HomeScreen(
    profile: Profile, contacts: List<Contact>?, settings: Settings, facts: ReadyFacts, watch: Boolean,
    onTab: (Tab) -> Unit, onJourney: () -> Unit, onStep: (SetupStep) -> Unit, onChat: () -> Unit, onFixSms: () -> Unit,
    onFakeCall: () -> Unit, onGuide: () -> Unit,
) {
    val ctx = LocalContext.current
    val tick by Progress.tick.collectAsStateWithLifecycle()
    var locOn by remember { mutableStateOf(has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) }
    var smsOn by remember { mutableStateOf(Sms.allowed(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { locOn = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION); smsOn = Sms.allowed(ctx) }
    val alerted = contacts?.filter { it.receivesSos }.orEmpty()
    val name = profile.displayName ?: "there"
    val next = remember(tick, facts) { nextStep(ctx, facts) }
    val journey by `in`.security4her.app.sos.Journey.state.collectAsStateWithLifecycle()
    val live by LiveShare.session.collectAsStateWithLifecycle()
    fun sendLink(url: String) = ShareSheet.text(ctx, "Follow my live location: $url (updates every 20 s). $name, via Security4Her", "Send live link")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(greeting(), color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (watch) Row(
                Modifier.clip(RoundedCornerShape(20.dp)).background(Mint.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ImageVector.vectorResource(R.drawable.ic_watch), null, tint = Mint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Watch linked", color = Mint, style = MaterialTheme.typography.labelMedium)
            }
            if (`in`.security4her.app.sos.PocketSos.enabled(ctx)) Text(
                "Pocket SOS", color = Lilac, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(20.dp)).background(Lilac.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(Surface1).clickable(onClickLabel = "Ask Security4Her", onClick = onChat),
                contentAlignment = Alignment.Center,
            ) { Icon(ImageVector.vectorResource(R.drawable.ic_chat), "Ask Security4Her", tint = Ink, modifier = Modifier.size(22.dp)) }
        }
        if (!locOn) {
            Spacer(Modifier.height(10.dp))
            Chunky(
                onClick = { runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) } },
                modifier = Modifier.fillMaxWidth(), face = Amber.copy(alpha = 0.14f).compositeOver(Night), edge = Amber.copy(alpha = 0.4f).compositeOver(Night), outline = Amber.copy(alpha = 0.5f), depth = 3.dp,
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocationOn, null, tint = Amber)
                    Spacer(Modifier.width(10.dp))
                    Text("Location is off, so an SOS can't say where you are. Tap to allow it.", color = Amber, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        UpdateCard(Modifier.padding(top = 12.dp))
        if (!smsOn) {
            // One-time setup, never during an emergency: once SMS is allowed every SOS text goes out by itself.
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(SosRed.copy(alpha = 0.14f).compositeOver(Night)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SOS texts aren't automatic yet", color = SosRed, style = MaterialTheme.typography.titleMedium)
                    Text("Allow texting once so an SOS reaches people by itself.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(12.dp))
                Chunky(onClick = onFixSms, tone = Tone.Danger, depth = 3.dp, shape = RoundedCornerShape(14.dp)) {
                    Text("Fix", color = Color.White, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
                }
            }
        }
        if (!Guide.done(ctx)) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1).clickable(onClickLabel = "Open the setup guide", onClick = onGuide).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
                    Text("A 3-minute guide that checks everything an SOS needs.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("›", color = Lilac, fontSize = 26.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (next != null && !(next.key == "perms" && !smsOn && locOn)) Tip(tipFor(next)) {
            Spacer(Modifier.height(12.dp))
            Chunky(onClick = { onStep(next) }, depth = 3.dp, shape = RoundedCornerShape(14.dp)) {
                Text(next.title, color = OnLilac, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SosHoldButton { SosEngine.start(ctx, trigger = "button", source = "phone") }
        }
        Text(
            "Hold for 1.5 seconds. Your location and a live link go out by text, then a call follows after ${settings.countdownSec} s.",
            color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(20.dp))
        if (journey?.running == true) { JourneyHomeCard(onStart = onJourney); Spacer(Modifier.height(12.dp)) }
        live?.let { s ->
            LiveCard(s, onSend = { sendLink(s.url) }, onStop = { LiveShare.stop(ctx) })
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickTile(Modifier.weight(1f), Icons.Filled.Share, if (live == null) "Live location" else "Send link", "Share where you are", Mint) {
                if (!locOn) {
                    Toast.makeText(ctx, "Allow location first.", Toast.LENGTH_SHORT).show()
                    return@QuickTile
                }
                if (!Locator.enabled(ctx)) {
                    Toast.makeText(ctx, "Turn on location in quick settings first.", Toast.LENGTH_LONG).show()
                    runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    return@QuickTile
                }
                val s = live ?: LiveShare.start(ctx)
                sendLink(s.url)
            }
            QuickTile(Modifier.weight(1f), Icons.Filled.Place, "Safe Journey", "I'll check you arrive", Lilac, onClick = onJourney)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickTile(Modifier.weight(1f), Icons.Filled.Call, "Call 100", "Police", SosRed) {
                ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:100")))
            }
            QuickTile(Modifier.weight(1f), ImageVector.vectorResource(R.drawable.ic_shield), "Safe places", "Nearby and open", Lilac) { onTab(Tab.Map) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickTile(Modifier.weight(1f), Icons.Filled.Phone, "Fake call", "A call to step away", Mint, onClick = onFakeCall)
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surface1).padding(18.dp)) {
            Text("Who hears from you", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            when {
                contacts == null -> Text("Loading contacts", color = Muted)
                alerted.isEmpty() -> {
                    Text("No one yet. Add at least one trusted contact so your SOS reaches someone.", color = Muted)
                    Spacer(Modifier.height(14.dp))
                    ChunkyButton("Add a contact", tone = Tone.Plain, icon = Icons.Filled.Person) { onTab(Tab.People) }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                        alerted.take(5).forEach { Avatar(it.name, 42.dp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    val police = if (settings.testMode) settings.demoNumber.ifBlank { null }?.let { "your test number" } else "police ${settings.policeNumber}"
                    Text(
                        alerted.joinToString(", ") { it.name } + (police?.let { ", and $it" } ?: ""),
                        color = Muted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun QuickTile(modifier: Modifier, icon: ImageVector, label: String, sub: String, tint: Color, onClick: () -> Unit) {
    Chunky(onClick = onClick, modifier = modifier, tone = Tone.Plain, depth = 3.dp, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(14.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(sub, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Button(onClick = onSend, colors = ButtonDefaults.buttonColors(containerColor = LiveGreen, contentColor = OnMint)) { Text("Send link", fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = onStop, border = androidx.compose.foundation.BorderStroke(1.dp, SosRed)) { Text("Stop sharing", color = SosRed) }
        }
    }
}

@Composable
internal fun SosHoldButton(practice: Boolean = false, onFire: () -> Unit) {
    val base = if (practice) Lilac else SosRed
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var holding by remember { mutableStateOf(false) }
    val press by androidx.compose.animation.core.animateFloatAsState(
        if (holding) 0.94f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 400f), label = "press",
    )
    val radar = rememberInfiniteTransition(label = "radar")
    val wave by radar.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "wave")
    val disc = androidx.compose.ui.graphics.Brush.radialGradient(
        0f to (if (practice) Color(0xFFF0ABFC) else Color(0xFFFF8A94)), 0.55f to base, 1f to (if (practice) LilacDeep else Color(0xFFD42A45)),
    )
    Box(
        Modifier.size(280.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                holding = true
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val job = scope.launch {
                    launch {
                        delay(500); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        delay(500); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
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
            val w = 8.dp.toPx()
            val r = size.minDimension / 2
            val inner = 98.dp.toPx()
            // Two radar ripples leave the button; they pause while she holds so the progress ring reads clearly
            if (!holding && progress.value == 0f) for (k in 0..1) {
                val t = (wave + k * 0.5f) % 1f
                drawCircle(base.copy(alpha = 0.28f * (1f - t)), radius = inner + (r - w - inner) * t, style = Stroke(2.dp.toPx()))
            }
            drawCircle(base.copy(alpha = 0.16f), radius = r - w / 2, style = Stroke(w))
            drawArc(
                Color.White, -90f, 360f * progress.value, false,
                topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round),
            )
        }
        Box(
            Modifier.size(196.dp).graphicsLayer { scaleX = press; scaleY = press }
                .sosGlow(base).clip(CircleShape).background(disc)
                .border(2.dp, Color.White.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SOS", color = if (practice) OnLilac else Color.White, fontSize = 66.sp, lineHeight = 62.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, fontFamily = Baloo)
                Text(
                    if (holding) "Keep holding" else if (practice) "Hold to practise" else "Hold to send",
                    color = (if (practice) OnLilac else Color.White).copy(alpha = 0.92f), style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** Soft red glow under the SOS disc. */
private fun Modifier.sosGlow(c0: Color) = this.drawBehind {
    val c = center.copy(y = center.y + 12.dp.toPx())
    val rr = size.minDimension * 0.78f
    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(c0.copy(alpha = 0.5f), Color.Transparent), center = c, radius = rr), radius = rr, center = c)
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
internal fun ContactsScreen(contacts: List<Contact>?, error: String?, onChanged: () -> Unit) {
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
                Text("Your people", style = MaterialTheme.typography.headlineMedium)
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
                Box(Modifier.size(96.dp).clip(CircleShape).background(Lilac.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Person, null, tint = Lilac, modifier = Modifier.size(44.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text("Who should hear from you first?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
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
private fun SettingsScreen(profile: Profile, settings: Settings, onChange: (Settings) -> Unit, onSignedOut: () -> Unit, onOpenLog: () -> Unit, onOpenLocker: () -> Unit, onOpenChat: () -> Unit, onPractice: () -> Unit, onWatchInstall: () -> Unit,
    onBetaConsent: () -> Unit = {}, onBetaReport: () -> Unit = {}, onGuide: () -> Unit = {}) {
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

    val openSection = rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Avatar(profile.displayName ?: "?", 56.dp)
                Box(Modifier.size(22.dp).clip(CircleShape).background(Mint).border(2.dp, Night, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, "Verified", tint = OnMint, modifier = Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(profile.displayName ?: "You", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (profile.verificationMethod == "digilocker") "Verified woman, via DigiLocker" else "Verified", color = Lilac, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Ask Security4Her", ImageVector.vectorResource(R.drawable.ic_chat), onOpenChat, Modifier.weight(1f))
            Tile("Setup guide", Icons.Filled.Check, onGuide, Modifier.weight(1f))
        }
        CompositionLocalProvider(LocalOpenSection provides openSection) {
        GroupLabel("Safety")
        Section(
            "Pocket SOS",
            if (`in`.security4her.app.sos.PocketSos.enabled(ctx)) "On: knock 3 times with the screen off" else "Off: knock 3 times to start an SOS",
        ) {
            var pocketOn by remember { mutableStateOf(`in`.security4her.app.sos.PocketSos.enabled(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Knock 3 times to start SOS")
                    Text(
                        "With the screen off, knock firmly 3 times through your pocket or bag. A long buzz means it's armed: you then have 5 seconds to cancel.",
                        color = Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = pocketOn,
                    onCheckedChange = {
                        pocketOn = it
                        `in`.security4her.app.sos.PocketSos.setEnabled(ctx, it)
                        if (it && !`in`.security4her.app.sos.PocketSos.calibrated(ctx)) onPractice() // first time: tune it to her knock
                        if (it && !`in`.security4her.app.sos.PocketSos.canShowOverLockScreen(ctx)) `in`.security4her.app.sos.PocketSos.askForLockScreenAlerts(ctx)
                    },
                    colors = switchColors,
                )
            }
            var lockOk by remember { mutableStateOf(`in`.security4her.app.sos.PocketSos.canShowOverLockScreen(ctx)) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { lockOk = `in`.security4her.app.sos.PocketSos.canShowOverLockScreen(ctx) }
            if (pocketOn && !lockOk) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Allow full-screen alerts so the countdown and Cancel button appear over your lock screen.",
                    color = Amber, style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { `in`.security4her.app.sos.PocketSos.askForLockScreenAlerts(ctx) }) { Text("Allow full-screen alerts", color = Lilac) }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onPractice, border = androidx.compose.foundation.BorderStroke(1.dp, Lilac)) {
                Text("Practise and adjust strength", color = Lilac)
            }
            Spacer(Modifier.height(6.dp))
            ScreenOffTestCard()
            var gemini by remember { mutableStateOf(false) }
            TextButton(onClick = { gemini = !gemini }, contentPadding = PaddingValues(0.dp)) {
                Text(if (gemini) "Hide" else "Gemini or the Assistant opens when I knock?", color = Lilac)
            }
            if (gemini) BackTapNote()
        }

        Section("Test Mode", if (settings.testMode) "On: texts and calls go to your test number" else "Off") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Send police texts and calls to a test number", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.testMode, onCheckedChange = { set(settings.copy(testMode = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Amber, checkedThumbColor = Night, uncheckedTrackColor = Surface2, uncheckedBorderColor = Line),
                )
            }
            Spacer(Modifier.height(12.dp))
            var demo by remember(settings.demoNumber) { mutableStateOf(settings.demoNumber) }
            val demoBad = demo.isNotBlank() && digits(demo) < 3
            Field(demo, { demo = it.filter { c -> c.isDigit() || c in "+ -" } }, "Test number (a friend's phone)", KeyboardType.Phone)
            if (demoBad) FieldError("Enter a full phone number.")
            SaveBar(changed = demo.trim() != settings.demoNumber, valid = !demoBad, onDiscard = { demo = settings.demoNumber }) {
                set(settings.copy(demoNumber = demo.trim()))
                AppLog.ok("Settings", "Test number saved")
            }
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
        EvidenceSection(onOpenLocker)
        SafeJourneySection()
        Section("Location updates", if (settings.smsUpdates) "Texts your location every 5 minutes in an SOS" else "Off") {
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
        Section("During SOS", "Countdown, the call and what gets recorded") { DuringSosEditor(settings) { set(it) } }
        Section("Watch", "Install, update or re-link your watch") {
            ChunkyButton("Install or update the watch app", tone = Tone.Plain, onClick = onWatchInstall)
            Spacer(Modifier.height(14.dp))
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
        GroupLabel("App")
        Section("Colours", Palettes.current.value.name) { PalettePicker() }
        Section("App updates", "Version ${Updater.versionName}") { UpdateSection() }
        Section("Beta testing", "Test data and problem reports") { `in`.security4her.app.beta.BetaSection(onEdit = onBetaConsent, onReport = onBetaReport) }
        val logCount = AppLog.entries.collectAsStateWithLifecycle().value.size
        LinkRow("Activity log", "Every SOS step, text and error ($logCount entries)", onOpenLog)
        Section("Account", profile.displayName ?: "Signed in") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(profile.displayName ?: "?", 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.displayName ?: "You", style = MaterialTheme.typography.titleMedium)
                    Text(
                                if (profile.verificationMethod == "digilocker") "Verified with DigiLocker" else "Verified",
                        color = Lilac, style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { scope.launch { runCatching { Backend.signOut() }; onSignedOut() } }) { Text("Sign out", color = SosRed) }
            }
        }
        Section("About", "Version, the team and privacy") {
            Text("Security4Her Beta ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Text("Free and open source, made for women's safety.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            Text("MADE BY", color = Lilac, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            listOf("Atharva Lakhe", "Sanskar Dwivedi", "Mohit Hadap", "Ashay Malode").forEach { name ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name, 30.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(name)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                TextButton(onClick = { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://security4her.vercel.app/"))) }) { Text("Website", color = Lilac) }
                TextButton(onClick = { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://security4her.vercel.app/privacy.html"))) }) { Text("Privacy", color = Lilac) }
                TextButton(onClick = { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://security4her.vercel.app/beta.html"))) }) { Text("Beta", color = Lilac) }
            }
        }
        }
    }
}

@Composable
private fun PalettePicker() {
    val ctx = LocalContext.current
    val now = Palettes.current.value
    Text("Pick what feels right. SOS stays red in every one.", color = Muted, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Palettes.all.forEach { pal ->
            val sel = pal == now
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .border(if (sel) 2.dp else 1.dp, if (sel) Lilac else Line, RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = "Use ${pal.name}") { Palettes.set(ctx, pal) }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // A tiny preview of the palette: background, card, accent, SOS
                Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(11.dp)).background(pal.night).padding(7.dp)) {
                    Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(5.dp)).background(pal.surface2))
                    Box(Modifier.align(Alignment.BottomStart).size(width = 26.dp, height = 12.dp).clip(RoundedCornerShape(6.dp)).background(pal.accent))
                    Box(Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(SosRed))
                }
                Spacer(Modifier.height(6.dp))
                Text(pal.name, fontSize = 11.sp, maxLines = 1, softWrap = false, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = if (sel) Lilac else Ink)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(now.blurb, color = Muted, style = MaterialTheme.typography.bodySmall)
}

private fun digits(s: String) = s.count(Char::isDigit)

/** Edits the SOS numbers, countdown and PIN as a draft; nothing changes until she taps Save. */
@Composable
private fun DuringSosEditor(settings: Settings, onSave: (Settings) -> Unit) {
    var police by remember(settings.policeNumber) { mutableStateOf(settings.policeNumber) }
    var call by remember(settings.autoCallNumber) { mutableStateOf(settings.autoCallNumber) }
    var secs by remember(settings.countdownSec) { mutableStateOf(settings.countdownSec.toString()) }
    var pin by remember { mutableStateOf("") }
    val pinCtx = LocalContext.current
    val pinSet = remember { `in`.security4her.app.core.Pin.isSet(pinCtx) }

    val policeErr = if (digits(police) < 3) "Enter the number to text, for example 100." else null
    val callErr = if (call.isNotBlank() && digits(call) < 3) "Enter a full phone number, or leave it blank." else null
    val secsN = secs.toIntOrNull()
    val secsErr = if (secsN == null || secsN !in 3..60) "Choose between 3 and 60 seconds." else null
    val pinErr = if (pin.isNotEmpty() && pin.length != 4) "A PIN needs exactly 4 digits, or leave it blank." else null
    val changed = police.trim() != settings.policeNumber || call.trim() != settings.autoCallNumber ||
        secs != settings.countdownSec.toString() || pin.isNotEmpty()
    val phone = { v: String -> v.filter { c -> c.isDigit() || c in "+ -" } }

    Field(police, { police = phone(it) }, "Police text number", KeyboardType.Phone)
    policeErr?.let { FieldError(it) }
    Spacer(Modifier.height(10.dp))
    Field(call, { call = phone(it) }, "Number to call (blank: your first contact)", KeyboardType.Phone)
    callErr?.let { FieldError(it) }
    Spacer(Modifier.height(10.dp))
    Field(secs, { secs = it.filter(Char::isDigit).take(2) }, "Seconds before the call", KeyboardType.Number)
    secsErr?.let { FieldError(it) }
    Spacer(Modifier.height(10.dp))
    Field(pin, { pin = it.filter(Char::isDigit).take(4) }, if (pinSet) "New PIN (blank keeps your current one)" else "PIN to end an SOS (4 digits, optional)", KeyboardType.NumberPassword, secret = true)
    pinErr?.let { FieldError(it) }

    SaveBar(
        changed = changed, valid = listOf(policeErr, callErr, secsErr, pinErr).all { it == null },
        onDiscard = { police = settings.policeNumber; call = settings.autoCallNumber; secs = settings.countdownSec.toString(); pin = "" },
    ) {
        onSave(settings.copy(policeNumber = police.trim(), autoCallNumber = call.trim(), countdownSec = secsN ?: settings.countdownSec, pin = pin))
        AppLog.ok("Settings", "SOS settings saved: text ${police.trim()}, call ${call.trim().ifBlank { "first contact" }}, ${secsN}s countdown, PIN ${if (pin.isNotEmpty()) "changed" else if (pinSet) "unchanged" else "off"}")
    }
}

@Composable
private fun FieldError(text: String) {
    Text(text, color = SosRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

/**
 * Save / Discard for a group of fields. Appears when something changed; after saving shows a green tick and
 * "Saved" for a moment, with a short vibration, so she knows it stuck.
 */
@Composable
private fun SaveBar(changed: Boolean, valid: Boolean, onDiscard: () -> Unit, onSave: () -> Unit) {
    val feel = LocalHapticFeedback.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var savedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(savedAt) { if (savedAt != 0L) { delay(2_500); savedAt = 0L } }
    androidx.compose.animation.AnimatedVisibility(changed || savedAt != 0L) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (changed) {
                Text("Unsaved changes", color = Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onDiscard(); focus.clearFocus() }) { Text("Discard", color = Muted) }
                Spacer(Modifier.width(6.dp))
                Chunky(
                    onClick = {
                        onSave(); focus.clearFocus(); savedAt = System.currentTimeMillis()
                        feel.performHapticFeedback(HapticFeedbackType.Confirm)
                    },
                    enabled = valid, depth = 3.dp, shape = RoundedCornerShape(14.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, null, tint = if (valid) OnLilac else Muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save", color = if (valid) OnLilac else Muted, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                }
            } else {
                Box(Modifier.size(24.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, "Saved", tint = OnMint, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Saved", color = Mint, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
internal fun Section(title: String, summary: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val shared = LocalOpenSection.current
    var own by rememberSaveable(title) { mutableStateOf(false) }
    val open = if (shared != null) shared.value == title else own
    fun toggle() { if (shared != null) shared.value = if (open) null else title else own = !own }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp)).background(Surface1).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Close $title" else "Open $title") { toggle() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (summary != null && !open) Text(summary, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = Lilac, fontSize = 24.sp, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
    }
}

/** Which settings row is open. One at a time keeps the screen short. */
internal val LocalOpenSection = androidx.compose.runtime.staticCompositionLocalOf<MutableState<String?>?> { null }

@Composable
private fun GroupLabel(text: String) =
    Text(text, color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp))

/** A settings row that opens another screen. */
@Composable
private fun LinkRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp)).background(Surface1).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = Lilac, fontSize = 24.sp)
    }
}

@Composable
private fun Tile(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Surface1).clickable(onClick = onClick).padding(14.dp)) {
        Icon(icon, null, tint = Lilac, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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
        val beat = rememberInfiniteTransition(label = "sosBeat")
        val dot by beat.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
        Column(
            Modifier.fillMaxWidth()
                .background(
                    if (inc.active) androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFFF5A69), Color(0xFFCF2744)))
                    else androidx.compose.ui.graphics.SolidColor(Surface1),
                )
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inc.active) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = dot)))
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    when (inc.state) { "active" -> "SOS active"; "resolved" -> "You're marked safe"; else -> "SOS cancelled" },
                    color = Color.White, style = if (inc.active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            if (inc.active) {
                Text(
                    "%d:%02d".format(secs / 60, secs % 60), color = Color.White,
                    fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 72.sp, lineHeight = 74.sp,
                )
                Text("Your people are being alerted. Keep the app open if you can.", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (inc.smsSent > 0) "Your contacts are getting an \"I'm safe\" text." else "No texts had gone out, so no one was told.",
                    color = Muted, style = MaterialTheme.typography.bodyLarge,
                )
            }
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
                        onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:100"))) },
                        colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White),
                    ) {
                        Icon(Icons.Filled.Call, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Call 100")
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
            EvidenceCard(sosActive = inc.active)
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
                if (inc.active && inc.recipients.any { it.state == "compose" }) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Android hasn't allowed this app to send texts by itself, so your SOS text is waiting in Messages. Tap Send there.",
                        color = Amber, style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    ChunkyButton("Open Messages again", tone = Tone.Danger, icon = Icons.Filled.Email) { SosEngine.composeAgain() }
                }
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
                            when (r.state) { "sending" -> "Sending"; "sent" -> "Sent"; "delivered" -> "Delivered"; "compose" -> "In Messages"; else -> "Failed" },
                            color = when (r.state) { "failed" -> SosRed; "sending" -> Muted; "compose" -> Amber; else -> Lilac },
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
                    if (!`in`.security4her.app.core.Pin.isSet(ctx)) SosEngine.end("resolved") else askPin = true
                }
            } else PrimaryButton("Back to home") { SosEngine.dismiss() }
        }
    }

    if (askPin) {
        var pin by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { askPin = false },
            containerColor = Surface1,
            title = { Text("Enter your PIN") },
            text = {
                Column {
                    Field(pin, { pin = it.filter(Char::isDigit).take(4); wrong = null }, "4-digit PIN", KeyboardType.NumberPassword, secret = true)
                    wrong?.let { Text(it, color = SosRed, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = `in`.security4her.app.core.Pin.check(ctx, pin)
                    if (r is `in`.security4her.app.core.Pin.Check.Ok) { askPin = false; SosEngine.end("resolved") }
                    else { wrong = `in`.security4her.app.core.Pin.message(r); pin = "" }
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
internal fun PrimaryButton(text: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
    ChunkyButton(text, busy = busy, enabled = enabled, onClick = onClick)

@Composable
internal fun Note(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).padding(14.dp)) {
        Text(text, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
