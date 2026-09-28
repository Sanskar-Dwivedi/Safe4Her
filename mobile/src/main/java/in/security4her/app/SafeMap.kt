package `in`.security4her.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import `in`.security4her.app.core.Backend
import `in`.security4her.app.map.MapRepository
import `in`.security4her.app.map.ReportCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val NAGPUR = GeoPoint(21.1458, 79.0882) // demo city, used until we know where she is

data class Place(val id: String, val name: String, val kind: String, val lat: Double, val lng: Double, val phone: String?, val distanceM: Double)

private val kindColor = mapOf(
    "police" to Color(0xFF7FB2FF),
    "hospital" to Color(0xFF5EE0A0),
    "clinic" to Color(0xFF5EE0A0),
    "pharmacy" to Color(0xFFFFD166),
    "fire_station" to Color(0xFFFF9E64),
)

private fun kindLabel(k: String) = when (k) {
    "police" -> "Police"
    "hospital" -> "Hospital"
    "clinic" -> "Clinic"
    "pharmacy" -> "Pharmacy"
    "fire_station" -> "Fire station"
    else -> "Safe place"
}

private fun meters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLng = Math.toRadians(bLng - aLng)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

private fun distanceText(m: Double) = if (m < 1000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(Locale.US, m / 1000)

/** Nearby safe places: our Supabase list when configured, otherwise live OpenStreetMap data (no key needed). */
object Places {
    private val endpoints = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

    suspend fun near(lat: Double, lng: Double): List<Place> = withContext(Dispatchers.IO) {
        if (!Backend.mock) {
            runCatching { MapRepository(Backend.client).nearestSafePlaces(lat, lng, 30) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { list -> return@withContext list.map { Place(it.id.toString(), it.name, it.category, it.lat, it.lng, it.phone, it.distanceM) } }
        }
        runCatching { nominatim(lat, lng) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { overpass(lat, lng) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: fallback(lat, lng)
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Security4Her/0.1 (hackathon safety app)")
        }
        if (c.responseCode != 200) error("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** OpenStreetMap's search API; one query per kind, spaced 1 s apart per its usage policy. */
    private fun nominatim(lat: Double, lng: Double): List<Place> {
        val box = "%.5f,%.5f,%.5f,%.5f".format(Locale.US, lng - 0.05, lat + 0.045, lng + 0.05, lat - 0.045)
        val out = mutableListOf<Place>()
        listOf("police", "hospital", "pharmacy").forEachIndexed { i, k ->
            if (i > 0) Thread.sleep(1_100)
            val body = get("https://nominatim.openstreetmap.org/search?q=$k&format=jsonv2&viewbox=$box&bounded=1&limit=20&extratags=1")
            Json.parseToJsonElement(body).jsonArray.forEach { el ->
                val o = el.jsonObject
                if (o["category"]?.jsonPrimitive?.content != "amenity") return@forEach
                val kind = o["type"]?.jsonPrimitive?.content ?: return@forEach
                val pLat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@forEach
                val pLng = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@forEach
                val name = o["name"]?.jsonPrimitive?.content?.ifBlank { null } ?: return@forEach
                val tags = o["extratags"] as? JsonObject
                val phone = (tags?.get("phone") ?: tags?.get("contact:phone"))?.jsonPrimitive?.content
                out += Place("nm-" + (o["osm_id"]?.jsonPrimitive?.content ?: name), name, kind, pLat, pLng, phone, meters(lat, lng, pLat, pLng))
            }
        }
        return out.distinctBy { it.name + it.kind }.sortedBy { it.distanceM }.take(40)
    }

    // MOCK: last resort when every OSM server is unreachable, a few real Nagpur landmarks (only shown near Nagpur).
    private fun fallback(lat: Double, lng: Double): List<Place> {
        if (meters(lat, lng, NAGPUR.latitude, NAGPUR.longitude) > 30_000) return emptyList()
        return listOf(
            Triple("Sitabuldi Police Station", "police", 21.1466 to 79.0849),
            Triple("Dhantoli Police Station", "police", 21.1352 to 79.0874),
            Triple("Government Medical College & Hospital", "hospital", 21.1336 to 79.0940),
            Triple("Mayo Hospital (IGGMC)", "hospital", 21.1530 to 79.0968),
            Triple("Sadar Police Station", "police", 21.1600 to 79.0800),
        ).map { (n, k, ll) -> Place("fb-$n", n, k, ll.first, ll.second, null, meters(lat, lng, ll.first, ll.second)) }.sortedBy { it.distanceM }
    }

    private fun overpass(lat: Double, lng: Double): List<Place> {
        val q = """[out:json][timeout:20];nwr["amenity"~"^(police|hospital|clinic|pharmacy|fire_station)$"](around:5000,$lat,$lng);out center 80;"""
        var last: Exception? = null
        for (url in endpoints) {
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 25_000
                    setRequestProperty("User-Agent", "Security4Her/0.1 (hackathon safety app)")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                return parse(body, lat, lng)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no endpoint")
    }

    private fun parse(body: String, lat: Double, lng: Double): List<Place> =
        Json.parseToJsonElement(body).jsonObject["elements"]!!.jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val center = o["center"]?.jsonObject ?: o
            val pLat = center["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val pLng = center["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val tags = o["tags"]?.jsonObject ?: JsonObject(emptyMap())
            fun tag(k: String) = tags[k]?.jsonPrimitive?.content
            val kind = tag("amenity") ?: return@mapNotNull null
            val name = tag("name:en") ?: tag("name") ?: "${kindLabel(kind)} (unnamed)"
            Place(
                id = "osm-" + (o["id"]?.jsonPrimitive?.longOrNull ?: 0),
                name = name, kind = kind, lat = pLat, lng = pLng,
                phone = tag("phone") ?: tag("contact:phone"),
                distanceM = meters(lat, lng, pLat, pLng),
            )
        }.distinctBy { it.name + it.kind }.sortedBy { it.distanceM }.take(40)
}

private fun dot(ctx: Context, color: Color, sizeDp: Int, ring: Color = Color.White, halo: Boolean = false): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val px = (sizeDp * d).toInt()
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val r = px / 2f
    if (halo) {
        p.color = color.copy(alpha = 0.25f).toArgb()
        c.drawCircle(r, r, r, p)
    }
    val inner = if (halo) r * 0.45f else r * 0.8f
    p.color = ring.toArgb()
    c.drawCircle(r, r, inner, p)
    p.color = color.toArgb()
    c.drawCircle(r, r, inner - 2.5f * d, p)
    return BitmapDrawable(ctx.resources, bmp)
}

private fun darkTiles(): ColorMatrixColorFilter {
    val m = ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
    m.postConcat(ColorMatrix(floatArrayOf(-0.574f, 1.43f, 0.144f, 0f, 0f, 0.426f, 0.43f, 0.144f, 0f, 0f, 0.426f, 1.43f, -0.856f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    m.postConcat(ColorMatrix(floatArrayOf(0.8f, 0f, 0f, 0f, 8f, 0f, 0.72f, 0f, 0f, 4f, 0f, 0f, 0.9f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)))
    return ColorMatrixColorFilter(m)
}

@SuppressLint("MissingPermission")
private suspend fun locate(ctx: Context): GeoPoint? = runCatching {
    val client = LocationServices.getFusedLocationProviderClient(ctx)
    val loc = withTimeoutOrNull(10_000) { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }
        ?: client.lastLocation.await()
    loc?.let { GeoPoint(it.latitude, it.longitude) }
}.getOrNull()

@Composable
fun SafeMapScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var hasLocation by remember { mutableStateOf(granted()) }
    var me by remember { mutableStateOf<GeoPoint?>(null) }
    var places by remember { mutableStateOf<List<Place>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Place?>(null) }
    var cells by remember { mutableStateOf<List<`in`.security4her.app.map.DangerCell>>(emptyList()) }
    var reportAt by remember { mutableStateOf<GeoPoint?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasLocation = granted() }

    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.cacheDir, "osm")
            osmdroidTileCache = File(ctx.cacheDir, "osm/tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            isTilesScaledToDpi = true
            // Standard OSM tiles, inverted and hue-shifted into a dark map that matches the app.
            overlayManager.tilesOverlay.setColorFilter(darkTiles())
            overlayManager.tilesOverlay.loadingBackgroundColor = 0xFF16101E.toInt()
            overlayManager.tilesOverlay.loadingLineColor = 0xFF221A2C.toInt()
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 4.0
            controller.setZoom(14.5)
            controller.setCenter(NAGPUR)
            setBackgroundColor(0xFF16101E.toInt())
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) map.onResume()
            if (e == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose { lifecycle.removeObserver(obs); map.onPause() }
    }

    LaunchedEffect(hasLocation, reload) {
        error = null
        val here = if (hasLocation) locate(ctx) else null
        me = here
        val center = here ?: NAGPUR
        map.controller.animateTo(center, 15.0, 700L)
        places = null
        places = try {
            Places.near(center.latitude, center.longitude)
        } catch (e: Exception) {
            error = "Couldn't load places. Check your internet and tap Refresh."
            emptyList()
        }
        if (!Backend.mock) cells = runCatching {
            MapRepository(Backend.client).dangerCells(center.latitude - 0.05, center.longitude - 0.05, center.latitude + 0.05, center.longitude + 0.05)
        }.getOrDefault(emptyList())
    }

    // Keep the overlays in step with state.
    LaunchedEffect(me, places, cells, selected) {
        map.overlays.clear()
        map.overlays += MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false.also { selected = null }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (Backend.mock) note = "Reporting unsafe spots needs the Security4Her server." else reportAt = p
                return true
            }
        })
        cells.forEach { c ->
            map.overlays += Polygon(map).apply {
                points = Polygon.pointsAsCircle(GeoPoint(c.lat, c.lng), 150.0)
                fillPaint.color = Color(0x55FF4D5E).toArgb()
                outlinePaint.color = Color(0xAAFF4D5E).toArgb()
                outlinePaint.strokeWidth = 2f
                setOnClickListener { _, _, _ -> true }
            }
        }
        places.orEmpty().forEach { p ->
            map.overlays += Marker(map).apply {
                position = GeoPoint(p.lat, p.lng)
                icon = dot(ctx, kindColor[p.kind] ?: Color(0xFFC49BFF), if (selected?.id == p.id) 30 else 20)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
                setOnMarkerClickListener { _, _ -> selected = p; true }
            }
        }
        me?.let {
            map.overlays += Marker(map).apply {
                position = it
                icon = dot(ctx, Color(0xFFFF4D5E), 44, halo = true)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
            }
        }
        map.invalidate()
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            AndroidView(factory = { map }, modifier = Modifier.fillMaxSize().clipToBounds())
            Row(Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("police", "hospital", "pharmacy").forEach { k ->
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xE6221A2C)).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(kindColor[k]!!))
                        Spacer(Modifier.width(6.dp))
                        Text(kindLabel(k), fontSize = 12.sp, color = Color(0xFFF3EDF7))
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapButton("+") { map.controller.zoomIn() }
                MapButton("−") { map.controller.zoomOut() }
                MapButton("◎") { (me ?: NAGPUR).let { map.controller.animateTo(it, 16.0, 500L) } }
            }
            Text(
                "© OpenStreetMap contributors", fontSize = 10.sp, color = Color(0xB3A99BB8),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
            if (places == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFFC49BFF))
        }

        Column(Modifier.fillMaxWidth().heightIn(max = 330.dp).background(Color(0xFF221A2C)).padding(top = 14.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Nearest safe places", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !hasLocation -> "Showing Nagpur. Allow location to see places near you."
                            me == null && places != null -> "Couldn't find you yet. Showing Nagpur."
                            else -> "Within 5 km of you, from OpenStreetMap"
                        },
                        fontSize = 12.sp, color = Color(0xFFA99BB8),
                    )
                }
                if (!hasLocation) TextButton(onClick = {
                    ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Allow", color = Color(0xFFC49BFF)) }
                else TextButton(onClick = { reload++ }) { Text("Refresh", color = Color(0xFFC49BFF)) }
            }
            (note ?: error)?.let { Text(it, fontSize = 13.sp, color = Color(0xFFFFB547), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            val list = places.orEmpty().let { l -> selected?.let { s -> listOf(s) + l.filter { it.id != s.id } } ?: l }
            if (places != null && list.isEmpty() && error == null) {
                Text("No police stations, hospitals or pharmacies mapped within 5 km.", color = Color(0xFFA99BB8), modifier = Modifier.padding(20.dp))
            }
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)) {
                itemsIndexed(list.take(15), key = { _, p -> p.id }) { _, p ->
                    PlaceRow(p, selected?.id == p.id, onFocus = {
                        selected = p
                        map.controller.animateTo(GeoPoint(p.lat, p.lng), 16.5, 500L)
                    })
                }
            }
        }
    }

    reportAt?.let { at ->
        ReportSpotDialog(onDismiss = { reportAt = null }) { cat, text ->
            reportAt = null
            scope.launch {
                note = try {
                    MapRepository(Backend.client).reportUnsafe(at.latitude, at.longitude, cat, text)
                    "Report saved. An area turns red once 3 women report it."
                } catch (e: Exception) {
                    "Couldn't save the report. Try again."
                }
            }
        }
    }
}

@Composable
private fun MapButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color(0xF0221A2C)).border(1.dp, Color(0xFF3D3149), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color(0xFFF3EDF7), fontSize = 20.sp) }
}

@Composable
private fun PlaceRow(p: Place, selected: Boolean, onFocus: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFF3A2D4A) else Color(0xFF2E2439)).clickable(onClick = onFocus).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(kindColor[p.kind] ?: Color(0xFFC49BFF)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${kindLabel(p.kind)}, ${distanceText(p.distanceM)}", fontSize = 12.sp, color = Color(0xFFA99BB8))
        }
        val phone = p.phone ?: if (p.kind == "police") "112" else null
        phone?.let { n ->
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + n.filter { it.isDigit() || it == '+' }))) }
            }) { Text("Call", color = Color(0xFFF3EDF7)) }
        }
        TextButton(onClick = {
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${p.lat},${p.lng}&mode=w")).setPackage("com.google.android.apps.maps")
            runCatching { ctx.startActivity(nav) }.onFailure {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${p.lat},${p.lng}&travelmode=walking")))
                }
            }
        }) { Text("Go", color = Color(0xFFC49BFF), fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ReportSpotDialog(onDismiss: () -> Unit, onSubmit: (ReportCategory, String?) -> Unit) {
    var cat by remember { mutableStateOf(ReportCategory.POOR_LIGHTING) }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF221A2C),
        title = { Text("Report this spot") },
        text = {
            Column {
                ReportCategory.entries.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().selectable(c == cat, onClick = { cat = c }, role = Role.RadioButton).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = c == cat, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFC49BFF)))
                        Spacer(Modifier.width(8.dp))
                        Text(c.label)
                    }
                }
                OutlinedTextField(text, { if (it.length <= 280) text = it }, label = { Text("What happened? (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(cat, text.trim().ifBlank { null }) }) { Text("Report", color = Color(0xFFC49BFF)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFFA99BB8)) } },
    )
}
