package `in`.security4her.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import `in`.security4her.app.data.WatchStore
import `in`.security4her.app.service.SafetyService
import `in`.security4her.app.sos.Locator
import `in`.security4her.app.sos.PhoneLink
import `in`.security4her.app.sos.SosController
import `in`.security4her.app.ui.WatchActions
import `in`.security4her.app.ui.WatchUi
import kotlinx.coroutines.launch

/**
 * Entry point. Launched by the Samsung Knock Knock gesture (or the app icon — the two can't be told apart):
 * after setup, a launch starts the 5 s cancellable SOS countdown with no extra tap.
 * Opening from our own notification (EXTRA_STATUS_ONLY) or activity recreation only shows status.
 */
class MainActivity : ComponentActivity() {

    private val store by lazy { WatchStore.get(this) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            lifecycleScope.launch {
                if (Locator.hasPermission(this@MainActivity)) store.setSetupDone(true)
            }
        }

    private val actions = object : WatchActions {
        override fun requestPermissions() {
            val perms = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }

        override fun finishSetup() {
            lifecycleScope.launch { store.setSetupDone(true) }
        }

        override fun sosNow() = SosController.beginCountdown(this@MainActivity, "button")

        override fun cancelCountdown() = SosController.cancelCountdown()

        override fun imSafe() = SosController.end(this@MainActivity)

        override fun dismiss() = SosController.dismiss(this@MainActivity)

        override fun setSafetyMode(on: Boolean) {
            lifecycleScope.launch {
                store.setSafetyMode(on) // write state first; the service only observes it
                if (on) SafetyService.send(this@MainActivity, SafetyService.ACTION_ENSURE)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchUi(store, actions) }
        if (savedInstanceState == null) handleEntry(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEntry(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { PhoneLink.syncFromPhone(this@MainActivity) }
    }

    private fun handleEntry(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_STATUS_ONLY, false)) return
        if (intent.action != Intent.ACTION_MAIN) return
        lifecycleScope.launch {
            if (!store.setupDoneNow()) return@launch
            val live = store.incidentNow()
            if (live != null && !live.ended) return@launch // show the live incident, never a second one
            SosController.beginCountdown(this@MainActivity, "knock")
        }
    }

    companion object {
        const val EXTRA_STATUS_ONLY = "in.security4her.app.STATUS_ONLY"
    }
}
