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
import android.graphics.DashPathEffect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.security4her.app.core.AppLog
import `in`.security4her.app.core.Community
import `in`.security4her.app.core.CommunityData
import `in`.security4her.app.core.CommunityReport
import `in`.security4her.app.core.Hotspot
import kotlinx.coroutines.delay
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
    "community" to Color(0xFFC49BFF),
)

private fun kindLabel(k: String) = when (k) {
    "police" -> "Police"
    "hospital" -> "Hospital"
    "clinic" -> "Clinic"
    "pharmacy" -> "Pharmacy"
    "fire_station" -> "Fire station"
    "community" -> "Added by women nearby"
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

internal fun dot(ctx: Context, color: Color, sizeDp: Int, ring: Color = Color.White, halo: Boolean = false): BitmapDrawable {
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

internal fun darkTiles(): ColorMatrixColorFilter {
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

private sealed interface MapSel {
    data class Official(val place: Place) : MapSel
    data class Report(val report: CommunityReport) : MapSel
    data class Spot(val hotspot: Hotspot) : MapSel
}

private val MapRed = Color(0xFFFF4D5E)
private val MapGreen = Color(0xFF5EE0A0)
private val MapLilac = Color(0xFFC49BFF)
private val MapAmber = Color(0xFFFFB547)
private val MapInk = Color(0xFFF3EDF7)
private val MapMuted = Color(0xFFA99BB8)
private val MapSurface = Color(0xFF221A2C)
private val MapSurface2 = Color(0xFF2E2439)
private val MapLine = Color(0xFF3D3149)

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 60 -> "${m.coerceAtLeast(1)} min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        m < 48 * 60 -> "yesterday"
        else -> "${m / (24 * 60)} days ago"
    }
}

private fun navigateTo(ctx: Context, lat: Double, lng: Double) {
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=w")).setPackage("com.google.android.apps.maps")
    runCatching { ctx.startActivity(nav) }.onFailure {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=walking"))) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SafeMapScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var hasLocation by remember { mutableStateOf(granted()) }
    var me by remember { mutableStateOf<GeoPoint?>(null) }
    var places by remember { mutableStateOf<List<Place>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<MapSel?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var showSafe by rememberSaveable { mutableStateOf(true) }
    var showDanger by rememberSaveable { mutableStateOf(true) }
    var showHot by rememberSaveable { mutableStateOf(true) }
    var adding by remember { mutableStateOf<String?>(null) } // danger | safe_area | safe_place while adding
    var addRadius by remember { mutableIntStateOf(250) }
    var center by remember { mutableStateOf(NAGPUR) }
    val cd by Community.data.collectAsStateWithLifecycle()
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
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    mapCenter.let { center = GeoPoint(it.latitude, it.longitude) }
                    return false
                }
                override fun onZoom(event: ZoomEvent?): Boolean = false
            })
        }
    }
    // Live preview of the area being added.
    val preview = remember {
        Polygon(map).apply {
            outlinePaint.color = MapLilac.toArgb()
            outlinePaint.strokeWidth = 4f
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
            fillPaint.color = MapLilac.copy(alpha = 0.12f).toArgb()
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
        val c = here ?: NAGPUR
        map.controller.animateTo(c, 15.0, 700L)
        places = null
        places = try {
            Places.near(c.latitude, c.longitude).also {
                AppLog.info("Map", "Loaded ${it.size} safe places near %.4f, %.4f".format(Locale.US, c.latitude, c.longitude))
            }
        } catch (e: Exception) {
            AppLog.warn("Map", "Couldn't load safe places", e)
            error = "Couldn't load hospitals and police stations. Check your internet and tap Refresh."
            emptyList()
        }
    }
    // Community reports: now, then every minute while the map is open.
    LaunchedEffect(Unit) {
        while (true) {
            Community.flush()
            Community.sync()
            delay(60_000)
        }
    }

    // Everything that counts as "somewhere safe to go": hospitals, police, pharmacies and places women added.
    val here = me ?: NAGPUR
    val safePlaces = remember(places, cd, me) {
        val community = cd.reports.filter { it.kind == "safe_place" }.map { r ->
            Place(
                id = r.id, name = r.name ?: Community.label(r.kind, r.category), kind = "community",
                lat = r.lat, lng = r.lng, phone = null, distanceM = meters(here.latitude, here.longitude, r.lat, r.lng),
            )
        }
        (places.orEmpty() + community).sortedBy { it.distanceM }
    }
    val hotspots = remember(cd) { Community.hotspots(cd) }
    val danger = remember(me, cd) { me?.let { Community.dangerAt(it.latitude, it.longitude, cd) } }

    LaunchedEffect(me, places, cd, selected, showSafe, showDanger, showHot, adding) {
        map.overlays.clear()
        map.overlays += MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false.also { selected = null }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null && adding == null) {
                    selected = null
                    adding = "danger"
                    map.controller.animateTo(p)
                }
                return true
            }
        })
        fun circle(lat: Double, lng: Double, radius: Double, fill: Color, stroke: Color, width: Float, dashed: Boolean, onTap: () -> Unit) {
            map.overlays += Polygon(map).apply {
                points = Polygon.pointsAsCircle(GeoPoint(lat, lng), radius)
                fillPaint.color = fill.toArgb()
                outlinePaint.color = stroke.toArgb()
                outlinePaint.strokeWidth = width
                if (dashed) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
                infoWindow = null
                setOnClickListener { _, _, _ -> if (adding == null) onTap(); adding == null }
            }
        }
        if (showSafe) cd.reports.filter { it.kind == "safe_area" }.forEach { r ->
            val n = Community.people(r, cd).coerceAtMost(4)
            circle(r.lat, r.lng, r.radiusM.toDouble(), MapGreen.copy(alpha = 0.08f + 0.04f * n), MapGreen.copy(alpha = 0.7f), 2.5f, false) { selected = MapSel.Report(r) }
        }
        if (showDanger) cd.reports.filter { it.kind == "danger" }.forEach { r ->
            val n = Community.people(r, cd).coerceAtMost(4)
            circle(r.lat, r.lng, r.radiusM.toDouble(), MapRed.copy(alpha = 0.08f + 0.05f * n), MapRed.copy(alpha = 0.75f), 2.5f, false) { selected = MapSel.Report(r) }
        }
        if (showHot) hotspots.forEach { h ->
            circle(h.lat, h.lng, h.radiusM.toDouble(), MapRed.copy(alpha = 0.28f), MapRed, 4f, true) { selected = MapSel.Spot(h) }
        }
        if (showSafe) {
            places.orEmpty().forEach { p ->
                val sel = (selected as? MapSel.Official)?.place?.id == p.id
                map.overlays += Marker(map).apply {
                    position = GeoPoint(p.lat, p.lng)
                    icon = dot(ctx, kindColor[p.kind] ?: MapLilac, if (sel) 30 else 20)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ -> if (adding == null) selected = MapSel.Official(p); true }
                }
            }
            cd.reports.filter { it.kind == "safe_place" }.forEach { r ->
                val sel = (selected as? MapSel.Report)?.report?.id == r.id
                map.overlays += Marker(map).apply {
                    position = GeoPoint(r.lat, r.lng)
                    icon = dot(ctx, MapLilac, if (sel) 32 else 24)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ -> if (adding == null) selected = MapSel.Report(r); true }
                }
            }
        }
        me?.let {
            map.overlays += Marker(map).apply {
                position = it
                icon = dot(ctx, MapRed, 44, halo = true)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
            }
        }
        if (adding == "danger" || adding == "safe_area") map.overlays += preview
        map.invalidate()
    }
    LaunchedEffect(adding, center, addRadius) {
        if (adding == "danger" || adding == "safe_area") {
            val c = if (adding == "danger") MapRed else MapGreen
            preview.points = Polygon.pointsAsCircle(center, addRadius.toDouble())
            preview.outlinePaint.color = c.toArgb()
            preview.fillPaint.color = c.copy(alpha = 0.14f).toArgb()
            map.invalidate()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            AndroidView(factory = { map }, modifier = Modifier.fillMaxSize().clipToBounds())
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (danger != null && adding == null) DangerBanner(danger, safePlaces.firstOrNull()) { p ->
                    navigateTo(ctx, p.lat, p.lng)
                }
                if (adding == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LayerChip("Safe", MapGreen, showSafe) { showSafe = !showSafe }
                    LayerChip("Danger", MapRed, showDanger) { showDanger = !showDanger }
                    LayerChip("SOS hotspots", MapRed, showHot, dashed = true) { showHot = !showHot }
                } else Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xF0221A2C)).padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text("Drag the map to put the pin on the spot", fontSize = 13.sp, color = MapInk)
                }
            }
            if (adding != null) {
                Canvas(Modifier.align(Alignment.Center).offset(y = (-21).dp).size(width = 30.dp, height = 42.dp)) {
                    val r = size.width / 2
                    val c = when (adding) { "danger" -> MapRed; "safe_area" -> MapGreen; else -> MapLilac }
                    drawLine(c, Offset(r, r * 1.6f), Offset(r, size.height - 2.dp.toPx()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(c, radius = r, center = Offset(r, r))
                    drawCircle(Color(0xFF16101E), radius = r * 0.38f, center = Offset(r, r))
                }
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapButton("+") { map.controller.zoomIn() }
                MapButton("−") { map.controller.zoomOut() }
                MapButton("◎") { (me ?: NAGPUR).let { map.controller.animateTo(it, 16.0, 500L) } }
            }
            if (adding == null) Box(
                Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(24.dp)).background(MapLilac)
                    .clickable { selected = null; adding = "danger"; addRadius = 250 }.padding(horizontal = 18.dp, vertical = 12.dp),
            ) { Text("+  Add to map", color = Color(0xFF1E0E33), fontWeight = FontWeight.SemiBold) }
            Text(
                "© OpenStreetMap contributors", fontSize = 10.sp, color = Color(0xB3A99BB8),
                modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
            )
            if (places == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = MapLilac)
        }

        val panel = Modifier.fillMaxWidth().background(MapSurface)
        val sel = selected
        when {
            adding != null -> AddPanel(
                kind = adding!!, radius = addRadius, onKind = { adding = it }, onRadius = { addRadius = it },
                onCancel = { adding = null },
                onSubmit = { kind, cat, name, text ->
                    val at = center
                    adding = null
                    scope.launch {
                        val shared = Community.add(ctx, kind, at.latitude, at.longitude, if (kind == "safe_place") 0 else addRadius, cat, name, text)
                        note = if (shared) "Added. Other women will see it on their map." else "Saved on your phone. It'll be shared when you're back online."
                    }
                },
                modifier = panel,
            )
            sel is MapSel.Report -> ReportPanel(sel.report, cd, here, onClose = { selected = null }, modifier = panel) {
                scope.launch { Community.confirm(ctx, sel.report) }
            }
            sel is MapSel.Spot -> HotspotPanel(sel.hotspot, onClose = { selected = null }, modifier = panel)
            else -> Column(panel.heightIn(max = 330.dp).padding(top = 14.dp)) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Nearest safe places", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                !hasLocation -> "Showing Nagpur. Allow location to see places near you."
                                me == null && places != null -> "Couldn't find you yet. Showing Nagpur."
                                else -> "Hospitals, police and places women added"
                            },
                            fontSize = 12.sp, color = MapMuted,
                        )
                    }
                    if (!hasLocation) TextButton(onClick = {
                        ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }) { Text("Allow", color = MapLilac) }
                    else TextButton(onClick = { reload++ }) { Text("Refresh", color = MapLilac) }
                }
                (note ?: error)?.let { Text(it, fontSize = 13.sp, color = MapAmber, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
                val focus = (sel as? MapSel.Official)?.place
                val list = focus?.let { f -> listOf(f) + safePlaces.filter { it.id != f.id } } ?: safePlaces
                if (places != null && list.isEmpty() && error == null) {
                    Text("No police stations, hospitals or pharmacies mapped within 5 km.", color = MapMuted, modifier = Modifier.padding(20.dp))
                }
                val listState = rememberLazyListState()
                LaunchedEffect(list.firstOrNull()?.id) { listState.scrollToItem(0) }
                LazyColumn(state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)) {
                    itemsIndexed(list.take(20), key = { _, p -> p.id }) { _, p ->
                        val report = if (p.kind == "community") cd.reports.firstOrNull { it.id == p.id } else null
                        PlaceRow(
                            p, focus?.id == p.id,
                            sub = report?.let { val n = Community.people(it, cd); "${Community.label(it.kind, it.category)} · $n " + (if (n == 1) "woman recommends" else "women recommend") },
                            onFocus = {
                                selected = report?.let { MapSel.Report(it) } ?: MapSel.Official(p)
                                map.controller.animateTo(GeoPoint(p.lat, p.lng), 16.5, 500L)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerChip(label: String, color: Color, on: Boolean, dashed: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Color(0xF02E2439) else Color(0xB3221A2C))
            .border(1.dp, if (on) color.copy(alpha = 0.7f) else MapLine, RoundedCornerShape(20.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape).background(if (on) color.copy(alpha = if (dashed) 0.45f else 1f) else MapLine)
                .then(if (dashed && on) Modifier.border(1.5.dp, color, CircleShape) else Modifier),
        )
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 12.sp, color = if (on) MapInk else MapMuted, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DangerBanner(d: Pair<CommunityReport?, Hotspot?>, nearest: Place?, onGo: (Place) -> Unit) {
    val (r, h) = d
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xF22A1520)).border(1.dp, MapRed.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text("You're in an area marked unsafe", color = MapRed, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(
            listOfNotNull(
                r?.let { val n = Community.people(it); "${Community.label(it.kind, it.category)}, reported by $n ${if (n == 1) "woman" else "women"}" },
                h?.let { "${it.count} SOS alerts raised here recently" },
            ).joinToString(". ") + ".",
            color = MapInk, fontSize = 12.5.sp,
        )
        nearest?.let { p ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Nearest safe place: ${p.name}, ${distanceText(p.distanceM)}", color = MapMuted, fontSize = 12.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(MapLilac).clickable { onGo(p) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text("Go", color = Color(0xFF1E0E33), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPanel(
    kind: String, radius: Int, onKind: (String) -> Unit, onRadius: (Int) -> Unit, onCancel: () -> Unit,
    onSubmit: (kind: String, category: String, name: String?, note: String?) -> Unit, modifier: Modifier,
) {
    var category by remember(kind) { mutableStateOf<String?>(null) }
    var name by remember(kind) { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val kindColor = when (kind) { "danger" -> MapRed; "safe_area" -> MapGreen; else -> MapLilac }
    Column(modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MapSurface2).padding(4.dp)) {
            listOf("danger" to "Danger zone", "safe_area" to "Safe area", "safe_place" to "Safe place").forEach { (k, l) ->
                val sel = k == kind
                val c = when (k) { "danger" -> MapRed; "safe_area" -> MapGreen; else -> MapLilac }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) c.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { onKind(k) }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(l, color = if (sel) c else MapMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when (kind) {
                "danger" -> "What makes this area feel unsafe?"
                "safe_area" -> "Why does this area feel safe?"
                else -> "What kind of place is it?"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Community.categories(kind).forEach { c ->
                val sel = c.key == category
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(if (sel) kindColor else MapSurface2).clickable { category = c.key }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) { Text(c.label, color = if (sel) Color(0xFF16101E) else MapInk, fontSize = 13.sp) }
            }
        }
        if (kind != "safe_place") {
            Spacer(Modifier.height(14.dp))
            Text("How big is the area?", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100 to "A street", 250 to "A block", 500 to "A neighbourhood").forEach { (m, l) ->
                    val sel = m == radius
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) kindColor.copy(alpha = 0.22f) else MapSurface2)
                            .clickable { onRadius(m) }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(l, color = if (sel) kindColor else MapInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("$m m", color = MapMuted, fontSize = 11.sp)
                    }
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                name, { if (it.length <= 60) name = it }, label = { Text("Name of the place") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MapLilac, unfocusedBorderColor = MapLine, focusedLabelColor = MapLilac, cursorColor = MapLilac),
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            text, { if (it.length <= 200) text = it }, label = { Text("Anything others should know? (optional)") }, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MapLilac, unfocusedBorderColor = MapLine, focusedLabelColor = MapLilac, cursorColor = MapLilac),
        )
        Text(
            "Shared anonymously. Your name and number are never attached.",
            color = MapMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel", color = MapMuted) }
            Spacer(Modifier.weight(1f))
            val ready = category != null && (kind != "safe_place" || name.isNotBlank())
            Button(
                onClick = { onSubmit(kind, category!!, name.ifBlank { null }, text.ifBlank { null }) }, enabled = ready,
                colors = ButtonDefaults.buttonColors(containerColor = MapLilac, contentColor = Color(0xFF1E0E33), disabledContainerColor = MapSurface2, disabledContentColor = MapMuted),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Add to map", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun ReportPanel(r: CommunityReport, cd: CommunityData, here: GeoPoint, onClose: () -> Unit, modifier: Modifier, onConfirm: () -> Unit) {
    val ctx = LocalContext.current
    val people = Community.people(r, cd)
    val mine = Community.confirmedByMe(ctx, r)
    val color = when (r.kind) { "danger" -> MapRed; "safe_area" -> MapGreen; else -> MapLilac }
    Column(modifier.padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(Community.kindLabel(r.kind), color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (r.sample) Box(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, MapLine, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("Sample", color = MapMuted, fontSize = 11.sp)
            }
            TextButton(onClick = onClose) { Text("Close", color = MapMuted) }
        }
        Text(r.name ?: Community.label(r.kind, r.category), style = MaterialTheme.typography.titleMedium)
        if (r.name != null) Text(Community.label(r.kind, r.category), color = MapMuted, fontSize = 13.sp)
        Text(
            "${if (r.kind == "safe_place") "Recommended" else "Reported"} by $people ${if (people == 1) "woman" else "women"} · ${ago(r.at)} · " +
                distanceText(meters(here.latitude, here.longitude, r.lat, r.lng)) + " away",
            color = MapMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
        )
        r.note?.let { Text("“$it”", color = MapInk, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mine) Text(if (r.by == Community.me(ctx)) "You added this" else "You confirmed this", color = MapMuted, fontSize = 13.sp)
            else OutlinedButton(onClick = onConfirm, border = BorderStroke(1.dp, color), shape = RoundedCornerShape(14.dp)) {
                Text(if (r.kind == "safe_place") "I recommend it too" else "I agree", color = color)
            }
            if (r.kind == "safe_place") Button(
                onClick = { navigateTo(ctx, r.lat, r.lng) },
                colors = ButtonDefaults.buttonColors(containerColor = MapLilac, contentColor = Color(0xFF1E0E33)), shape = RoundedCornerShape(14.dp),
            ) { Text("Go", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun HotspotPanel(h: Hotspot, onClose: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(MapRed.copy(alpha = 0.45f)).border(1.5.dp, MapRed, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("SOS hotspot", color = MapRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close", color = MapMuted) }
        }
        Text("${h.count} SOS alerts were raised here", style = MaterialTheme.typography.titleMedium)
        Text(
            "Marked red automatically because SOS keeps being used in this area (last one ${ago(h.lastAt)}). Stay on busy, lit roads here, or go around it.",
            color = MapMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
        )
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
private fun PlaceRow(p: Place, selected: Boolean, sub: String? = null, onFocus: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFF3A2D4A) else Color(0xFF2E2439)).clickable(onClick = onFocus).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(kindColor[p.kind] ?: MapLilac))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (sub ?: kindLabel(p.kind)) + ", ${distanceText(p.distanceM)}",
                fontSize = 12.sp, color = if (sub != null) MapLilac else Color(0xFFA99BB8), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val phone = p.phone ?: if (p.kind == "police") "112" else null
        phone?.let { n ->
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + n.filter { it.isDigit() || it == '+' }))) }
            }) { Text("Call", color = Color(0xFFF3EDF7)) }
        }
        TextButton(onClick = { navigateTo(ctx, p.lat, p.lng) }) { Text("Go", color = MapLilac, fontWeight = FontWeight.SemiBold) }
    }
}
