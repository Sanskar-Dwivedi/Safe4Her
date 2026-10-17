package `in`.security4her.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import `in`.security4her.app.core.AppLog
import `in`.security4her.app.core.Contact
import `in`.security4her.app.sos.ShareSheet
import `in`.security4her.app.sos.Sms
import kotlinx.coroutines.delay
import java.util.UUID

// ---------------------------------------------------------------- Family tab (Kids mode)
// Where a child's grown-ups connect their own phones to the family page: who's connected, a link texted to each
// grown-up, and a QR code to scan. Showing the QR or making a new link needs the grown-up PIN, because anyone holding
// the link can follow the child.

private fun qr(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { if (m.get(it % size, it / size)) 0xFF110C15.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

private fun seen(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 2 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}

@Composable
internal fun KidsFamily(childName: String, contacts: List<Contact>?) {
    val ctx = LocalContext.current
    var members by remember { mutableStateOf(Family.members(ctx)) }
    var link by remember { mutableStateOf(Family.link(ctx)) }
    var gate by remember { mutableStateOf<String?>(null) } // "qr" | "reset"
    var showQr by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(setOf<String>()) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { members = Family.refresh(ctx); delay(5_000) } }

    gate?.let { what ->
        PinGate(
            if (what == "qr") "Show the QR code" else "Make a new link", "Ask a grown-up to enter the PIN.",
            onUnlocked = {
                gate = null
                if (what == "qr") showQr = true
                else { Family.reset(ctx); link = Family.link(ctx); members = emptyList(); sent = emptySet(); showQr = false
                    note = "New link made. The old one stopped working; connect each grown-up again." }
            },
            onCancel = { gate = null },
        )
        return
    }

    val grown = contacts.orEmpty().filter { it.receivesSos }.sortedBy { it.priority }
    fun connected(name: String) = members.firstOrNull { it.name.equals(name, ignoreCase = true) }
    val extra = members.filter { m -> grown.none { it.name.equals(m.name, ignoreCase = true) } }
    val count = grown.count { connected(it.name) != null } + extra.size

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text("Family", fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, color = Ink)
        Text("Connect your grown-ups' phones. They see when you're safe and when you need them.", color = Muted, style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (count > 0) Mint.copy(alpha = 0.12f) else Surface1)
                .border(1.dp, if (count > 0) Mint.copy(alpha = 0.4f) else Line, RoundedCornerShape(22.dp)).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(if (count > 0) Mint else Surface2), contentAlignment = Alignment.Center) {
                Text("$count", color = if (count > 0) OnMint else Muted, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(if (count == 1) "1 grown-up connected" else "$count grown-ups connected", style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(
                    if (count > 0) "They'll see your SOS, calls and check-ins right away." else "Send the link to your grown-ups below.",
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Your grown-ups", style = MaterialTheme.typography.titleMedium, color = Ink)
        Spacer(Modifier.height(10.dp))
        if (grown.isEmpty()) Text("Add your grown-ups in My grown-ups first.", color = Amber, style = MaterialTheme.typography.bodyMedium)
        grown.forEach { c ->
            val m = connected(c.name)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(20.dp)).background(Surface1).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(c.name, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium, color = Ink)
                    Text(
                        when {
                            m != null -> "Connected · page open ${seen(m.seen)}"
                            c.phone in sent -> "Link sent · waiting for them to open it"
                            else -> "Not connected yet"
                        },
                        color = if (m != null) Mint else Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (m != null) {
                    Icon(Icons.Filled.Check, "Connected", tint = Mint, modifier = Modifier.size(26.dp))
                } else {
                    Chunky(
                        onClick = {
                            val err = Sms.send(ctx, c.phone, "Hi ${c.name}, it's $childName. Open this to stay connected to me on Security4Her (keep it private): $link", UUID.randomUUID().toString())
                            if (err == null) { sent = sent + c.phone; AppLog.info("Family", "Family link texted to ${c.name}") }
                            else note = "Couldn't text ${c.name}: $err"
                        },
                        tone = Tone.Accent, depth = 3.dp, shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Send, null, tint = OnLilac, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (c.phone in sent) "Again" else "Send", color = OnLilac, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        extra.forEach { m ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(20.dp)).background(Surface1).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(m.name, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium, color = Ink)
                    Text("Connected · page open ${seen(m.seen)}", color = Mint, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Filled.Check, "Connected", tint = Mint, modifier = Modifier.size(26.dp))
            }
        }
        note?.let { Text(it, color = Amber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }

        Spacer(Modifier.height(10.dp))
        Text("Connect in person", style = MaterialTheme.typography.titleMedium, color = Ink)
        Spacer(Modifier.height(10.dp))
        if (showQr) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surface1).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val bmp = remember(link) { qr(link) }
                Image(bmp.asImageBitmap(), "QR code for the family page", Modifier.size(240.dp).clip(RoundedCornerShape(14.dp)))
                Spacer(Modifier.height(12.dp))
                Text("Scan with the grown-up's phone camera", style = MaterialTheme.typography.titleSmall, color = Ink)
                Text("It opens the family page. No app needed.", color = Muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showQr = false }) { Text("Hide", color = Lilac) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChunkyButton("Show QR code", Modifier.weight(1f), tone = Tone.Plain, icon = Icons.Filled.Lock) { gate = "qr" }
                ChunkyButton("Share", Modifier.weight(0.7f), tone = Tone.Plain, icon = Icons.Filled.Share) {
                    ShareSheet.text(ctx, "Security4Her family link for $childName (keep it private): $link", "Share family link")
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("What your grown-ups can see", style = MaterialTheme.typography.titleMedium, color = Ink)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                "🚨" to "Your SOS the moment you press it, with where you are",
                "📞" to "Who's being called, and who answered",
                "✅" to "Your \"I got there safely\" check-ins",
                "🛑" to "A button to stop the SOS if it was pressed by mistake",
            ).forEach { (e, t) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e, fontSize = 18.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(t, color = Ink, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Mint.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Lock, null, tint = Mint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Encrypted. Only phones with your link can read it.", color = Mint, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { gate = "reset" }) { Text("Link shared by mistake? Make a new one", color = Muted) }
        Spacer(Modifier.height(16.dp))
    }
}
