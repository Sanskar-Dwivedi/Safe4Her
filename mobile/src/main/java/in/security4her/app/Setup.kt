package `in`.security4her.app

import android.content.Context

// The few things that make an SOS work. Home suggests the next one that isn't done yet.

data class SetupStep(val key: String, val title: String)

/** Facts from the rest of the app that finish setup steps on their own. */
data class ReadyFacts(val hasContact: Boolean = false, val hasPerms: Boolean = false, val hasPin: Boolean = false, val watch: Boolean = false)

val SETUP_STEPS = listOf(
    SetupStep("contact", "Add a trusted contact"),
    SetupStep("perms", "Allow location and texts"),
    SetupStep("pin", "Set a safety PIN"),
    SetupStep("test_text", "Send a test text"),
    SetupStep("watch", "Link your watch"),
)

fun stepDone(ctx: Context, s: SetupStep, f: ReadyFacts) = when (s.key) {
    "contact" -> f.hasContact
    "perms" -> f.hasPerms
    "pin" -> f.hasPin
    "watch" -> f.watch || Progress.done(ctx, "watch")
    else -> Progress.done(ctx, s.key)
}

fun nextStep(ctx: Context, f: ReadyFacts): SetupStep? = SETUP_STEPS.firstOrNull { !stepDone(ctx, it, f) }
