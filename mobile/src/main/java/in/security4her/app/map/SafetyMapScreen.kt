package `in`.security4her.app.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private val DEFAULT_CENTER = LatLng(21.1458, 79.0882) // Nagpur (demo city), until we know where she is
private val DANGER = Color(0xFFD32F2F)
private val PURPLE = Color(0xFF5B2A86)

/**
 * Safety map (Claude-owned; CONTRACT.md §5):
 * nearest safe places (police, hospitals, pharmacies…) with Call / Go, community danger zones,
 * and long-press to report an unsafe spot.
 */
@Composable
fun SafetyMapScreen(supabase: SupabaseClient, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(supabase) { MapRepository(supabase) }

    var hasLocation by remember { mutableStateOf(hasLocationPermission(context)) }
    var me by remember { mutableStateOf<LatLng?>(null) }
    var places by remember { mutableStateOf<List<SafePlace>>(emptyList()) }
    var cells by remember { mutableStateOf<List<DangerCell>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var reportAt by remember { mutableStateOf<LatLng?>(null) }
    var cellsVersion by remember { mutableStateOf(0) }

    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(DEFAULT_CENTER, 12f) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = hasLocationPermission(context)
    }

    suspend fun refreshPlaces(at: LatLng) {
        loading = true
        try {
            places = repo.nearestSafePlaces(at.latitude, at.longitude)
            message = if (places.isEmpty()) "No safe places loaded for this area yet." else null
        } catch (e: Exception) {
            message = e.userMessage()
        } finally {
            loading = false
        }
    }

    // Locate her once permission is granted, centre the map, and load nearby safe places.
    LaunchedEffect(hasLocation) {
        if (!hasLocation) return@LaunchedEffect
        val fix = currentLocation(context) ?: return@LaunchedEffect
        me = fix
        camera.animate(CameraUpdateFactory.newLatLngZoom(fix, 15f), 800)
        refreshPlaces(fix)
    }

    // Load danger cells for whatever is on screen once the camera settles (and after a report).
    LaunchedEffect(camera.isMoving, cellsVersion) {
        if (camera.isMoving) return@LaunchedEffect
        delay(350)
        val bounds = camera.projection?.visibleRegion?.latLngBounds ?: return@LaunchedEffect
        if (camera.position.zoom < 11f) {
            cells = emptyList()
            return@LaunchedEffect
        }
        try {
            cells = repo.dangerCells(
                bounds.southwest.latitude, bounds.southwest.longitude,
                bounds.northeast.latitude, bounds.northeast.longitude,
            )
        } catch (e: Exception) {
            message = e.userMessage()
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = camera,
                properties = MapProperties(isMyLocationEnabled = hasLocation),
                uiSettings = MapUiSettings(myLocationButtonEnabled = hasLocation, mapToolbarEnabled = false),
                onMapLongClick = { reportAt = it },
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                val maxScore = cells.maxOfOrNull { it.score }?.coerceAtLeast(1.0) ?: 1.0
                cells.forEach { cell ->
                    val intensity = (cell.score / maxScore).coerceIn(0.2, 1.0).toFloat()
                    Circle(
                        center = LatLng(cell.lat, cell.lng),
                        radius = 150.0,
                        fillColor = DANGER.copy(alpha = 0.15f + 0.3f * intensity),
                        strokeColor = DANGER.copy(alpha = 0.6f),
                        strokeWidth = 2f,
                    )
                }
                places.forEach { place ->
                    key(place.id) {
                        Marker(
                            state = rememberUpdatedMarkerState(position = LatLng(place.lat, place.lng)),
                            title = place.name,
                            snippet = "${categoryLabel(place.category)} · ${formatDistance(place.distanceM)} · tap to navigate",
                            icon = BitmapDescriptorFactory.defaultMarker(categoryHue(place.category)),
                            onInfoWindowClick = { navigate(context, place) },
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 3.dp,
            ) {
                Text(
                    "Red areas: places ≥3 women reported as unsafe, plus official data. Long-press the map to report a spot.",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        NearestPanel(
            hasLocation = hasLocation,
            places = places.take(5),
            message = message,
            onGrant = {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            },
            onRefresh = { me?.let { scope.launch { refreshPlaces(it) } } },
            onCall = { dial(context, it) },
            onGo = { navigate(context, it) },
        )
    }

    reportAt?.let { at ->
        ReportDialog(
            onDismiss = { reportAt = null },
            onSubmit = { category, note ->
                reportAt = null
                scope.launch {
                    message = try {
                        repo.reportUnsafe(at.latitude, at.longitude, category, note)
                        cellsVersion++
                        "Thanks — your report was saved. Areas appear once 3 women report them."
                    } catch (e: Exception) {
                        e.userMessage()
                    }
                }
            },
        )
    }
}

@Composable
private fun NearestPanel(
    hasLocation: Boolean,
    places: List<SafePlace>,
    message: String?,
    onGrant: () -> Unit,
    onRefresh: () -> Unit,
    onCall: (SafePlace) -> Unit,
    onGo: (SafePlace) -> Unit,
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nearest safe places", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (hasLocation) TextButton(onClick = onRefresh) { Text("Refresh") }
            }
            if (!hasLocation) {
                Text("Allow location to see the closest police stations, hospitals and pharmacies.")
                Button(onClick = onGrant) { Text("Allow location") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
            places.forEachIndexed { i, place ->
                if (i > 0) HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            place.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(categoryLabel(place.category))
                                append(" · ")
                                append(formatDistance(place.distanceM))
                                if (place.verified) append(" · ✓ verified")
                            },
                            fontSize = 13.sp,
                            color = if (place.verified) PURPLE else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (place.phone != null) {
                        OutlinedButton(onClick = { onCall(place) }, modifier = Modifier.padding(end = 6.dp)) { Text("Call") }
                    }
                    Button(onClick = { onGo(place) }) { Text("Go") }
                }
            }
        }
    }
}

@Composable
private fun ReportDialog(onDismiss: () -> Unit, onSubmit: (ReportCategory, String?) -> Unit) {
    var category by remember { mutableStateOf(ReportCategory.POOR_LIGHTING) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report unsafe spot") },
        text = {
            Column {
                ReportCategory.entries.forEach { c ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = c == category, onClick = { category = c }, role = Role.RadioButton)
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(selected = c == category, onClick = null)
                        Text(c.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 280) note = it },
                    label = { Text("What happened? (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "Reports are anonymous on the map and appear only after 3 different women report the same area.",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { Button(onClick = { onSubmit(category, note.trim()) }) { Text("Report") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): LatLng? = try {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val loc = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
        ?: client.lastLocation.await()
    loc?.let { LatLng(it.latitude, it.longitude) }
} catch (e: Exception) {
    null
}

private fun categoryLabel(category: String) = when (category) {
    "police" -> "Police"
    "hospital" -> "Hospital"
    "clinic" -> "Clinic"
    "pharmacy" -> "Pharmacy"
    "fire_station" -> "Fire station"
    "cafe" -> "Café (partner)"
    "ngo" -> "NGO"
    else -> "Safe place"
}

private fun categoryHue(category: String) = when (category) {
    "police" -> BitmapDescriptorFactory.HUE_AZURE
    "hospital", "clinic" -> BitmapDescriptorFactory.HUE_GREEN
    "pharmacy" -> BitmapDescriptorFactory.HUE_CYAN
    "fire_station" -> BitmapDescriptorFactory.HUE_ORANGE
    else -> BitmapDescriptorFactory.HUE_VIOLET
}

/** Walking navigation in Google Maps (free intent; no Directions API key needed), web fallback. */
private fun navigate(context: Context, place: SafePlace) {
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${place.lat},${place.lng}&mode=w"))
        .setPackage("com.google.android.apps.maps")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(nav)
    } catch (e: ActivityNotFoundException) {
        val web = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${place.lat},${place.lng}&travelmode=walking")
        context.startActivity(Intent(Intent.ACTION_VIEW, web).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun dial(context: Context, place: SafePlace) {
    val phone = place.phone ?: return
    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.filter { it.isDigit() || it == '+' }}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
