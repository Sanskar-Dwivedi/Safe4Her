package `in`.security4her.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Separate exported launcher components let Samsung map each action independently. */
abstract class ActionShortcutActivity : Activity() {
    abstract val entryAction: String
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            startActivity(Intent(this, MainActivity::class.java).setAction(entryAction)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}

class SosShortcutActivity : ActionShortcutActivity() {
    override val entryAction = MainActivity.ACTION_SOS
}

class FakeCallShortcutActivity : ActionShortcutActivity() {
    override val entryAction = MainActivity.ACTION_FAKE_CALL
}
