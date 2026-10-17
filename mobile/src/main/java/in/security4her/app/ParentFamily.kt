package `in`.security4her.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import `in`.security4her.app.core.AppLog
import `in`.security4her.app.core.Store
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------- My family (a grown-up's own app)
// A parent who uses Security4Her connects to their child inside the app: scan the QR code on the child's Family tab
// (or tap the family link in a text, which opens here). The child's screen is a live feed: SOS, calls, check-ins and
// the child's position on a map, streamed as they happen, with the button that stops a false alarm.

/** The children this phone follows: their family-link key, name, and what they call this grown-up. */
object Children {
    data class Child(val key: String, val name: String, val me: String, val seenAt: Long = 0)

    fun list(ctx: Context): List<Child> = runCatching {
        val a = JSONArray(Store.prefs(ctx).getString("children", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Child(o.getString("k"), o.getString("n"), o.getString("me"), o.optLong("seen")) } }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, l: List<Child>) = Store.prefs(ctx).edit().putString(
        "children", JSONArray().apply { l.forEach { put(JSONObject().put("k", it.key).put("n", it.name).put("me", it.me).put("seen", it.seenAt)) } }.toString(),
    ).apply()

    fun add(ctx: Context, key: String, name: String, me: String) {
        save(ctx, list(ctx).filter { it.key != key } + Child(key, name, me))
        AppLog.ok("Family", "Now following $name as $me")
        FamilyAlerts.schedule(ctx)
    }

    fun update(ctx: Context, key: String, f: (Child) -> Child) = save(ctx, list(ctx).map { if (it.key == key) f(it) else it })
    fun remove(ctx: Context, key: String) = save(ctx, list(ctx).filter { it.key != key })
}

/** SOS alerts for followed children: live while the app runs, and a check every 15 minutes when it doesn't. */
object FamilyAlerts {
    private const val CHANNEL = "family"

    fun schedule(ctx: Context) {
        if (Children.list(ctx).isEmpty()) return
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "family-alerts", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<FamilyWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    /** Shows an alert for an SOS (or "safe") the phone hasn't announced yet. */
    fun handle(ctx: Context, c: Children.Child, e: Family.Event) {
        if (e.kind != "sos" && e.kind != "safe") return
        val p = Store.prefs(ctx)
        val key = "family_alerted_${c.key.take(10)}"
        if (e.at <= p.getLong(key, 0)) return
        p.edit().putLong(key, e.at).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Family alerts", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            ctx, c.key.hashCode(), Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_CHILD, c.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sos = e.kind == "sos"
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_security4her)
            .setContentTitle(if (sos) "${c.name} pressed SOS" else "${c.name} is safe")
            .setContentText(if (sos) "Tap to see where ${c.name} is, live" else e.text)
            .setPriority(if (sos) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (sos) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open).setAutoCancel(true)
            .build()
        runCatching { nm.notify(c.key.hashCode(), n) }
        AppLog.warn("Family", if (sos) "${c.name} pressed SOS" else "${c.name} is safe")
    }

    const val EXTRA_CHILD = "in.security4her.app.FAMILY_CHILD"
    val openChild = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

class FamilyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Children.list(applicationContext).forEach { c ->
            Family.history(Family.keyFrom(c.key), "20m").lastOrNull { it.kind == "sos" || it.kind == "safe" }
                ?.let { FamilyAlerts.handle(applicationContext, c, it) }
        }
        return Result.success()
    }
}

private fun ago(t: Long): String {
    val s = (System.currentTimeMillis() - t) / 1000
    return when { s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86_400 -> "${s / 3600} h ago"; else -> "${s / 86_400} d ago" }
}
private fun clock(t: Long) = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(t))

/** SOS active if the latest sos/safe event is an sos. */
private fun sosOn(events: List<Family.Event>) = events.lastOrNull { it.kind == "sos" || it.kind == "safe" }?.kind == "sos"

@Composable
internal fun MyFamilyScreen(myName: String, onClose: () -> Unit, openChild: String? = null) {
    val ctx = LocalContext.current
    var children by remember { mutableStateOf(Children.list(ctx)) }
    var viewing by remember { mutableStateOf(openChild) }
    var adding by remember { mutableStateOf<String?>(null) } // "scan" | "paste" | a link to confirm
    val pending by Family.pendingLink.collectAsState()
    LaunchedEffect(pending) { pending?.let { adding = it; Family.pendingLink.value = null } }

    viewing?.let { k ->
        val c = children.firstOrNull { it.key == k }
        if (c != null) { ChildLiveScreen(c, onBack = { viewing = null; children = Children.list(ctx) }); return }
    }
    when (val a = adding) {
        null -> {}
        "scan" -> { QrScanScreen(onCode = { adding = it }, onPaste = { adding = "paste" }, onBack = { adding = null }); return }
        else -> {
            ConnectChildScreen(a, myName, onDone = { key -> children = Children.list(ctx); adding = null; viewing = key }, onBack = { adding = null })
            return
        }
    }
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Icon(Icons.Filled.ArrowBack, "Back", tint = Ink, modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(8.dp).size(24.dp))
        Text("My family", fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, color = Ink)
        Text("Follow your children live: their SOS, calls, check-ins and where they are.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(18.dp))
        if (children.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surface1).padding(20.dp)) {
                Text("No one yet", style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(
                    "On your child's phone, open Security4Her → Family → Show QR code, then scan it here. " +
                        "Or tap the family link your child texted you.",
                    color = Muted, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        children.forEach { c -> ChildRow(c) { viewing = c.key } }
        Spacer(Modifier.height(14.dp))
        ChunkyButton("Scan my child's QR code", icon = Icons.Filled.Add) { adding = "scan" }
        TextButton(onClick = { adding = "paste" }) { Text("Or paste a family link", color = Lilac) }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Mint.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = Mint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("End-to-end encrypted. Only phones with the family link can read it.", color = Mint, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ChildRow(c: Children.Child, onOpen: () -> Unit) {
    var events by remember { mutableStateOf<List<Family.Event>?>(null) }
    LaunchedEffect(c.key) { events = Family.history(Family.keyFrom(c.key)) }
    val sos = events?.let(::sosOn) == true
    val last = events?.lastOrNull { it.kind != "loc" }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(22.dp))
            .background(if (sos) Brush.verticalGradient(listOf(Color(0xFFFF5A69), Color(0xFFCF2744))) else Brush.verticalGradient(listOf(Surface1, Surface1)))
            .clickable(onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.name, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, color = if (sos) Color.White else Ink)
            Text(
                when {
                    events == null -> "Checking…"
                    sos -> "SOS active · tap to see live"
                    last != null -> "${last.text} · ${ago(last.at)}"
                    else -> "No news in the last 12 hours"
                },
                color = if (sos) Color.White else Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("›", color = if (sos) Color.White else Muted, fontSize = 26.sp)
    }
}

/** Confirms a scanned or tapped family link, asks what the child calls you, then says hello to the child's phone. */
@Composable
private fun ConnectChildScreen(input: String, myName: String, onDone: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(if (input == "paste") "" else input) }
    var me by remember { mutableStateOf(myName.substringBefore(' ')) }
    var name by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val key = Family.parse(link)
    LaunchedEffect(link) {
        name = null
        Family.parse(link)?.let { s -> name = Family.history(s).lastOrNull()?.name ?: "Your child" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Icon(Icons.Filled.ArrowBack, "Back", tint = Ink, modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(8.dp).size(24.dp))
        Spacer(Modifier.height(16.dp))
        Text("Connect to your child", fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = Ink)
        Spacer(Modifier.height(16.dp))
        if (input == "paste") {
            Field(link, { link = it.trim(); error = null }, "Family link")
            Spacer(Modifier.height(12.dp))
        }
        if (key == null) {
            Text(if (link.isBlank()) "Paste the link your child texted you." else "That isn't a family link.", color = if (link.isBlank()) Muted else SosRed, style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(name ?: "?", 48.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(name ?: "Checking…", style = MaterialTheme.typography.titleMedium, color = Ink)
                    Text("Family link found", color = Mint, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            Field(me, { me = it.take(30) }, "What does ${name ?: "your child"} call you? (e.g. Mum)")
            error?.let { Text(it, color = SosRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(18.dp))
            ChunkyButton("Connect", busy = busy, enabled = me.isNotBlank()) {
                busy = true
                scope.launch {
                    val k = Family.keyOf(key)
                    Children.add(ctx, k, name ?: "Your child", me.trim())
                    if (!Family.send(key, "hello", me.trim())) error = "Saved, but couldn't reach ${name ?: "your child"}'s phone yet. It connects when you're online."
                    busy = false
                    onDone(k)
                }
            }
        }
    }
}

/** The live feed: status, map, and every event as it happens. */
@Composable
private fun ChildLiveScreen(c: Children.Child, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = remember(c.key) { Family.keyFrom(c.key) }
    var events by remember { mutableStateOf(listOf<Family.Event>()) }
    var live by remember { mutableStateOf(false) }
    var stopSent by remember { mutableStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.key) {
        events = Family.history(key)
        Family.send(key, "hello", c.me) // the child's Family tab shows this grown-up as connected
        live = true
        Family.live(key).collect { e ->
            if (events.none { it.id == e.id }) events = (events + e).sortedBy { it.at }
            FamilyAlerts.handle(ctx, c, e)
        }
    }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(15_000); now = System.currentTimeMillis() } }
    val sos = sosOn(events)
    val sosAt = events.lastOrNull { it.kind == "sos" }?.at ?: 0
    val loc = events.lastOrNull { it.lat != null }
    val last = events.lastOrNull()
    val beat = rememberInfiniteTransition(label = "live")
    val dot by beat.animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dot")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.fillMaxWidth().background(
                if (sos) Brush.verticalGradient(listOf(Color(0xFFFF5A69), Color(0xFFCF2744))) else Brush.verticalGradient(listOf(Night, Night)),
            ).padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = if (sos) Color.White else Ink, modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(8.dp).size(24.dp))
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background((if (sos) Color.White else Mint).copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background((if (sos) Color.White else Mint).copy(alpha = if (live) dot else 0.3f)))
                    Spacer(Modifier.width(6.dp))
                    Text(if (live) "LIVE" else "Connecting…", color = if (sos) Color.White else Mint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            Text(c.name, color = if (sos) Color.White.copy(alpha = 0.9f) else Muted, style = MaterialTheme.typography.titleMedium)
            Text(
                when { sos -> "SOS active"; last?.kind == "safe" -> "Safe now"; last != null -> "All OK"; else -> "No news yet" },
                fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp,
                color = when { sos -> Color.White; last != null -> Mint; else -> Ink },
            )
            Text(
                when {
                    sos -> "Started ${clock(sosAt)} · ${ago(sosAt)}"
                    last != null -> "Last update ${ago(last.at)}: ${last.text.ifBlank { "location" }}"
                    else -> "You'll see SOS alerts and check-ins here as they happen."
                },
                color = if (sos) Color.White.copy(alpha = 0.9f) else Muted, style = MaterialTheme.typography.bodyMedium,
            )
            if (sos) {
                Spacer(Modifier.height(14.dp))
                if (stopSent > sosAt) {
                    Text("Stop sent. ${c.name}'s phone ends the SOS within a few seconds.", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                } else {
                    Chunky(
                        onClick = {
                            scope.launch {
                                if (Family.send(key, "stop", c.me)) { stopSent = System.currentTimeMillis(); AppLog.info("Family", "Stopped ${c.name}'s SOS as a false alarm") }
                                else Toast.makeText(ctx, "Couldn't send. Check your internet.", Toast.LENGTH_LONG).show()
                            }
                        },
                        Modifier.fillMaxWidth(), face = Color.White, edge = Color(0xFF9C9C9C), depth = 4.dp, shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(
                            "Stop the SOS · it's a false alarm", color = RedDeep, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
                            modifier = Modifier.padding(vertical = 15.dp),
                        )
                    }
                    Text("Only if you're sure ${c.name} is safe. Calls to the other grown-ups stop too.", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Column(Modifier.padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(14.dp))
            if (loc != null) {
                ChildMap(loc)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChunkyButton("Directions", Modifier.weight(1f), tone = Tone.Plain, icon = Icons.Filled.Place) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${loc.lat},${loc.lng}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${loc.lat},${loc.lng}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    ChunkyButton("Call 112", Modifier.weight(0.8f), tone = Tone.Danger, icon = Icons.Filled.Call) { dial(ctx, "112") }
                }
                Text("Position from ${ago(loc.at)}", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("Live feed", style = MaterialTheme.typography.titleMedium, color = Ink)
            Spacer(Modifier.height(8.dp))
            val feed = events.filter { it.kind != "loc" }.reversed()
            if (feed.isEmpty()) Text("Nothing in the last 12 hours. New events appear here the moment they happen.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1)) {
                feed.take(40).forEachIndexed { i, e ->
                    val (icon, tint) = when (e.kind) {
                        "sos" -> "🚨" to SosRed; "call" -> "📞" to Amber; "checkin" -> "✅" to Mint; "safe" -> "💚" to Mint; else -> "•" to Muted
                    }
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Text(icon, fontSize = 16.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.text.ifBlank { e.kind }, color = Ink, style = MaterialTheme.typography.bodyLarge)
                            Text("${clock(e.at)} · ${ago(e.at)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (i < feed.size - 1 && i < 39) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(Line))
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { Children.remove(ctx, c.key); onBack() }) { Text("Stop following ${c.name}", color = Muted) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** A small live map with the child's pin, recentring as new positions arrive. */
@Composable
private fun ChildMap(loc: Family.Event) {
    val ctx = LocalContext.current
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.filesDir, "osm")
            osmdroidTileCache = File(ctx.filesDir, "osm/tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
            overlayManager.tilesOverlay.setColorFilter(darkTiles())
            overlayManager.tilesOverlay.loadingBackgroundColor = Night.toArgb()
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.0)
        }
    }
    val pin = remember { Marker(map).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Here" } }
    LaunchedEffect(loc.lat, loc.lng) {
        val p = GeoPoint(loc.lat!!, loc.lng!!)
        pin.position = p
        if (pin !in map.overlays) map.overlays += pin
        map.controller.animateTo(p)
        map.invalidate()
    }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause() } }
    AndroidView({ map }, Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, Line, RoundedCornerShape(20.dp)))
}

/** The phone's own camera, reading the QR code on the child's Family tab. */
@Composable
private fun QrScanScreen(onCode: (String) -> Unit, onPaste: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var done by remember { mutableStateOf(false) }
    val hasCamera = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (!hasCamera) ask.launch(android.Manifest.permission.CAMERA) }
    val provider = remember { ProcessCameraProvider.getInstance(ctx) }
    DisposableEffect(Unit) { onDispose { runCatching { provider.get().unbindAll() } } }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Icon(Icons.Filled.ArrowBack, "Back", tint = Ink, modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(8.dp).size(24.dp))
        Text("Scan the QR code", fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = Ink)
        Text("On your child's phone: Security4Her → Family → Show QR code.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        AndroidView(
            factory = { c ->
                PreviewView(c).also { pv ->
                    provider.addListener({
                        val cam = provider.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                        val reader = QRCodeReader()
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { img ->
                            val y = img.planes[0]
                            val buf = y.buffer
                            val data = ByteArray(buf.remaining()).also { buf.get(it) }
                            val src = PlanarYUVLuminanceSource(data, y.rowStride, img.height, 0, 0, img.width, img.height, false)
                            val text = runCatching { reader.decode(BinaryBitmap(HybridBinarizer(src))).text }.getOrNull()
                            img.close()
                            if (text != null && Family.parse(text) != null && !done) { done = true; pv.post { onCode(text) } }
                        }
                        runCatching { cam.unbindAll(); cam.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                            .onFailure { AppLog.warn("Family", "Camera didn't start for the QR scan", it) }
                    }, ContextCompat.getMainExecutor(c))
                }
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).border(2.dp, Lilac, RoundedCornerShape(24.dp)),
        )
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onPaste) { Text("No camera? Paste the family link instead", color = Lilac) }
    }
}
