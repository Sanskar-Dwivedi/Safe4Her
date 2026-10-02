package `in`.security4her.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import `in`.security4her.app.data.WatchStore
import `in`.security4her.app.service.SafetyService
import `in`.security4her.app.service.TwistService
import `in`.security4her.app.sos.FakeCallController
import `in`.security4her.app.sos.TwistStrength
import `in`.security4her.app.sos.TwistRuntime
import `in`.security4her.app.sos.Locator
import `in`.security4her.app.sos.PhoneLink
import `in`.security4her.app.sos.SosController
import `in`.security4her.app.sos.WristTwistDetector
import `in`.security4her.app.ui.WatchActions
import `in`.security4her.app.ui.WatchUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Samsung may open the default launcher for Knock Knock, so ordinary launches start SOS after setup. */
class MainActivity : ComponentActivity() {

    private val store by lazy { WatchStore.get(this) }
    private var entryGeneration by mutableIntStateOf(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            lifecycleScope.launch {
                if (Locator.hasPermission(this@MainActivity)) store.setSetupDone(true)
            }
        }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) SafetyService.send(this, SafetyService.ACTION_ENSURE)
        }

    private val actions = object : WatchActions {
        override fun requestPermissions() {
            val perms = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.RECORD_AUDIO) // audio evidence during an SOS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }

        override fun finishSetup() {
            lifecycleScope.launch { store.setSetupDone(true) }
        }

        override fun sosNow() = SosController.beginCountdown(this@MainActivity, "button")

        override fun fakeCallNow() = FakeCallController.request(this@MainActivity)

        override fun setTwistStrength(strength: TwistStrength) {
            lifecycleScope.launch { store.setTwistStrength(strength) }
        }

        override fun cancelCountdown() = SosController.cancelCountdown()

        override fun imSafe(pin: String?) = SosController.end(this@MainActivity, pin)

        override fun dismiss() = SosController.dismiss(this@MainActivity)

        override fun setVoiceSos(on: Boolean) {
            lifecycleScope.launch {
                store.setVoiceSos(on)
                if (on && !`in`.security4her.app.voice.VoiceSos.hasPermission(this@MainActivity)) {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else if (on) {
                    SafetyService.send(this@MainActivity, SafetyService.ACTION_ENSURE) // claims the microphone while visible
                }
            }
        }

        override fun setSafetyMode(on: Boolean) {
            lifecycleScope.launch {
                store.setSafetyMode(on) // write state first; the service only observes it
                if (on) SafetyService.send(this@MainActivity, SafetyService.ACTION_ENSURE)
            }
        }

        override fun setFakeCallTwist(on: Boolean) {
            if (on && !WristTwistDetector.available(this@MainActivity)) return
            lifecycleScope.launch {
                store.setFakeCallTwist(on)
                if (on) TwistService.ensure(this@MainActivity)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { key(entryGeneration) { WatchUi(store, actions) } }
        if (savedInstanceState == null) handleEntry(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A shortcut must show its result, even if the previous visit ended on a help subpage.
        if (!TwistRuntime.practice.value) entryGeneration++
        handleEntry(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { withTimeoutOrNull(8_000) { PhoneLink.syncFromPhone(this@MainActivity) } }
        lifecycleScope.launch {
            // Re-arm enabled watch features (e.g. after a restart); the microphone needs the app visible.
            if (store.voiceSos.first() || store.safetyModeNow() || store.incidentNow()?.ended == false)
                SafetyService.send(this@MainActivity, SafetyService.ACTION_ENSURE)
            if (store.fakeCallTwistNow()) TwistService.ensure(this@MainActivity)
        }
        // Just look: the background job installs at a moment that won't interrupt her.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { `in`.security4her.app.sos.WatchUpdater.run(this@MainActivity, install = false) }
    }

    private fun handleEntry(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_STATUS_ONLY, false)) return
        val action = intent.action
        val startsSos = action == ACTION_SOS || action == Intent.ACTION_MAIN || action == null
        if (!startsSos && action != ACTION_FAKE_CALL) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        // Consume it before suspension; neither recreation nor returning from recents repeats it.
        intent.action = ACTION_STATUS
        if (TwistRuntime.practice.value) return
        lifecycleScope.launch {
            if (!store.setupDoneNow()) return@launch
            val live = store.incidentNow()
            if (live != null && !live.ended) return@launch // show the live incident, never a second one
            // Keep the existing wire trigger: the server contract allows knock/shake/button/voice.
            if (startsSos) SosController.beginCountdown(this@MainActivity, "knock")
            else FakeCallController.request(this@MainActivity)
        }
    }

    companion object {
        const val EXTRA_STATUS_ONLY = "in.security4her.app.STATUS_ONLY"
        const val ACTION_SOS = "in.security4her.app.action.SOS"
        const val ACTION_FAKE_CALL = "in.security4her.app.action.FAKE_CALL"
        const val ACTION_STATUS = "in.security4her.app.action.STATUS"
    }
}
