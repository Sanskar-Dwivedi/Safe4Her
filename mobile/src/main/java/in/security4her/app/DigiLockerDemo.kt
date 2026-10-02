package `in`.security4her.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeights
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.security4her.app.core.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// MOCK: DigiLocker is simulated for the hackathon demo. No real DigiLocker or Aadhaar service is contacted.
// The accounts below are made up; the Aadhaar numbers are fictional and only work in this demo.

data class DemoAccount(val aadhaar: String, val name: String, val gender: String, val dob: String)

val DEMO_ACCOUNTS = listOf(
    DemoAccount("234567894821", "Priya Sharma", "Female", "14-03-2001"),
    DemoAccount("345678901357", "Rohan Mehta", "Male", "02-11-1998"),
)
private const val DEMO_OTP = "123456"

private fun masked(a: String) = "XXXX XXXX " + a.takeLast(4)
private fun spaced(a: String) = a.chunked(4).joinToString(" ")

private enum class Step { Aadhaar, Otp, Consent, Checking, Result }

/**
 * DigiLocker sign-in: Aadhaar number → OTP → consent to share name and gender → result.
 * Only a female account passes; a male account is turned away.
 */
@Composable
fun DigiLockerDemoScreen(onCancel: () -> Unit, onVerifiedFemale: (DemoAccount) -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.Aadhaar) }
    var aadhaar by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var account by remember { mutableStateOf<DemoAccount?>(null) }
    var sending by remember { mutableStateOf(false) }
    BackHandler { if (step == Step.Aadhaar || step == Step.Result) onCancel() else { step = Step.Aadhaar; otp = ""; error = null } }

    Column(Modifier.fillMaxSize().background(Night)) {
        Row(Modifier.fillMaxWidth().background(Surface1).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel", color = Muted) }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("DigiLocker", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(72.dp))
        }
        StepDots(step)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
            when (step) {
                Step.Aadhaar -> {
                    Text("Sign in with Aadhaar", style = MaterialTheme.typography.headlineSmall)
                    Text("Security4Her asks DigiLocker to confirm your name and gender.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(20.dp))
                    Field(spaced(aadhaar), { v -> aadhaar = v.filter(Char::isDigit).take(12); error = null }, "Aadhaar number (12 digits)", KeyboardType.Number)
                    error?.let { Text(it, color = SosRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton("Send OTP", busy = sending, enabled = aadhaar.length == 12) {
                        val a = DEMO_ACCOUNTS.firstOrNull { it.aadhaar == aadhaar }
                        if (a == null) {
                            error = "No DigiLocker account found for this Aadhaar number."
                            return@PrimaryButton
                        }
                        sending = true
                        scope.launch {
                            delay(900)
                            sending = false
                            account = a
                            step = Step.Otp
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    DemoAccounts { a -> aadhaar = a.aadhaar; error = null }
                }
                Step.Otp -> {
                    val a = account!!
                    var autoRead by remember { mutableStateOf(false) }
                    LaunchedEffect(a) { delay(1_600); if (otp.isEmpty()) { otp = DEMO_OTP; autoRead = true } } // MOCK: stands in for SMS OTP autofill
                    Text("Enter the OTP", style = MaterialTheme.typography.headlineSmall)
                    Text("Sent to the mobile number linked to Aadhaar ${masked(a.aadhaar)}.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(20.dp))
                    Field(otp, { v -> otp = v.filter(Char::isDigit).take(6); error = null; autoRead = false }, "6-digit OTP", KeyboardType.NumberPassword)
                    error?.let { Text(it, color = SosRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                    if (autoRead) Text("OTP read from SMS", color = Lilac, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton("Verify OTP", enabled = otp.length == 6) {
                        if (otp != DEMO_OTP) error = "That OTP is wrong. Check it and try again." else { error = null; step = Step.Consent }
                    }
                }
                Step.Consent -> {
                    val a = account!!
                    Text("Allow Security4Her to see", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ConsentRow("Name", "From your Aadhaar")
                        ConsentRow("Gender", "From your Aadhaar")
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Your Aadhaar number, address, photo and documents are not shared. Security4Her keeps only whether you're verified.",
                        color = Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton("Allow") {
                        step = Step.Checking
                        scope.launch {
                            delay(1_200)
                            AppLog.info("Account", "DigiLocker returned ${a.gender.lowercase()} for ${masked(a.aadhaar)}")
                            if (a.gender == "Female") onVerifiedFemale(a) else step = Step.Result
                        }
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Deny", color = Muted) }
                }
                Step.Checking -> Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Lilac)
                    Spacer(Modifier.height(16.dp))
                    Text("Checking your details…", color = Muted)
                }
                Step.Result -> {
                    val a = account!!
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(SosRed.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Text("✕", color = SosRed, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("Security4Her is for women only", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "DigiLocker shows this account (${a.name}, ${masked(a.aadhaar)}) as ${a.gender.lowercase()}, so it can't be used to join. " +
                                "This keeps the safety map, alerts and community reports trustworthy.",
                            color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        PrimaryButton("Try another account") { step = Step.Aadhaar; aadhaar = ""; otp = ""; account = null }
                        TextButton(onClick = onCancel) { Text("Back", color = Muted) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDots(step: Step) {
    val i = when (step) { Step.Aadhaar -> 0; Step.Otp -> 1; else -> 2 }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center) {
        listOf("Aadhaar", "OTP", "Consent").forEachIndexed { n, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (n <= i) Lilac else Line))
                Spacer(Modifier.width(6.dp))
                Text(label, color = if (n <= i) Ink else Muted, fontSize = 12.sp)
            }
            if (n < 2) Box(Modifier.padding(horizontal = 10.dp).width(24.dp).height(1.dp).align(Alignment.CenterVertically).background(Line))
        }
    }
}

@Composable
private fun ConsentRow(what: String, from: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Lilac.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Text("✓", color = Lilac, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(what, fontWeight = FontWeight.SemiBold)
            Text(from, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DemoAccounts(onPick: (DemoAccount) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(16.dp),
    ) {
        Text("Recent accounts on this device", color = Ink, style = MaterialTheme.typography.titleSmall)
        Text("Tap one to fill it in.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        DEMO_ACCOUNTS.forEach { a ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Surface1)
                    .clickable { onPick(a) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, fontWeight = FontWeight.SemiBold)
                    Text(spaced(a.aadhaar), color = Muted, fontSize = 12.sp)
                }
                val female = a.gender == "Female"
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).border(BorderStroke(1.dp, if (female) Lilac else SosRed), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) { Text(a.gender, color = if (female) Lilac else SosRed, fontSize = 11.sp) }
            }
        }
    }
}
