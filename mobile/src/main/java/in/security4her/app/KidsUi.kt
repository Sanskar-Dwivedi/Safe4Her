package `in`.security4her.app

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.security4her.app.core.AppLog
import `in`.security4her.app.core.Backend
import `in`.security4her.app.core.Contact
import `in`.security4her.app.core.Profile
import `in`.security4her.app.core.Settings
import `in`.security4her.app.core.Store
import `in`.security4her.app.sos.Locator
import `in`.security4her.app.sos.SosEngine
import `in`.security4her.app.sos.Sms
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID

// ---------------------------------------------------------------- Children's section
// DigiLocker gives a date of birth with the name and gender. Anyone under 18 gets this section instead of the
// adult app: one big SOS that reaches their grown-ups, "I got there" check-ins, Childline, and safety lessons in
// a child's words. Children can't post on the public map, and the full app unlocks on their 18th birthday.

/** Age from the DigiLocker date of birth. Only the date is kept, on this phone, to know when they turn 18. */
object Age {
    private val format = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    fun yearsOf(dob: String, today: LocalDate = LocalDate.now()): Int? =
        runCatching { Period.between(LocalDate.parse(dob, format), today).years }.getOrNull()

    fun save(ctx: Context, dob: String) = Store.prefs(ctx).edit().putString("dob", dob).apply()
    fun clear(ctx: Context) = Store.prefs(ctx).edit().remove("dob").apply()
    fun years(ctx: Context): Int? = Store.prefs(ctx).getString("dob", null)?.let { yearsOf(it) }
    fun isChild(ctx: Context): Boolean = years(ctx)?.let { it < 18 } == true
}

private enum class KidTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home), Learn("Is it OK?", Icons.Filled.Star), Family("Family", Icons.Filled.Favorite),
    Grownups("My grown-ups", Icons.Filled.Person), Me("Me", Icons.Filled.AccountCircle),
}

private val Sky = Color(0xFF7CC8FF)
private val Sun = Color(0xFFFFC94D)

@Composable
internal fun KidsMain(profile: Profile, onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(KidTab.Home) }
    var contacts by remember { mutableStateOf<List<Contact>?>(null) }
    var contactsError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    var grownupsOpen by rememberSaveable { mutableStateOf(false) } // unlocked with the PIN this visit
    LaunchedEffect(profile.displayName) { Store.prefs(ctx).edit().putString("family_name", profile.displayName?.substringBefore(' ') ?: "Your child").apply() }
    LaunchedEffect(reload) {
        try { contacts = Backend.contacts(); contactsError = null } catch (e: Exception) {
            contactsError = e.message; if (contacts == null) contacts = emptyList()
        }
    }
    when (overlay) {
        "fake" -> { FakeCallScreen(onClose = { overlay = null }); return }
        "walk" -> { BackHandler { overlay = null }; JourneySetupScreen(onClose = { overlay = null }); return }
        "chat" -> { ChatScreen(onClose = { overlay = null }); return }
        "pocket" -> { KidsPocketScreen(onPractice = { overlay = "practice" }, onClose = { overlay = null }); return }
        "practice" -> { PocketPracticeScreen(onClose = { overlay = "pocket" }); return }
        "map" -> { KidsBack("Safe places", onBack = { overlay = null }) { SafeMapScreen(readOnly = true) }; return }
        "help" -> { BackHandler { overlay = null }; HelpScreen(onMap = { overlay = "map" }, onFakeCall = { overlay = "fake" }, onExit = { overlay = null }); return }
        "watch" -> { WatchInstallScreen(onClose = { overlay = null }); return }
        "locker" -> { EvidenceLockerScreen(onClose = { overlay = "controls" }); return }
        "log" -> { LogScreen(onBack = { overlay = "controls" }); return }
        "unlockGrownups" -> { PinGate("Change grown-ups", "Ask a grown-up to enter the PIN.", onUnlocked = { grownupsOpen = true; overlay = null }, onCancel = { overlay = null }); return }
        "parent" -> { PinGate("Grown-up settings", "Ask a grown-up to enter the PIN.", onUnlocked = { overlay = "controls" }, onCancel = { overlay = null }); return }
        "controls" -> {
            ParentControlsScreen(
                profile.displayName?.substringBefore(' ') ?: "your child", contacts, onClose = { overlay = null },
                onLocker = { overlay = "locker" }, onLog = { overlay = "log" },
            ); return
        }
    }
    val pending by Family.pendingLink.collectAsState()
    LaunchedEffect(pending) { if (pending != null) { Family.pendingLink.value = null; tab = KidTab.Family } } // a child's own link
    BackHandler(tab != KidTab.Home) { tab = KidTab.Home }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                KidTab.Home -> KidsHome(profile, contacts, onGrownups = { tab = KidTab.Grownups }, onLearn = { tab = KidTab.Learn }, onOverlay = { overlay = it })
                KidTab.Learn -> KidsLearn(contacts)
                KidTab.Family -> KidsFamily(profile.displayName?.substringBefore(' ') ?: "your child", contacts)
                KidTab.Grownups ->
                    if (KidsSettings.load(ctx).lockGrownups && `in`.security4her.app.core.Pin.isSet(ctx) && !grownupsOpen) GrownupsLocked(contacts) {
                        overlay = null; grownupsOpen = false
                        tab = KidTab.Grownups; overlay = "unlockGrownups"
                    } else ContactsScreen(contacts, contactsError) { reload++ }
                KidTab.Me -> KidsMe(profile, onSignedOut, onParent = { overlay = "parent" })
            }
        }
        KidsTabBar(tab) { tab = it }
    }
}

@Composable
private fun KidsTabBar(tab: KidTab, onTab: (KidTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(28.dp)).background(Surface1).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        KidTab.entries.forEach { t ->
            val sel = t == tab
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(if (sel) Sky else Color.Transparent)
                    .clickable(onClickLabel = t.label) { onTab(t) }.animateContentSize(tween(220))
                    .padding(horizontal = if (sel) 16.dp else 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, if (sel) null else t.label, tint = if (sel) Color(0xFF0B2233) else Muted, modifier = Modifier.size(22.dp))
                if (sel) { Spacer(Modifier.width(8.dp)); Text(t.label, color = Color(0xFF0B2233), style = MaterialTheme.typography.labelLarge, maxLines = 1) }
            }
        }
    }
}

private fun guardians(contacts: List<Contact>?) = contacts.orEmpty().filter { it.receivesSos }.sortedBy { it.priority }

/** Texts every grown-up that she or he got somewhere safely, with a map pin, and tells the family page. */
private suspend fun checkIn(ctx: Context, who: String, place: String, to: List<Contact>): String {
    val s = Settings.load(ctx)
    val numbers = to.map { it.phone } // grown-ups are family: Test Mode never replaces them
    if (numbers.isEmpty()) return "Add a grown-up first."
    val at = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())
    val fix = if (!KidsSettings.load(ctx).shareLocation) null else runCatching { Locator.last(ctx) }.getOrNull()
    val pin = fix?.let { " https://maps.google.com/?q=%.5f,%.5f".format(Locale.US, it.lat, it.lng) }.orEmpty()
    val body = "$who reached $place safely at $at.$pin (Security4Her)"
    val id = UUID.randomUUID().toString()
    val errors = numbers.mapNotNull { Sms.send(ctx, it, body, id) }
    val failed = errors.size
    Family.post(ctx, "checkin", "Reached $place", fix?.lat, fix?.lng)
    AppLog.info("Kids", "Check-in \"$place\" sent to ${numbers.size - failed} of ${numbers.size}" + errors.firstOrNull()?.let { " ($it)" }.orEmpty())
    return when {
        failed == 0 -> "Sent to " + to.joinToString(", ") { it.name } + "."
        failed < numbers.size -> "Sent to some of your grown-ups."
        else -> "Couldn't send. Check SMS is allowed."
    }
}

@Composable
private fun KidsHome(profile: Profile, contacts: List<Contact>?, onGrownups: () -> Unit, onLearn: () -> Unit, onOverlay: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = profile.displayName?.substringBefore(' ') ?: "there"
    val grown = guardians(contacts)
    val first = grown.firstOrNull()
    var sending by remember { mutableStateOf<String?>(null) }
    var smsOn by remember { mutableStateOf(Sms.allowed(ctx)) }
    /** Live location to every grown-up, by text: the same live map page an SOS uses. */
    fun shareLive() {
        if (grown.isEmpty()) { onGrownups(); return }
        val s = `in`.security4her.app.sos.LiveShare.session.value ?: `in`.security4her.app.sos.LiveShare.start(ctx)
        val id = UUID.randomUUID().toString()
        val ok = grown.count { Sms.send(ctx, it.phone, "$name is sharing where they are, live: ${s.url} (Security4Her)", id) == null }
        scope.launch { Family.post(ctx, "checkin", "Shared live location") }
        Toast.makeText(ctx, if (ok == grown.size) "Sent to " + grown.joinToString(", ") { it.name } + "." else "Sent to $ok of ${grown.size}.", Toast.LENGTH_LONG).show()
        AppLog.info("Kids", "Live location shared with $ok grown-ups")
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { smsOn = Sms.allowed(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hi $name!", fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, color = Ink)
                Text("You're safe to ask for help, any time.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Kids mode", color = Sky, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Sky.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        if (!smsOn) {
            Spacer(Modifier.height(14.dp))
            SmsAccessCard(onGranted = { smsOn = Sms.allowed(ctx) })
        }
        if (contacts != null && grown.isEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Sun.copy(alpha = 0.12f)).padding(16.dp)) {
                Text("Add a grown-up you trust", style = MaterialTheme.typography.titleMedium, color = Sun)
                Text("Mum, Dad, a guardian or a teacher. They hear from you when you press SOS.", color = Ink, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                ChunkyButton("Add a grown-up", tone = Tone.Plain, icon = Icons.Filled.Person, onClick = onGrownups)
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SosHoldButton { SosEngine.start(ctx, trigger = "button", source = "phone", reason = "child SOS") }
        }
        Text(
            "Scared or in danger? Hold the button. " + (first?.let { "${it.name} and your grown-ups" } ?: "Your grown-ups") +
                " get where you are, and a call goes out.",
            color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigCall(Modifier.weight(1f), first?.let { "Call ${it.name}" } ?: "Call a grown-up", first?.phone ?: "", Sky) {
                if (first == null) onGrownups() else dial(ctx, first.phone)
            }
            BigCall(Modifier.weight(1f), "Childline", "1098", Sun) { dial(ctx, "1098") }
        }

        Spacer(Modifier.height(22.dp))
        Text("I got there safely", style = MaterialTheme.typography.titleMedium, color = Ink)
        Text("One tap tells your grown-ups, with where you are.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KidsSettings.load(ctx).places.forEach { place ->
                val busy = sending == place
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(if (busy) Mint else Surface1).border(1.dp, Mint.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .clickable(enabled = sending == null) {
                            sending = place
                            scope.launch {
                                val r = checkIn(ctx, name, place.lowercase().let { if (it == "home") "home" else it }, grown)
                                Toast.makeText(ctx, r, Toast.LENGTH_LONG).show()
                                sending = null
                            }
                        }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Check, null, tint = if (busy) OnMint else Mint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(place, color = if (busy) OnMint else Ink, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("More ways to stay safe", style = MaterialTheme.typography.titleMedium, color = Ink)
        Spacer(Modifier.height(10.dp))
        val pocketOn = `in`.security4her.app.sos.PocketSos.enabled(ctx)
        val pocketHow = if (`in`.security4her.app.sos.PocketSos.powerMode(ctx)) "power button ×3" else "tap ×3"
        val tiles = listOf(
            KidAction(Icons.Filled.Lock, SosRed, "Secret SOS", if (pocketOn) "On: $pocketHow, even in a bag" else "Tap ×3 or power ×3") { onOverlay("pocket") },
            KidAction(Icons.Filled.Share, Mint, "Share where I am", "Send your grown-ups a live map") { shareLive() },
            KidAction(Icons.Filled.Phone, Mint, "Pretend call", "A call from home, to get away") { onOverlay("fake") },
            KidAction(Icons.Filled.Place, Sky, "Walk with me", "Your grown-ups check you arrive") { onOverlay("walk") },
            KidAction(Icons.Filled.Place, Lilac, "Safe places", "Police, hospitals, open shops") { onOverlay("map") },
            KidAction(Icons.Filled.Call, Sun, "All helplines", "Who to call for what") { onOverlay("help") },
            KidAction(Icons.Filled.Star, Sun, "Is it OK?", "Touch, secrets, online, lost", onLearn),
            KidAction(Icons.Filled.Lock, Lilac, "Ask a question", "Answers right on your phone") { onOverlay("chat") },
            KidAction(Icons.Filled.Favorite, Sky, "My watch", "SOS from your wrist") { onOverlay("watch") },
        )
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { a -> KidTile(Modifier.weight(1f), a.icon, a.title, a.sub, a.tint, a.go) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BigCall(modifier: Modifier, title: String, sub: String, color: Color, onClick: () -> Unit) {
    Chunky(onClick, modifier, tone = Tone.Plain, depth = 4.dp, shape = RoundedCornerShape(20.dp), outline = color.copy(alpha = 0.6f)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Call, null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1)
                if (sub.isNotBlank()) Text(sub, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun KidTile(modifier: Modifier, icon: ImageVector, title: String, sub: String, tint: Color, onClick: () -> Unit) {
    Chunky(onClick, modifier, tone = Tone.Plain, depth = 4.dp, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(sub, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------- "Is it OK?"

private data class Lesson(val title: String, val hook: String, val color: Color, val points: List<String>)

private val Lessons = listOf(
    Lesson(
        "My body is mine", "Who can touch me, and where", Color(0xFFFF8FA3),
        listOf(
            "No one should touch the parts your swimsuit covers, ask you to touch theirs, or take photos of them. Not even someone you know.",
            "If it happens: say NO loudly, GO away, and TELL a grown-up you trust.",
            "It is never your fault, and you won't be in trouble for telling.",
        ),
    ),
    Lesson(
        "Safe and unsafe secrets", "Which secrets to keep", Sun,
        listOf(
            "A surprise, like a birthday present, is a safe secret: everyone finds out soon and it makes people happy.",
            "A secret that makes you feel scared, sad or confused is not OK, even if someone says you'll get in trouble.",
            "Tell a grown-up you trust. Keep telling until someone listens.",
        ),
    ),
    Lesson(
        "Strangers online", "Games, chats and photos", Sky,
        listOf(
            "Never share your photo, school, address or password with someone you only know online.",
            "If someone asks for photos, asks you to keep a secret, or wants to meet: stop replying and tell a grown-up.",
            "Don't delete the messages. A grown-up can report them on 1930 or cybercrime.gov.in.",
        ),
    ),
    Lesson(
        "If I get lost", "What to do, step by step", Mint,
        listOf(
            "Stop and stay where you are, so your grown-ups can find you.",
            "Ask for help from a police officer, a shop counter, or a mother with children.",
            "Learn one grown-up's phone number by heart. Or hold SOS: it sends them where you are.",
        ),
    ),
    Lesson(
        "Someone is bullying me", "At school or online", Lilac,
        listOf(
            "Being bullied is not your fault, and you don't have to handle it alone.",
            "Tell a teacher or a grown-up at home. Save any messages or posts.",
            "Stay near friends and grown-ups. Walk away rather than fight back.",
        ),
    ),
    Lesson(
        "Someone is following me", "On the street or the bus", Color(0xFFFF4D5E),
        listOf(
            "Go into a busy shop, a school, or anywhere with grown-ups. Don't go home if they're behind you.",
            "Hold the SOS button. Your grown-ups get where you are.",
            "If you're in danger, call 112.",
        ),
    ),
)

@Composable
private fun KidsLearn(contacts: List<Contact>?) {
    val ctx = LocalContext.current
    val first = guardians(contacts).firstOrNull()
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text("Is it OK?", fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, color = Ink)
        Text("Tap a card. If something feels wrong, it's OK to tell.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Lessons.forEachIndexed { i, l ->
            val isOpen = open == i
            Column(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(22.dp)).background(Surface1)
                    .border(1.5.dp, if (isOpen) l.color else Color.Transparent, RoundedCornerShape(22.dp))
                    .clickable { open = if (isOpen) null else i }.animateContentSize(tween(240)).padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(l.color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = l.color, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(l.title, style = MaterialTheme.typography.titleMedium, color = Ink)
                        Text(l.hook, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (isOpen) "–" else "+", color = l.color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                if (isOpen) {
                    Spacer(Modifier.height(12.dp))
                    l.points.forEach { p ->
                        Row(Modifier.padding(vertical = 5.dp)) {
                            Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(l.color))
                            Spacer(Modifier.width(10.dp))
                            Text(p, color = Ink, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KidChip(first?.let { "Tell ${it.name}" } ?: "Tell a grown-up", Sky) { first?.let { dial(ctx, it.phone) } ?: dial(ctx, "1098") }
                        KidChip("Call Childline 1098", Sun) { dial(ctx, "1098") }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun KidChip(label: String, color: Color, onClick: () -> Unit) = Text(
    label, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1,
    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.14f)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 9.dp),
)

// ---------------------------------------------------------------- Me

@Composable
private fun KidsMe(profile: Profile, onSignedOut: () -> Unit, onParent: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val years = Age.years(ctx)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(profile.displayName ?: "?", 56.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(profile.displayName ?: "You", style = MaterialTheme.typography.titleLarge, color = Ink)
                Text("Child account, verified with DigiLocker" + (years?.let { " · age $it" } ?: ""), color = Sky, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("How Kids mode keeps you safe", style = MaterialTheme.typography.titleMedium, color = Ink)
        Spacer(Modifier.height(10.dp))
        listOf(
            "Your SOS goes to your grown-ups first, with where you are.",
            "You can't post on the public safety map, so no stranger sees where you go.",
            "Your name and number are never shown to anyone outside your grown-ups.",
            "Childline 1098 is always one tap away.",
        ).forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Surface1).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = Mint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(t, color = Ink, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        ChunkyButton("Grown-up settings", tone = Tone.Plain, icon = Icons.Filled.Lock, onClick = onParent)
        Spacer(Modifier.height(14.dp))
        Text(
            "On your 18th birthday this becomes the full Security4Her app.",
            color = Muted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { scope.launch { runCatching { Backend.signOut() }; Age.clear(ctx); onSignedOut() } }) { Text("Sign out", color = SosRed) }
    }
}

/** My grown-ups, read-only until a grown-up enters the PIN. */
@Composable
private fun GrownupsLocked(contacts: List<Contact>?, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text("My grown-ups", fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, color = Ink)
        Text("They get your SOS and are called in this order.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        guardians(contacts).forEachIndexed { i, c ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(20.dp)).background(Surface1).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", color = Sky, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                Spacer(Modifier.width(14.dp))
                Avatar(c.name, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium, color = Ink)
                    Text(listOfNotNull(c.relation, c.phone).joinToString(" · "), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        ChunkyButton("A grown-up can change these", tone = Tone.Plain, icon = Icons.Filled.Lock, onClick = onUnlock)
    }
}

private class KidAction(val icon: ImageVector, val tint: Color, val title: String, val sub: String, val go: () -> Unit)

/** Secret SOS: three taps on the phone, or three presses of the power button, set up and tested. */
@Composable
private fun KidsPocketScreen(onPractice: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp)) {
        TextButton(onClick = onClose) { Text("‹  Back", color = Ink) }
        Text("Secret SOS", fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, color = Ink)
        Text(
            "Start an SOS without taking the phone out: tap it three times, or press the power button three times. " +
                "It works in a pocket or a bag, with the screen off.",
            color = Muted, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
        PocketControls(onPractice = onPractice)
        Spacer(Modifier.height(24.dp))
    }
}

/** A screen from the full app, with a way back to Kids mode. */
@Composable
private fun KidsBack(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  Back", color = Ink) }
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}
