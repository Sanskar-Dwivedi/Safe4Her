package `in`.security4her.app

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import `in`.security4her.app.sos.LiveShare
import `in`.security4her.app.sos.ShareSheet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- Get help
// Trusted support in one place: what to do for her situation, the government services that handle it, a quick exit
// for when someone walks in, and a complaint she can write privately and share only when she chooses. All offline,
// nothing stored, nothing sent.

/** Situations, worded the way she'd describe them. */
internal enum class Need(val label: String) {
    Followed("Someone is following me"),
    Public("Harassed in public"),
    Online("Online abuse or morphed photos"),
    Home("Violence at home"),
    Child("A child needs help"),
    Medical("I need medical help"),
    Legal("I need legal help"),
    Talk("I need to talk to someone"),
}

/** A government service. Numbers are national and free; checked against the ministries that run them. */
internal data class Service(
    val name: String,
    val number: String,
    val what: String,
    val by: String,
    val hours: String,
    val expect: String,
    val needs: Set<Need>,
    val whatsapp: String? = null,
    val web: String? = null,
)

internal val Services = listOf(
    Service(
        "Emergency", "112", "Police, fire and ambulance, anywhere in India.", "Govt of India · ERSS", "24×7",
        "One number for every emergency. The call is routed to the nearest police control room, which can send a patrol " +
            "to your location. It works from any phone.",
        setOf(Need.Followed, Need.Public, Need.Home, Need.Child, Need.Medical),
    ),
    Service(
        "Women Helpline", "181", "Counselling, rescue and a One Stop Centre (Sakhi) near you.", "Ministry of Women & Child Development", "24×7",
        "A trained counsellor listens first. They can connect you to police, an ambulance, a shelter or the nearest One Stop " +
            "Centre, which offers medical help, legal aid, counselling and a safe place to stay under one roof.",
        setOf(Need.Home, Need.Public, Need.Followed, Need.Legal, Need.Talk),
    ),
    Service(
        "Childline", "1098", "For any child who is lost, hurt, abused or alone.", "Ministry of Women & Child Development", "24×7",
        "Anyone can call: the child, a parent, or a stranger who's worried. A team reaches the child, and can arrange " +
            "rescue, medical care, shelter and counselling.",
        setOf(Need.Child),
    ),
    Service(
        "Cyber Crime Helpline", "1930", "Morphed photos, online stalking, sextortion, fraud.", "Ministry of Home Affairs · I4C", "24×7",
        "Report online abuse by phone, or file it yourself at cybercrime.gov.in. Complaints about women and children can " +
            "be filed anonymously there, and platforms can be asked to take content down.",
        setOf(Need.Online), web = "https://cybercrime.gov.in",
    ),
    Service(
        "NCW on WhatsApp", "7827170170", "Report domestic violence by message, without a call.", "National Commission for Women", "Message any time",
        "Useful when a call isn't safe. Send a message describing what is happening; the Commission responds and can " +
            "involve the police and protection officers.",
        setOf(Need.Home), whatsapp = "917827170170",
    ),
    Service(
        "Police", "100", "Your local police control room.", "State police", "24×7",
        "Ask for a patrol, report an incident or ask where the nearest police station is. In many states 100 now reaches 112.",
        setOf(Need.Followed, Need.Public),
    ),
    Service(
        "Women Police Helpline", "1091", "Women's desk of the police, in many states.", "State police", "24×7 in most states",
        "Staffed for complaints from women about harassment, stalking and violence. Where it isn't run, call 112.",
        setOf(Need.Public, Need.Followed),
    ),
    Service(
        "Ambulance", "108", "Free emergency ambulance.", "State health services", "24×7",
        "A free ambulance in most states. Any hospital must treat a survivor of sexual assault or acid attack free, " +
            "even before a police complaint.",
        setOf(Need.Medical),
    ),
    Service(
        "Legal aid", "15100", "Free advice, and a free lawyer for women and children.", "National Legal Services Authority", "Office hours",
        "Every woman and child is entitled to free legal aid by law, whatever her income. They can help with a complaint, " +
            "a protection order or a court case.",
        setOf(Need.Legal, Need.Home),
    ),
    Service(
        "Tele-MANAS", "14416", "Free, confidential mental-health support in Indian languages.", "Ministry of Health", "24×7",
        "Talk to a trained counsellor about fear, shock or stress after what happened. You don't have to give your name.",
        setOf(Need.Talk),
    ),
)

private sealed interface HelpStep {
    val text: String
    data class Do(override val text: String) : HelpStep
    data class Call(override val text: String, val number: String) : HelpStep
    data class Go(override val text: String, val action: String) : HelpStep
}

private fun guide(n: Need): List<HelpStep> = when (n) {
    Need.Followed -> listOf(
        HelpStep.Go("Head for people and light: a shop, a hospital, a police station.", "map"),
        HelpStep.Go("Share your live location with someone you trust.", "live"),
        HelpStep.Go("Step away with a fake call if you need a reason to leave.", "fake"),
        HelpStep.Call("If they don't stop, call 112.", "112"),
    )
    Need.Public -> listOf(
        HelpStep.Do("Say out loud what they're doing. People step in when it's named."),
        HelpStep.Call("Call the women's police helpline, or 112 if you're in danger.", "1091"),
        HelpStep.Do("Note the time, the place and what they looked like. Record only if it's safe."),
        HelpStep.Go("Mark the spot on the map so other women are warned.", "map"),
        HelpStep.Go("Write it down while it's fresh, to share with the police.", "report"),
    )
    Need.Online -> listOf(
        HelpStep.Do("Don't delete anything. Screenshot messages and profiles with the date showing."),
        HelpStep.Call("Call 1930, or report it at cybercrime.gov.in.", "1930"),
        HelpStep.Do("Block and report the account on the app it came from."),
        HelpStep.Do("Never pay or send more photos to someone threatening you."),
    )
    Need.Home -> listOf(
        HelpStep.Call("If you're in danger right now, call 112.", "112"),
        HelpStep.Call("Call 181: a counsellor can connect you to a One Stop Centre for shelter, medical and legal help.", "181"),
        HelpStep.Go("If calling isn't safe, message the NCW on WhatsApp.", "ncw"),
        HelpStep.Do("Tap Quick exit at the top if someone walks in. Calls you make still show in your phone's call log."),
    )
    Need.Child -> listOf(
        HelpStep.Call("If the child is in danger right now, call 112.", "112"),
        HelpStep.Call("Call 1098 for any child who is lost, hurt, abused or alone.", "1098"),
        HelpStep.Do("Stay with the child somewhere public and visible until help arrives."),
    )
    Need.Medical -> listOf(
        HelpStep.Call("Call 108 for a free ambulance.", "108"),
        HelpStep.Go("See the nearest hospitals on the map.", "map"),
        HelpStep.Do("After a sexual assault, any hospital must treat you free, even before a police complaint."),
    )
    Need.Legal -> listOf(
        HelpStep.Call("Call 15100: women and children get a free lawyer.", "15100"),
        HelpStep.Do("You can file a Zero FIR at any police station, wherever it happened."),
        HelpStep.Go("Write down what happened, to take with you.", "report"),
    )
    Need.Talk -> listOf(
        HelpStep.Call("Call 14416 to talk to a counsellor, free and confidential.", "14416"),
        HelpStep.Call("Or call 181 to talk to a women's counsellor.", "181"),
        HelpStep.Do("What happened isn't your fault, and you don't have to decide anything right now."),
    )
}

internal fun dial(ctx: Context, number: String) =
    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

private fun open(ctx: Context, url: String) =
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Quick exit: the app vanishes to the home screen and drops out of the recent-apps list, so someone glancing at her
 * phone sees nothing. It comes back to recents the next time she opens it.
 */
internal fun quickExit(ctx: Context) {
    runCatching { ctx.getSystemService(ActivityManager::class.java).appTasks.forEach { it.setExcludeFromRecents(true) } }
    runCatching { ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    ctx.activity()?.moveTaskToBack(true)
}

internal fun showInRecents(ctx: Context) =
    runCatching { ctx.getSystemService(ActivityManager::class.java).appTasks.forEach { it.setExcludeFromRecents(false) } }

@Composable
internal fun HelpScreen(onMap: () -> Unit, onFakeCall: () -> Unit, onExit: () -> Unit) {
    val ctx = LocalContext.current
    var need by rememberSaveable { mutableStateOf<Need?>(null) }
    var writing by rememberSaveable { mutableStateOf(false) }
    if (writing) { ComplaintScreen(onClose = { writing = false }, onExit = onExit); return }

    fun act(a: String) = when (a) {
        "map" -> onMap()
        "fake" -> onFakeCall()
        "report" -> writing = true
        "ncw" -> open(ctx, "https://wa.me/917827170170")
        "live" -> {
            val s = LiveShare.session.value ?: LiveShare.start(ctx)
            ShareSheet.text(ctx, "Follow my live location: ${s.url} (updates every 20 s). Via Security4Her", "Send live link")
        }
        else -> {}
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Get help", fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, color = Ink)
                Text("Trusted services and the right next step", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            QuickExitButton { onExit(); quickExit(ctx) }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Mint.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Lock, null, tint = Mint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Private: nothing here is saved or sent. Calls go straight from your phone.", color = Mint, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(22.dp))
        Text("What's happening?", style = MaterialTheme.typography.titleMedium, color = Ink)
        Spacer(Modifier.height(10.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Need.entries.forEach { n ->
                val sel = n == need
                Text(
                    n.label, color = if (sel) OnLilac else Ink, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (sel) Lilac else Surface1)
                        .border(1.dp, if (sel) Lilac else Line, RoundedCornerShape(20.dp))
                        .clickable(onClickLabel = n.label) { need = if (sel) null else n }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        AnimatedVisibility(need != null) {
            need?.let { n -> GuideCard(n, onCall = { dial(ctx, it) }, onAct = ::act) }
        }

        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (need == null) "Trusted helplines" else "Helplines for this", style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f))
            Text("Govt-run · free", color = Muted, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(10.dp))
        val shown = need?.let { n -> Services.filter { n in it.needs } } ?: Services
        shown.forEach { s ->
            ServiceCard(s, onCall = { dial(ctx, s.number) }, onOpen = { url -> open(ctx, url) })
            Spacer(Modifier.height(10.dp))
        }
        if (need != null) {
            Text(
                "Show all ${Services.size} helplines", color = Lilac, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { need = null }.padding(vertical = 8.dp, horizontal = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HelpTile(Modifier.weight(1f), Icons.Filled.Place, "Near me", "Police stations and hospitals", Mint, onMap)
            HelpTile(Modifier.weight(1f), Icons.Filled.Edit, "Write it down", "A complaint, ready to share", Lilac) { writing = true }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun QuickExitButton(onClick: () -> Unit) {
    Chunky(onClick, tone = Tone.Danger, depth = 3.dp, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ExitToApp, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Quick exit", color = Color.White, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun GuideCard(n: Need, onCall: (String) -> Unit, onAct: (String) -> Unit) {
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surface1).border(1.5.dp, Lilac.copy(alpha = 0.5f), RoundedCornerShape(22.dp)).padding(18.dp)) {
        Text("Do this now", color = Lilac, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(10.dp))
        guide(n).forEachIndexed { i, step ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Lilac.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = Lilac, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(step.text, color = Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                when (step) {
                    is HelpStep.Call -> StepButton("Call ${step.number}", SosRed) { onCall(step.number) }
                    is HelpStep.Go -> StepButton(
                        when (step.action) { "map" -> "Map"; "live" -> "Share"; "fake" -> "Fake call"; "report" -> "Write"; "ncw" -> "WhatsApp"; else -> "Open" },
                        Mint,
                    ) { onAct(step.action) }
                    is HelpStep.Do -> {}
                }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, color: Color, onClick: () -> Unit) {
    Spacer(Modifier.width(10.dp))
    Text(
        label, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.14f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ServiceCard(s: Service, onCall: () -> Unit, onOpen: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1).clickable { open = !open }
            .animateContentSize().padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(Mint.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Check, null, tint = Mint, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Verified", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(s.what, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            Chunky(onCall, tone = Tone.Danger, depth = 3.dp, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Call, "Call ${s.name}", tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.number, color = Color.White, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = if (s.number.length > 6) 14.sp else 17.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("${s.by} · ${s.hours}", color = Muted.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        if (open) {
            Spacer(Modifier.height(10.dp))
            Text("What happens when you reach out", color = Lilac, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(s.expect, color = Ink, style = MaterialTheme.typography.bodyMedium)
            if (s.web != null || s.whatsapp != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.whatsapp?.let { w -> StepButtonPlain("WhatsApp", Mint) { onOpen("https://wa.me/$w") } }
                    s.web?.let { u -> StepButtonPlain(u.removePrefix("https://"), Lilac) { onOpen(u) } }
                }
            }
        } else {
            Spacer(Modifier.height(2.dp))
            Text("What happens when you call ›", color = Lilac.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun StepButtonPlain(label: String, color: Color, onClick: () -> Unit) = Text(
    label, color = color, style = MaterialTheme.typography.labelLarge,
    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.14f)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp),
)

@Composable
private fun HelpTile(modifier: Modifier, icon: ImageVector, title: String, sub: String, tint: Color, onClick: () -> Unit) {
    Chunky(onClick, modifier, tone = Tone.Plain, depth = 4.dp, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(sub, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------- Write it down

private val Kinds = listOf("Harassment", "Stalking", "Online abuse", "Violence at home", "A child at risk", "Something else")

/**
 * A complaint she writes privately and shares only if she wants: the facts the police or the NCW ask for, in order.
 * Kept in memory only; leaving the screen or tapping Quick exit forgets it.
 */
@Composable
private fun ComplaintScreen(onClose: () -> Unit, onExit: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val now = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).format(Date()) }
    var kind by rememberSaveable { mutableStateOf(Kinds[0]) }
    var whenAt by rememberSaveable { mutableStateOf(now) }
    var where by rememberSaveable { mutableStateOf("") }
    var what by rememberSaveable { mutableStateOf("") }
    var who by rememberSaveable { mutableStateOf("") }
    var witnesses by rememberSaveable { mutableStateOf("") }
    var proof by rememberSaveable { mutableStateOf("") }

    fun text() = buildString {
        appendLine("COMPLAINT: $kind")
        appendLine()
        appendLine("When: $whenAt")
        appendLine("Where: ${where.ifBlank { "(not given)" }}")
        appendLine()
        appendLine("What happened:")
        appendLine(what.ifBlank { "(not given)" })
        if (who.isNotBlank()) { appendLine(); appendLine("The person involved: $who") }
        if (witnesses.isNotBlank()) { appendLine(); appendLine("Witnesses: $witnesses") }
        if (proof.isNotBlank()) { appendLine(); appendLine("Evidence I have: $proof") }
        appendLine()
        append("Written on ${SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).format(Date())} with Security4Her.")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ArrowBack, "Back", tint = Ink,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(8.dp).size(24.dp),
            )
            Spacer(Modifier.weight(1f))
            QuickExitButton { onExit(); quickExit(ctx) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Write it down", fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, color = Ink)
        Text(
            "The facts the police, the NCW or a lawyer will ask for. It stays on this screen until you share it; nothing is saved.",
            color = Muted, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(18.dp))
        Text("What kind of incident?", style = MaterialTheme.typography.titleSmall, color = Ink)
        Spacer(Modifier.height(8.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Kinds.forEach { k ->
                val sel = k == kind
                Text(
                    k, color = if (sel) OnLilac else Ink, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (sel) Lilac else Surface1)
                        .clickable { kind = k }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        ComplaintField(whenAt, { whenAt = it }, "When did it happen?")
        Spacer(Modifier.height(10.dp))
        ComplaintField(where, { where = it }, "Where? A street, a landmark, a bus number")
        Text(
            "Add where I am now", color = Lilac, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).clickable {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(ctx, "Allow location first.", Toast.LENGTH_SHORT).show(); return@clickable
                }
                @Suppress("MissingPermission")
                LocationServices.getFusedLocationProviderClient(ctx).lastLocation.addOnSuccessListener { l ->
                    if (l == null) Toast.makeText(ctx, "Couldn't find you yet. Type the place instead.", Toast.LENGTH_SHORT).show()
                    else {
                        val pin = "%.5f, %.5f (maps.google.com/?q=%.5f,%.5f)".format(Locale.US, l.latitude, l.longitude, l.latitude, l.longitude)
                        where = if (where.isBlank()) pin else "$where. $pin"
                    }
                }
            }.padding(horizontal = 4.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(10.dp))
        ComplaintField(what, { what = it }, "What happened, in your own words", lines = 5)
        Spacer(Modifier.height(10.dp))
        ComplaintField(who, { who = it }, "The person involved, if you know (name, looks, vehicle)", lines = 2)
        Spacer(Modifier.height(10.dp))
        ComplaintField(witnesses, { witnesses = it }, "Anyone who saw it (optional)")
        Spacer(Modifier.height(10.dp))
        ComplaintField(proof, { proof = it }, "Evidence you have (screenshots, recordings, the SOS locker)", lines = 2)
        Spacer(Modifier.height(18.dp))
        ChunkyButton("Share with police or NCW", icon = Icons.Filled.Share, enabled = what.isNotBlank()) {
            ShareSheet.text(ctx, text(), "Share complaint")
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChunkyButton("File online", Modifier.weight(1f), tone = Tone.Plain, icon = Icons.Filled.Send) {
                open(ctx, if (kind == "Online abuse") "https://cybercrime.gov.in" else "https://www.ncw.gov.in/")
            }
            ChunkyButton("Call 181", Modifier.weight(1f), tone = Tone.Plain, icon = Icons.Filled.Call) { dial(ctx, "181") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.LocationOn, null, tint = Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "You can file at any police station (a Zero FIR), wherever it happened. Online abuse goes to cybercrime.gov.in; " +
                    "everything else can go to the NCW at ncw.gov.in (Register a complaint).",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ComplaintField(value: String, onChange: (String) -> Unit, label: String, lines: Int = 1) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = lines == 1, minLines = lines,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Lilac, unfocusedBorderColor = Line, focusedLabelColor = Lilac, unfocusedLabelColor = Muted,
            cursorColor = Lilac, focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
        ),
    )
}
