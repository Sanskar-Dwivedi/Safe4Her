package `in`.security4her.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.security4her.app.core.Backend
import `in`.security4her.app.core.Settings
import `in`.security4her.app.sos.Destination
import `in`.security4her.app.sos.Fix
import `in`.security4her.app.sos.Journey
import `in`.security4her.app.sos.JourneyState
import `in`.security4her.app.sos.Locator
import `in`.security4her.app.sos.RouteEstimate
import kotlinx.coroutines.delay
import java.util.Locale

private fun distanceText(m: Double) = if (m < 1000) "${(m / 10).toInt() * 10} m" else "%.1f km".format(Locale.US, m / 1000)

// ---------------------------------------------------------------- Home card

@Composable
fun JourneyHomeCard(onStart: () -> Unit) {
    val ctx = LocalContext.current
    val s by Journey.state.collectAsStateWithLifecycle()
    val j = s

    if (j != null && j.running) ActiveJourneyCard(j)
    else Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1)
            .border(1.dp, Lilac.copy(alpha = 0.28f), RoundedCornerShape(20.dp)).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Safe Journey", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Tell us where you're going. If you're late, stop moving, or head the wrong way, we check on you. No answer, and your contacts get an SOS.",
            color = Muted, style = MaterialTheme.typography.bodyMedium,
        )
        j?.let { last ->
            if (System.currentTimeMillis() - (last.endedAt ?: 0) < 30 * 60_000) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (last.status == "alerted") SosRed else Lilac))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (last.status) {
                            "arrived" -> "Reached ${last.dest.name} at ${Journey.hhmm(last.endedAt ?: 0)}"
                            "alerted" -> "Your contacts were alerted at ${Journey.hhmm(last.endedAt ?: 0)}"
                            else -> "Journey to ${last.dest.name} ended"
                        },
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    Text("Clear", color = Muted, fontSize = 12.sp, modifier = Modifier.clickable { Journey.dismiss() }.padding(4.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(containerColor = Lilac, contentColor = OnLilac),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Start a journey", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ActiveJourneyCard(s: JourneyState) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirmEnd by remember { mutableStateOf(false) }
    LaunchedEffect(s.id) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val leftMin = ((s.deadline - now) / 60_000.0)
    val late = leftMin < 0
    val progress = s.remainingM?.let { (1 - it / (s.startM ?: s.totalM).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) } ?: 0f
    val stillMin = s.stillSince?.let { ((now - it) / 60_000).toInt() } ?: 0
    val stillLimit = (Journey.stillLimitMs(ctx) / 60_000).toInt().coerceAtLeast(1)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1)
            .border(1.dp, (if (late) Amber else Lilac).copy(alpha = 0.45f), RoundedCornerShape(20.dp)).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("On the way to", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(s.dest.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Journey.hhmm(s.deadline), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(10.dp))
            Text(
                if (late) "${(-leftMin).toInt().coerceAtLeast(1)} min late" else "arrive by · ${ceilMin(leftMin)} min left",
                color = if (late) Amber else Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Lilac, trackColor = Surface2, drawStopIndicator = {},
        )
        Spacer(Modifier.height(8.dp))
        Text(
            buildString {
                append(s.remainingM?.let { "${distanceText(it)} to go" } ?: "Finding you…")
                if (s.remainingM != null) append(if (stillMin >= 1) " · stopped for $stillMin min" else " · moving")
            },
            color = if (stillMin * 2 >= stillLimit) Amber else Muted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { Journey.arrived(ctx) },
                colors = ButtonDefaults.buttonColors(containerColor = Lilac, contentColor = OnLilac), shape = RoundedCornerShape(14.dp),
            ) { Text("I've arrived", fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = { Journey.addTime(ctx, 10) }, border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(14.dp)) {
                Text("+10 min", color = Ink)
            }
            TextButton(onClick = { confirmEnd = true }) { Text("End", color = Muted) }
        }
    }

    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        containerColor = Surface1,
        title = { Text("End this journey?") },
        text = { Text("We'll stop watching your trip to ${s.dest.name}. Your contacts won't be told.", color = Muted) },
        confirmButton = { TextButton(onClick = { confirmEnd = false; Journey.end(ctx) }) { Text("End journey", color = SosRed) } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going", color = Muted) } },
    )
}

private fun ceilMin(m: Double) = kotlin.math.ceil(m).toInt().coerceAtLeast(0)

// ---------------------------------------------------------------- Setup

@Composable
fun JourneySetupScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val test = remember { Settings.load(ctx).testMode }
    var here by remember { mutableStateOf<Fix?>(null) }
    var locating by remember { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Destination>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var dest by remember { mutableStateOf<Destination?>(null) }
    var mode by rememberSaveable { mutableStateOf("walk") }
    var est by remember { mutableStateOf<RouteEstimate?>(null) }
    var allow by remember { mutableIntStateOf(0) }
    var tell by remember { mutableStateOf(Journey.tellOnStart(ctx)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val recents = remember { Journey.recents(ctx) }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        MapPickerScreen(start = here, onClose = { picking = false }) { dest = it; picking = false }
        return
    }

    LaunchedEffect(Unit) {
        here = Locator.fresh(ctx, waitMs = 10_000) ?: Locator.last(ctx)
        locating = false
    }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    LaunchedEffect(query) {
        searchError = null
        if (query.trim().length < 3) { results = emptyList(); return@LaunchedEffect }
        delay(600)
        searching = true
        results = try { Journey.search(query.trim(), here) } catch (e: Exception) {
            searchError = "Couldn't search. Check your internet and try again."
            emptyList()
        }
        searching = false
    }
    LaunchedEffect(dest, mode, here) {
        val d = dest ?: return@LaunchedEffect
        val h = here ?: return@LaunchedEffect
        est = null
        val e = Journey.route(h, d, mode)
        est = e
        allow = Journey.allowedMinutes(e.minutes)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹  Back", color = Lilac) }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Safe Journey", style = MaterialTheme.typography.headlineSmall)
            Text("Where are you going?", color = Muted, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))

            val d = dest
            if (d == null) {
                Field(query, { query = it }, "Search a place, area or address")
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1)
                        .border(1.dp, Lilac.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).clickable { picking = true }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Place, contentDescription = null, tint = Lilac, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Pick on the map", fontWeight = FontWeight.SemiBold)
                        Text("For places search can't find: drop a pin exactly where you're going.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("›", color = Lilac, fontSize = 24.sp)
                }
                Spacer(Modifier.height(8.dp))
                when {
                    searching -> Text("Searching…", color = Muted, style = MaterialTheme.typography.bodySmall)
                    searchError != null -> Text(searchError!!, color = SosRed, style = MaterialTheme.typography.bodySmall)
                    query.trim().length >= 3 && results.isEmpty() -> Text(
                        "No places found. Try a nearby landmark, or pick it on the map.", color = Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                val list = if (query.trim().length >= 3) results else recents
                if (query.trim().length < 3 && recents.isNotEmpty()) {
                    Text("Recent places", color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
                list.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(Surface1)
                            .clickable { dest = p }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.detail.isNotBlank()) Text(p.detail, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        here?.let { h -> Text(distanceText(Journey.meters(h.lat, h.lng, p.lat, p.lng)), color = Muted, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (query.isBlank() && recents.isEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Type where you're headed: home, a friend's area, a station. We'll work out how long it should take.",
                        color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (d.detail.isNotBlank()) Text(d.detail, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    TextButton(onClick = { dest = null; est = null }) { Text("Change", color = Lilac) }
                }
                Spacer(Modifier.height(16.dp))
                Text("How are you travelling?", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Journey.modes.forEach { m ->
                        val sel = m.key == mode
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) Lilac else Surface1)
                                .clickable { mode = m.key }.padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(m.label, color = if (sel) OnLilac else Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1).padding(18.dp)) {
                    val e = est
                    when {
                        locating -> Text("Finding where you are…", color = Muted)
                        here == null -> Text("We can't find your location. Turn on location and try again.", color = Amber)
                        e == null -> Text("Working out your route…", color = Muted)
                        else -> {
                            Text(
                                "${distanceText(e.distanceM)} · about ${e.minutes} min ${Journey.label(mode).let { if (it == "walking") "on foot" else "by $it" }}",
                                color = Muted, style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!e.fromRoute) Text("Rough estimate: the route service didn't answer.", color = Amber, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(10.dp))
                            Text("Check on me if I'm not there by", style = MaterialTheme.typography.bodyMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Journey.hhmm(now + allow * 60_000L), style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                                StepButton("−") { allow = (allow - 5).coerceAtLeast(if (test) 1 else 5) }
                                Spacer(Modifier.width(8.dp))
                                StepButton("+") { allow += 5 }
                            }
                            Text("$allow min from now, with some time to spare.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                val still = Journey.stillLimitMs(ctx) / 60_000
                val window = Journey.checkWindowMs(ctx) / 1000
                Text(
                    "We ask \"Are you OK?\" if you're late, stop moving for $still min, or head away from ${d.name}. " +
                        "If you don't answer within ${if (window >= 60) "${window / 60} min" else "$window s"}, your contacts get an SOS with your location." +
                        if (test) " Test Mode shortens these timers." else "",
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Text my contacts that I've left")
                        Text("They get the arrive-by time and a live map link.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = tell, onCheckedChange = { tell = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Lilac, checkedThumbColor = OnLilac, uncheckedTrackColor = Surface2, uncheckedBorderColor = Line),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        val ready = dest != null && est != null && here != null
        Box(Modifier.fillMaxWidth().padding(20.dp)) {
            PrimaryButton("Start journey", enabled = ready) {
                Journey.start(ctx, dest!!, mode, est!!, allow, tell)
                onClose()
            }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 22.sp, color = Ink) }
}

// ---------------------------------------------------------------- Check-in

@Composable
fun CheckInScreen(s: JourneyState) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var names by remember { mutableStateOf("your contacts") }
    var askPin by remember { mutableStateOf(false) }
    BackHandler {}
    LaunchedEffect(s.checkUntil) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    LaunchedEffect(Unit) {
        runCatching { Backend.contacts().filter { it.receivesSos }.map { it.name } }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { names = if (it.size == 1) it[0] else it.dropLast(1).joinToString(", ") + " and " + it.last() }
    }
    val window = Journey.checkWindowMs(ctx).toFloat()
    val leftMs = ((s.checkUntil ?: now) - now).coerceAtLeast(0)
    val frac = (leftMs / window).coerceIn(0f, 1f)
    val secs = kotlin.math.ceil(leftMs / 1000.0).toInt()
    fun ok() { if (Settings.load(ctx).pin.isBlank()) Journey.respondOk(ctx) else askPin = true }

    Column(
        Modifier.fillMaxSize().background(Night).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Safe Journey check-in", color = Amber, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 10.dp.toPx()
                drawCircle(Amber.copy(alpha = 0.15f), radius = size.minDimension / 2 - w / 2, style = Stroke(w))
                drawArc(Amber, -90f, 360f * frac, false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.displaySmall)
                Text("to answer", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("Are you OK?", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            when (s.checkReason) {
                "late" -> "You were expected at ${s.dest.name} by ${Journey.hhmm(s.deadline)}."
                "stuck" -> "You haven't moved for a while on the way to ${s.dest.name}."
                "away" -> "You seem to be heading away from ${s.dest.name}."
                else -> "Checking in on your journey to ${s.dest.name}."
            },
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "If you don't answer, $names get an SOS with your location.",
            color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton(if (s.checkReason == "late") "I'm OK, give me 10 more minutes" else "I'm OK", onClick = { ok() })
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { Journey.arrived(ctx) }, modifier = Modifier.fillMaxWidth().height(52.dp),
            border = BorderStroke(1.dp, Line), shape = RoundedCornerShape(14.dp),
        ) { Text("I've arrived", color = Ink) }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { Journey.alert(now = true) }, modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White), shape = RoundedCornerShape(14.dp),
        ) { Text("I need help: send SOS now", fontWeight = FontWeight.SemiBold) }
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
                    if (pin == Settings.load(ctx).pin) { askPin = false; Journey.respondOk(ctx) } else wrong = true
                }) { Text("I'm OK", color = Lilac) }
            },
            dismissButton = { TextButton(onClick = { askPin = false }) { Text("Back", color = Muted) } },
        )
    }
}

// ---------------------------------------------------------------- Pick on the map

@Composable
private fun MapPickerScreen(start: Fix?, onClose: () -> Unit, onPick: (Destination) -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    val home = remember { start?.let { GeoPoint(it.lat, it.lng) } ?: GeoPoint(21.1458, 79.0882) }
    var center by remember { mutableStateOf(home) }
    var place by remember { mutableStateOf<Destination?>(null) }
    var looking by remember { mutableStateOf(true) }

    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = java.io.File(ctx.cacheDir, "osm")
            osmdroidTileCache = java.io.File(ctx.cacheDir, "osm/tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
            overlayManager.tilesOverlay.setColorFilter(darkTiles())
            overlayManager.tilesOverlay.loadingBackgroundColor = 0xFF16101E.toInt()
            overlayManager.tilesOverlay.loadingLineColor = 0xFF221A2C.toInt()
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 4.0
            controller.setZoom(16.5)
            controller.setCenter(home)
            if (start != null) overlays += Marker(this).apply {
                position = home
                icon = dot(ctx, Color(0xFFFF4D5E), 36, halo = true)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
            }
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    mapCenter.let { center = GeoPoint(it.latitude, it.longitude) }
                    return true
                }
                override fun onZoom(event: ZoomEvent?): Boolean = true
            })
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) map.onResume()
            if (e == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose { lifecycle.removeObserver(obs); map.onPause() }
    }
    // Look up the address once the map stops moving.
    LaunchedEffect(center) {
        looking = true
        delay(700)
        place = Journey.reverse(center.latitude, center.longitude)
        looking = false
    }

    Box(Modifier.fillMaxSize().background(Night)) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            AndroidView(factory = { map }, modifier = Modifier.fillMaxSize().clipToBounds())
            // The pin: its tip marks the centre of the map.
            Canvas(Modifier.align(Alignment.Center).offset(y = (-21).dp).size(width = 30.dp, height = 42.dp)) {
                val r = size.width / 2
                drawOval(Color.Black.copy(alpha = 0.35f), topLeft = Offset(r - 7.dp.toPx(), size.height - 4.dp.toPx()), size = Size(14.dp.toPx(), 5.dp.toPx()))
                drawLine(Lilac, Offset(r, r * 1.6f), Offset(r, size.height - 2.dp.toPx()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Lilac, radius = r, center = Offset(r, r))
                drawCircle(OnLilac, radius = r * 0.38f, center = Offset(r, r))
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(Surface1.copy(alpha = 0.95f)).clickable(onClick = onClose)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) { Text("‹  Back", color = Lilac, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Surface1.copy(alpha = 0.95f)).padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text("Drag the map to place the pin", style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(Modifier.align(Alignment.CenterEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerButton("+") { map.controller.zoomIn() }
                PickerButton("−") { map.controller.zoomOut() }
                if (start != null) PickerButton("◎") { map.controller.animateTo(home, 17.0, 500L) }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Surface1).padding(20.dp),
        ) {
            Text("Going to", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(
                if (looking || place == null) "Finding the address…" else place!!.name,
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (looking || place == null) "%.5f, %.5f".format(Locale.US, center.latitude, center.longitude) else place!!.detail.ifBlank { "%.5f, %.5f".format(Locale.US, center.latitude, center.longitude) },
                color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            start?.let { s ->
                Text(
                    "${distanceText(Journey.meters(s.lat, s.lng, center.latitude, center.longitude))} from you",
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Go here", enabled = !looking) {
                val p = place
                onPick(
                    if (p != null && Journey.meters(p.lat, p.lng, center.latitude, center.longitude) < 5) p
                    else Destination("Pinned place", "%.5f, %.5f".format(Locale.US, center.latitude, center.longitude), center.latitude, center.longitude),
                )
            }
        }
    }
}

@Composable
private fun PickerButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Surface1.copy(alpha = 0.95f)).border(1.dp, Line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Ink, fontSize = 20.sp) }
}

// ---------------------------------------------------------------- Settings

@Composable
fun SafeJourneySection() {
    val ctx = LocalContext.current
    var tellStart by remember { mutableStateOf(Journey.tellOnStart(ctx)) }
    var tellArrive by remember { mutableStateOf(Journey.tellOnArrive(ctx)) }
    val colors = SwitchDefaults.colors(checkedTrackColor = Lilac, checkedThumbColor = OnLilac, uncheckedTrackColor = Surface2, uncheckedBorderColor = Line)
    Section("Safe Journey", "Who hears when a journey starts and ends") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Text contacts when I start a journey", modifier = Modifier.weight(1f))
            Switch(checked = tellStart, onCheckedChange = { tellStart = it; Journey.setTellOnStart(ctx, it) }, colors = colors)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Text contacts when I arrive", modifier = Modifier.weight(1f))
            Switch(checked = tellArrive, onCheckedChange = { tellArrive = it; Journey.setTellOnArrive(ctx, it) }, colors = colors)
        }
    }
}
