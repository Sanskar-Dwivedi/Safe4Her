package `in`.security4her.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import `in`.security4her.app.core.AppLog
import `in`.security4her.app.core.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Ask Security4Her
// A small assistant that answers questions about the app, on the phone, with no internet needed.
// MOCK: answers come from the built-in guide below; a production build would put an LLM (needs an API key) behind the same screen.

private data class Msg(val fromUser: Boolean, val text: String)

private data class Topic(val keys: List<String>, val answer: (Settings) -> String)

private val TOPICS = listOf(
    Topic(listOf("hi", "hello", "hey", "namaste", "help")) {
        "Hi! Ask me anything about Security4Her: how SOS works, sharing your live location, Safe Journey, the safety map, evidence recording, your watch or settings."
    },
    Topic(listOf("sos", "emergency", "danger", "alert", "press", "hold", "button", "work", "happens")) { s ->
        "Press and hold SOS on Home for 1.5 seconds (or on your watch). Straight away:\n" +
            "• Your trusted contacts get a text with your location and a live-location link.\n" +
            "• The live link updates every 20 seconds.\n" +
            "• Audio${if (s.evidenceVideo) " and video" else ""} recording starts as evidence.\n" +
            "• After ${s.countdownSec} seconds, the app calls ${if (s.autoCallNumber.isBlank()) "your first contact" else s.autoCallNumber} unless you cancel."
    },
    Topic(listOf("cancel", "mistake", "false", "stop", "safe", "end", "accident")) {
        "Pressed it by mistake? On the SOS screen tap Cancel call to stop the automatic call, then tap I'm safe. If you set a PIN, you'll enter it first. Anyone who was texted gets an \"I'm safe\" message, so nobody panics."
    },
    Topic(listOf("internet", "offline", "data", "network", "signal", "wifi")) {
        "SOS texts are ordinary SMS, so they reach your contacts without mobile data. Each text includes a Google Maps link to where you are. The live-location page needs internet, and it catches up as soon as you're back online."
    },
    Topic(listOf("sms", "text", "texts", "restricted", "blocked", "permission", "automatic", "denied")) {
        "For SOS texts to go out automatically, Android has to allow SMS once. Android blocks it for apps installed from a download (\"App was denied access\"). Fix it in one tap: Home → Fix → Fix texting, then Update when Android asks; open the app again and tap Allow. Until then, an SOS opens Messages with everything filled in."
    },
    Topic(listOf("live", "location", "share", "sharing", "link", "track", "follow")) {
        "Tap Live location on Home to share a link. Anyone with it sees where you are on a map, updated every 20 seconds, until you tap Stop sharing. During an SOS the link is sent automatically."
    },
    Topic(listOf("journey", "travel", "trip", "walk", "going", "late", "cab", "auto", "destination", "arrive")) {
        "Safe Journey: tap Safe Journey on Home and pick where you're going (search, or drop a pin on the map). The app works out when you should arrive. If you're late, stop moving for a while or head the wrong way, it asks \"Are you okay?\". No answer in time, and your contacts get an SOS."
    },
    Topic(listOf("map", "places", "hospital", "police", "station", "zone", "red", "unsafe", "danger zone", "nearby")) {
        "The Map tab shows safe places near you: hospitals, police stations, pharmacies and places other women recommend. Red zones are areas women marked unsafe, or where SOS alerts keep happening. Tap Add to map to mark a place safe or unsafe yourself."
    },
    Topic(listOf("record", "recording", "evidence", "audio", "video", "camera", "proof", "locker")) {
        "When SOS starts, the phone records audio (and video if it's on) in short pieces. Each piece gets a fingerprint, so any edit shows. Recordings stay private on your phone in Me → Evidence locker, where you can play, check and export them. They can't be deleted for 24 hours."
    },
    Topic(listOf("contact", "contacts", "people", "family", "friend", "who", "add")) {
        "Add trusted contacts in the People tab, from your phone book or by typing a number. They're texted in the order shown, and you can switch anyone off without deleting them."
    },
    Topic(listOf("fake call", "fake", "excuse", "uncomfortable", "get away", "leave")) {
        "Fake call makes your phone ring with a real-looking call (from Mom, Dad or any name you type) so you can step away. Answer it and a voice talks to you. Home → Fake call; it can ring now, in 10 s or in 30 s. Nobody is actually called."
    },
    Topic(listOf("install watch", "watch app", "install on watch", "update watch", "watch update", "put on watch")) {
        "Your phone installs the watch app for you, no computer needed. On the watch turn on Developer options, then Wireless debugging, and tap Pair new device. Then open Me → Install or update the watch app here, type the 6-digit code and tap Pair and install. After that the watch updates itself."
    },
    Topic(listOf("watch", "wear", "wrist", "smartwatch")) {
        "With the Wear OS app, press and hold SOS on your watch even when your phone is in a bag. The watch also records audio from your wrist. It links to your phone on its own once both apps are open."
    },
    Topic(listOf("pocket", "power button", "power", "press", "knock", "knocks", "tap", "taps", "gesture", "triple", "bag", "without watch")) {
        "Pocket SOS: turn it on in Me → Pocket SOS. Press the power button 3 times, slowly (about one press a second), screen on or off, even in a pocket or bag. It buzzes on the third press, then counts down 5 seconds so you can cancel. Pressing fast starts your phone's own Emergency SOS too, so keep it slow. Prefer knocking? Choose Knock ×3 there instead."
    },
    Topic(listOf("voice", "shout", "say", "speak", "talk", "hands free", "hands-free", "scream")) {
        "Voice SOS is on the Wear OS watch. Turn it on from the watch's Home screen (Voice SOS), then saying HELP HELP HELP clearly, three times in a row, starts the same 5-second countdown as the other triggers: the watch vibrates, you can cancel, and then the full SOS goes out. It listens only while switched on, entirely on the watch; no audio is saved or sent. Use Practise on the watch to check it hears you."
    },
    Topic(listOf("pin", "password", "code")) {
        "A safety PIN stops anyone else from switching your SOS off. Set 4 digits in Me → During SOS. You'll be asked for it when you tap I'm safe."
    },
    Topic(listOf("test", "practice", "practise", "try")) {
        "Test Mode (in Me) sends the police text and the automatic call to a test number, like a friend's phone, instead of the police. Use it to try an SOS safely."
    },
    Topic(listOf("112", "181", "1091", "helpline", "number", "call", "numbers", "ambulance")) {
        "Emergency numbers in India:\n• 112: all emergencies (police, fire, ambulance)\n• 181: Women Helpline\n• 1091: Women Police Helpline\n• 100: Police\nCall 100 (police) is on Home and on the SOS screen."
    },
    Topic(listOf("private", "privacy", "data", "store", "stored", "secure", "who can see")) {
        "Your location is shared only during an SOS, a Safe Journey or when you share it yourself. Recordings stay on your phone unless you export them. Contacts only get what you send them."
    },
    Topic(listOf("digilocker", "verify", "verified", "women only", "why women", "aadhaar")) {
        "Security4Her is only for women, which keeps the safety map and alerts trustworthy. DigiLocker confirms it once when you sign up."
    },
    Topic(listOf("battery", "background", "killed", "sleep")) {
        "Allow Run in the background during setup (or in Android settings) so the phone doesn't pause an SOS or a Safe Journey to save battery."
    },
)

private fun answer(q: String, s: Settings): String {
    val t = q.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
    val words = t.split(' ').filter { it.isNotBlank() }.toSet()
    val best = TOPICS.maxByOrNull { topic -> topic.keys.count { k -> if (' ' in k) t.contains(k) else k in words } }
    val hits = best?.keys?.count { k -> if (' ' in k) t.contains(k) else k in words } ?: 0
    return if (best != null && hits > 0) best.answer(s)
    else "I can help with SOS, cancelling a false alarm, live location, Safe Journey, the safety map, evidence recording, contacts, your watch, helpline numbers and settings. Try one of the suggestions below."
}

private val SUGGESTIONS = listOf(
    "How does SOS work?", "Cancel a false alarm", "No internet?", "What is Safe Journey?", "Helpline numbers", "Is my recording private?",
)

@Composable
fun ChatScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf(Msg(false, "Hi, I'm here to help with Security4Her. What would you like to know?")) }
    var input by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    BackHandler(onBack = onClose)

    fun ask(q: String) {
        val text = q.trim()
        if (text.isEmpty() || typing) return
        msgs += Msg(true, text)
        input = ""
        typing = true
        scope.launch {
            delay(650)
            msgs += Msg(false, answer(text, Settings.load(ctx)))
            typing = false
            AppLog.info("Chat", "Answered: $text")
        }
    }
    LaunchedEffect(msgs.size, typing) { list.animateScrollToItem((msgs.size + if (typing) 1 else 0).coerceAtLeast(1) - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Back", onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = Muted)
            }
            Spacer(Modifier.width(6.dp))
            Logo(34.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Ask Security4Her", style = MaterialTheme.typography.titleMedium)
                Text("Answers about the app, on your phone", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(
            state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(msgs) { m -> Bubble(m) }
            if (typing) item { TypingDots() }
        }
        // Suggestions stay one tap away
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUGGESTIONS.forEach { s ->
                Text(
                    s, style = MaterialTheme.typography.labelMedium, color = Ink,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, Line, RoundedCornerShape(20.dp))
                        .clickable { ask(s) }.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, placeholder = { Text("Ask a question", color = Muted) },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp), maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { ask(input) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Lilac, unfocusedBorderColor = Line, cursorColor = Lilac,
                    focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(if (input.isBlank()) Surface2 else Lilac)
                    .clickable(enabled = input.isNotBlank(), onClickLabel = "Send") { ask(input) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Send, "Send", tint = if (input.isBlank()) Muted else OnLilac) }
        }
    }
}

@Composable
private fun Bubble(m: Msg) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            m.text, style = MaterialTheme.typography.bodyLarge, color = if (m.fromUser) OnLilac else Ink,
            modifier = Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (m.fromUser) 18.dp else 4.dp, bottomEnd = if (m.fromUser) 4.dp else 18.dp))
                .background(if (m.fromUser) Lilac else Surface1).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    Row(Modifier.clip(RoundedCornerShape(18.dp)).background(Surface1).padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420, delayMillis = i * 140), RepeatMode.Reverse), label = "dot$i")
            Box(Modifier.size(7.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(Muted))
        }
    }
}
