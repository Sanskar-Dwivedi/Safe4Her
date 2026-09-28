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
import `in`.security4her.app.map.SafetyMapScreen
import `in`.security4her.app.sos.Incident
import `in`.security4her.app.sos.SosEngine
import `in`.security4her.app.sos.WearBridge
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
        Backend.init(this)
        SosEngine.init(this)
    }
}

// ---- Tokens: aubergine night, one lilac accent; red is reserved for SOS, amber for Test Mode ----
private val Night = Color(0xFF16101E)
private val Surface1 = Color(0xFF221A2C)
private val Surface2 = Color(0xFF2E2439)
private val Line = Color(0xFF3D3149)
private val Ink = Color(0xFFF3EDF7)
private val Muted = Color(0xFFA99BB8)
private val Lilac = Color(0xFFC49BFF)
private val OnLilac = Color(0xFF1E0E33)
private val SosRed = Color(0xFFFF4D5E)
private val Amber = Color(0xFFFFB547)

@Composable
private fun S4hTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Lilac, onPrimary = OnLilac, secondary = Lilac, onSecondary = OnLilac,
        background = Night, onBackground = Ink, surface = Surface1, onSurface = Ink,
        surfaceVariant = Surface2, onSurfaceVariant = Muted, surfaceContainer = Surface1,
        surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface2, surfaceContainerLow = Surface1,
        outline = Line, outlineVariant = Line, error = SosRed, onError = Color.White,
    )
    val base = Typography()
    val type = base.copy(
        displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
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
private enum class Tab(val label: String) { Home("Home"), Contacts("Contacts"), Map("Map"), Settings("Settings") }

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<Profile?>(null) }
    var stage by remember { mutableStateOf(Stage.Loading) }
    val incident by SosEngine.current.collectAsStateWithLifecycle()

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
            Shield(56.dp)
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(Modifier.size(22.dp), color = Lilac, strokeWidth = 2.dp)
        }
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 40.dp)) {
        Shield(44.dp)
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

private data class Perm(val title: String, val why: String, val granted: (Context) -> Boolean, val ask: () -> Unit)

@Composable
private fun PermissionsScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    val perms = buildList {
        add(Perm("Location", "Your texts and live link show where you are.", { has(Manifest.permission.ACCESS_FINE_LOCATION) }) {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        })
        if (Build.VERSION.SDK_INT >= 29) add(
            Perm("Location all the time", "Lets your watch start SOS while the phone is locked. Choose \"Allow all the time\".",
                { has(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                if (has(Manifest.permission.ACCESS_FINE_LOCATION)) launcher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            },
        )
        add(Perm("Text messages", "Texts your contacts the moment SOS starts.", { has(Manifest.permission.SEND_SMS) }) {
            launcher.launch(arrayOf(Manifest.permission.SEND_SMS))
        })
        add(Perm("Phone calls", "Calls your chosen number when the countdown ends.", { has(Manifest.permission.CALL_PHONE) }) {
            launcher.launch(arrayOf(Manifest.permission.CALL_PHONE))
        })
        if (Build.VERSION.SDK_INT >= 33) add(
            Perm("Notifications", "Shows SOS progress and the Cancel call button.", { has(Manifest.permission.POST_NOTIFICATIONS) }) {
                launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            },
        )
        add(Perm("Run in the background", "Stops Android from pausing SOS to save battery.",
            { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) }) {
            runCatching {
                ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }
        })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp)) {
        Text("Let SOS work when it matters", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Each of these is used only during an SOS.", color = Muted, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            key(tick) {
                perms.forEach { p ->
                    val ok = p.granted(ctx)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.titleMedium)
                            Text(p.why, color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.width(12.dp))
                        if (ok) Text("Allowed", color = Lilac, style = MaterialTheme.typography.labelLarge)
                        else OutlinedButton(onClick = p.ask, border = androidx.compose.foundation.BorderStroke(1.dp, Lilac)) {
                            Text("Allow", color = Lilac)
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
    BackHandler(tab != Tab.Home) { tab = Tab.Home }

    Column(Modifier.fillMaxSize()) {
        if (settings.testMode) TestBanner(settings.demoNumber.isBlank()) { tab = Tab.Settings }
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.Home -> HomeScreen(profile, contacts, settings, onContacts = { tab = Tab.Contacts })
                Tab.Contacts -> ContactsScreen(contacts, contactsError) { reload++ }
                Tab.Map -> if (!Backend.mock && BuildConfig.HAS_MAPS_KEY) SafetyMapScreen(Backend.client) else DemoMap()
                Tab.Settings -> SettingsScreen(profile, settings, onChange = { settings = it }, onSignedOut = onSignedOut)
            }
        }
        TabBar(tab) { tab = it }
    }
}

@Composable
private fun TestBanner(noDemoNumber: Boolean, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Amber.copy(alpha = 0.14f)).clickable(onClick = onFix).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Amber))
        Spacer(Modifier.width(10.dp))
        Text(
            if (noDemoNumber) "Test Mode is on. Set a demo number so police texts and calls reach a teammate."
            else "Test Mode is on. Police texts and calls go to your demo number, never 112.",
            color = Amber, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Surface1).padding(horizontal = 8.dp)) {
        Tab.entries.forEach { t ->
            val sel = t == tab
            Column(
                Modifier.weight(1f).clickable { onTab(t) }.padding(top = 10.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.width(24.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (sel) Lilac else Color.Transparent))
                Spacer(Modifier.height(8.dp))
                Text(t.label, color = if (sel) Ink else Muted, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// ---------------------------------------------------------------- Home

@Composable
private fun HomeScreen(profile: Profile, contacts: List<Contact>?, settings: Settings, onContacts: () -> Unit) {
    val ctx = LocalContext.current
    var watch by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            watch = WearBridge.watchReachable(ctx)
            delay(10_000)
        }
    }
    val alerted = contacts?.filter { it.receivesSos }.orEmpty()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text("Hi, ${profile.displayName ?: "there"}", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (watch == true) Lilac else Line))
            Spacer(Modifier.width(8.dp))
            Text(
                when (watch) { null -> "Looking for your watch"; true -> "Watch connected"; false -> "Watch not reachable. Phone SOS still works." },
                color = Muted, style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(36.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SosHoldButton { SosEngine.start(ctx, trigger = "button", source = "phone") }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Texts go out as soon as you let go. A call follows after ${settings.countdownSec} seconds unless you cancel it.",
            color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(32.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).padding(18.dp)) {
            Text("Who hears from you", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            when {
                contacts == null -> Text("Loading contacts", color = Muted)
                alerted.isEmpty() -> {
                    Text("No one yet. Add at least one trusted contact so your SOS reaches someone.", color = Muted)
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = onContacts, border = androidx.compose.foundation.BorderStroke(1.dp, Lilac)) {
                        Text("Add contacts", color = Lilac)
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                        alerted.take(5).forEach { Avatar(it.name, 40.dp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    val police = if (settings.testMode) settings.demoNumber.ifBlank { null }?.let { "demo number" } else "police ${settings.policeNumber}"
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
private fun SosHoldButton(onFire: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var holding by remember { mutableStateOf(false) }
    Box(
        Modifier.size(252.dp).pointerInput(Unit) {
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
            drawCircle(SosRed.copy(alpha = 0.16f), radius = size.minDimension / 2 - inset, style = Stroke(w))
            drawArc(
                SosRed, -90f, 360f * progress.value, false,
                topLeft = Offset(inset, inset), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round),
            )
            drawCircle(
                SosRed.copy(alpha = 0.35f), radius = size.minDimension / 2 - 18.dp.toPx(),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f))),
            )
        }
        Box(
            Modifier.size(190.dp).clip(CircleShape).background(SosRed),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SOS", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                Text(if (holding) "Keep holding" else "Hold to send", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Surface2).border(2.dp, Surface1, CircleShape),
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
                Shield(52.dp, Line)
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
private fun SettingsScreen(profile: Profile, settings: Settings, onChange: (Settings) -> Unit, onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var linkMsg by remember { mutableStateOf<String?>(null) }
    fun set(s: Settings) {
        s.save(ctx)
        onChange(s)
        if (s.testMode != settings.testMode) scope.launch { WearBridge.linkWatch(ctx) }
    }

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
            if (settings.testMode) {
                Spacer(Modifier.height(12.dp))
                Field(settings.demoNumber, { set(settings.copy(demoNumber = it)) }, "Demo number (a teammate's phone)", KeyboardType.Phone)
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
        Section("Account") {
            Text(profile.displayName ?: "You", style = MaterialTheme.typography.titleMedium)
            Text(
                if (profile.verificationMethod == "digilocker") "Verified with DigiLocker" else "Verified (demo)",
                color = Lilac, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { scope.launch { runCatching { Backend.signOut() }; onSignedOut() } }, contentPadding = PaddingValues(0.dp)) {
                Text("Sign out", color = SosRed)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(16.dp), content = content)
    Spacer(Modifier.height(22.dp))
}

// ---------------------------------------------------------------- Active SOS

@Composable
private fun ActiveSosScreen(inc: Incident) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var askPin by remember { mutableStateOf(false) }
    LaunchedEffect(inc.active) { while (inc.active) { now = System.currentTimeMillis(); delay(250) } }
    BackHandler(inc.active) {}
    val secs = ((now - inc.startedAt) / 1000).coerceAtLeast(0)
    val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().background(if (inc.active) SosRed else Surface1).padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Text(
                when (inc.state) { "active" -> "SOS active"; "resolved" -> "You marked yourself safe"; else -> "SOS cancelled" },
                color = Color.White, style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (inc.active) "Running for %d:%02d".format(secs / 60, secs % 60) + if (inc.test) "  ·  Test Mode" else ""
                else if (inc.smsSent > 0) "Your contacts are getting an \"I'm safe\" text." else "No texts had gone out, so no one was told.",
                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge,
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Call
            Card {
                val left = inc.callAt?.let { ((it - now) / 1000.0).let { s -> kotlin.math.ceil(s).toInt().coerceAtLeast(0) } }
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
                    ) { Text("Call 112") }
                }
            }
            // Live link
            Card {
                Text("Live location link", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    inc.shareUrl ?: when (inc.server) { "failed" -> "Server unreachable. Texts include a map link instead."; "retrying" -> "Server not reachable. Retrying."; else -> "Creating link" },
                    color = if (inc.shareUrl != null) Lilac else Muted, style = MaterialTheme.typography.bodyMedium,
                )
                inc.shareUrl?.let { url ->
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Share live link"))
                    }, border = androidx.compose.foundation.BorderStroke(1.dp, Lilac)) { Text("Share link", color = Lilac) }
                }
                inc.lat?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Last fix %.4f, %.4f  ±%s m, %s".format(Locale.US, inc.lat, inc.lng, inc.acc?.roundToInt() ?: "?", clock.format(Date(inc.fixAt ?: now))),
                        color = Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // Recipients
            if (inc.recipients.isNotEmpty()) Card {
                Text("Texts", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                inc.recipients.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(r.name, 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.phone, color = Muted, style = MaterialTheme.typography.bodySmall)
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
        Box(Modifier.fillMaxWidth().padding(20.dp)) {
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
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(18.dp), content = content)
}

// ---------------------------------------------------------------- Demo map (no backend / no Maps key)

private data class DemoPlace(val name: String, val kind: String, val lat: Double, val lng: Double)

// MOCK: a few real Nagpur landmarks, shown when Supabase or the Maps key isn't configured.
private val demoCenter = 21.1458 to 79.0882
private val demoPlaces = listOf(
    DemoPlace("Sitabuldi Police Station", "Police", 21.1466, 79.0849),
    DemoPlace("Dhantoli Police Station", "Police", 21.1352, 79.0874),
    DemoPlace("Government Medical College & Hospital", "Hospital", 21.1336, 79.0940),
    DemoPlace("Mayo Hospital (IGGMC)", "Hospital", 21.1530, 79.0968),
    DemoPlace("Sadar Police Station", "Police", 21.1600, 79.0800),
    DemoPlace("24-hour pharmacy, Dharampeth", "Pharmacy", 21.1420, 79.0700),
)

@Composable
private fun DemoMap() {
    val ctx = LocalContext.current
    fun meters(p: DemoPlace): Pair<Double, Double> {
        val dy = (p.lat - demoCenter.first) * 111_320
        val dx = (p.lng - demoCenter.second) * 111_320 * cos(Math.toRadians(demoCenter.first))
        return dx to dy
    }
    val sorted = demoPlaces.sortedBy { val (x, y) = meters(it); x * x + y * y }
    val maxR = 2_500.0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Nearest safe places", style = MaterialTheme.typography.headlineSmall)
        Text("Sitabuldi, Nagpur", color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Surface1)) {
            Canvas(Modifier.fillMaxSize().padding(18.dp)) {
                val c = Offset(size.width / 2, size.height / 2)
                val r = size.minDimension / 2
                listOf(0.33f, 0.66f, 1f).forEach { drawCircle(Line, radius = r * it, center = c, style = Stroke(1.dp.toPx())) }
                drawLine(Line, Offset(c.x, 0f), Offset(c.x, size.height), 1.dp.toPx())
                drawLine(Line, Offset(0f, c.y), Offset(size.width, c.y), 1.dp.toPx())
                demoPlaces.forEach { p ->
                    val (x, y) = meters(p)
                    val o = Offset(c.x + (x / maxR * r).toFloat(), c.y - (y / maxR * r).toFloat())
                    drawCircle(if (p.kind == "Police") Lilac else Ink.copy(alpha = if (p.kind == "Hospital") 0.9f else 0.5f), radius = 6.dp.toPx(), center = o)
                }
                drawCircle(SosRed.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = c)
                drawCircle(SosRed, radius = 6.dp.toPx(), center = c)
            }
            Text("You", color = SosRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Center).padding(top = 40.dp))
            Text("2.5 km", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(Lilac, "Police"); Legend(Ink.copy(alpha = 0.9f), "Hospital"); Legend(Ink.copy(alpha = 0.5f), "Pharmacy")
        }
        Spacer(Modifier.height(18.dp))
        sorted.forEach { p ->
            val (x, y) = meters(p)
            val d = sqrt(x * x + y * y)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Surface1).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${p.kind}, " + if (d < 1000) "${(d / 10).roundToInt() * 10} m" else "%.1f km".format(Locale.US, d / 1000),
                        color = Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${p.lat},${p.lng}&travelmode=walking")))
                    }
                }) { Text("Go", color = Lilac) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Note("Sample places for the demo city. Connect Supabase and a Maps key to see the live map and community reports.")
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------- Shared bits

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType = KeyboardType.Text, secret: Boolean = false) {
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
private fun PrimaryButton(text: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
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
private fun Note(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Text(text, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
