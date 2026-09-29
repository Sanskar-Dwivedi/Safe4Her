package `in`.security4her.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import `in`.security4her.app.core.Store

// ---------------------------------------------------------------- Colour palettes
// One rule for every palette: the brand lives in the background and the logo, the accent sits far from red on the
// colour wheel, and SOS red stays the loudest thing on screen. Me → Colours switches them live.

data class Palette(
    val key: String,
    val name: String,
    val blurb: String,
    val light: Boolean,
    val night: Color, val surface1: Color, val surface2: Color, val line: Color,
    val ink: Color, val muted: Color,
    val accent: Color, val accentDeep: Color, val onAccent: Color,
    val live: Color, val liveDeep: Color, val onLive: Color,
    val warn: Color,
    val ground: Color, // the edge under pressable surfaces
)

object Palettes {
    val Black = Palette(
        "black", "Black", "True black, white accent. SOS is the only colour.", false,
        night = Color(0xFF050505), surface1 = Color(0xFF141414), surface2 = Color(0xFF1F1F1F), line = Color(0xFF2C2C2C),
        ink = Color(0xFFF5F5F5), muted = Color(0xFF9A9A9A),
        accent = Color(0xFFFFFFFF), accentDeep = Color(0xFFBDBDBD), onAccent = Color(0xFF0A0A0A),
        live = Color(0xFF5EE0A0), liveDeep = Color(0xFF2E9E6A), onLive = Color(0xFF06270F),
        warn = Color(0xFFFFB547), ground = Color(0xFF000000),
    )
    val Dusk = Palette(
        "dusk", "Dusk", "Aubergine night, calm teal", false,
        night = Color(0xFF15101C), surface1 = Color(0xFF211A2B), surface2 = Color(0xFF2D2439), line = Color(0xFF3B3048),
        ink = Color(0xFFF4F0F8), muted = Color(0xFFA89FB6),
        accent = Color(0xFF4FD1C5), accentDeep = Color(0xFF2A9D93), onAccent = Color(0xFF04221F),
        live = Color(0xFF8BD96B), liveDeep = Color(0xFF5A9E3F), onLive = Color(0xFF0F2A06),
        warn = Color(0xFFFFB547), ground = Color(0xFF0A070E),
    )
    val Midnight = Palette(
        "midnight", "Midnight", "Ink blue, periwinkle", false,
        night = Color(0xFF0D0F1C), surface1 = Color(0xFF171A2E), surface2 = Color(0xFF21253D), line = Color(0xFF2E3350),
        ink = Color(0xFFEFF1FA), muted = Color(0xFF9AA0BF),
        accent = Color(0xFF8C9EFF), accentDeep = Color(0xFF5B6CE0), onAccent = Color(0xFF0B1030),
        live = Color(0xFF5EE0A0), liveDeep = Color(0xFF2E9E6A), onLive = Color(0xFF06270F),
        warn = Color(0xFFFFB547), ground = Color(0xFF07080F),
    )
    val Plum = Palette(
        "plum", "Plum", "Plum night, berry rose", false,
        night = Color(0xFF140B12), surface1 = Color(0xFF221520), surface2 = Color(0xFF2E1D2B), line = Color(0xFF3A2636),
        ink = Color(0xFFF7ECF2), muted = Color(0xFFB39AAA),
        accent = Color(0xFFE0689F), accentDeep = Color(0xFFA8457A), onAccent = Color(0xFF2A0B1C),
        live = Color(0xFF5EE0A0), liveDeep = Color(0xFF2E9E6A), onLive = Color(0xFF06270F),
        warn = Color(0xFFFFB547), ground = Color(0xFF0B0609),
    )
    val Daylight = Palette(
        "daylight", "Daylight", "Logo cream, deep berry", true,
        night = Color(0xFFFBF4F2), surface1 = Color(0xFFFFFFFF), surface2 = Color(0xFFF3E6EA), line = Color(0xFFE6D3DA),
        ink = Color(0xFF2B0F24), muted = Color(0xFF7A5C6E),
        accent = Color(0xFF9C2F6B), accentDeep = Color(0xFF6E1F4C), onAccent = Color.White,
        live = Color(0xFF1E8E57), liveDeep = Color(0xFF146B41), onLive = Color.White,
        warn = Color(0xFFA15C00), ground = Color(0xFFD9C3CB),
    )
    val all = listOf(Black, Dusk, Midnight, Plum, Daylight)

    /** Snapshot state, so every screen recolours the moment it changes. */
    val current = mutableStateOf(Black)

    fun load(ctx: Context) {
        val k = Store.prefs(ctx).getString("palette", null)
        current.value = all.firstOrNull { it.key == k } ?: Black
    }

    fun set(ctx: Context, p: Palette) {
        Store.prefs(ctx).edit().putString("palette", p.key).apply()
        current.value = p
    }
}

private val P get() = Palettes.current.value

// Tokens every screen uses. They read the current palette, so a switch recolours the whole app.
internal val Night get() = P.night
internal val Surface1 get() = P.surface1
internal val Surface2 get() = P.surface2
internal val Line get() = P.line
internal val Ink get() = P.ink
internal val Muted get() = P.muted
internal val Lilac get() = P.accent // the accent (historic name)
internal val LilacDeep get() = P.accentDeep
internal val OnLilac get() = P.onAccent
internal val LiveGreen get() = P.live
internal val Amber get() = P.warn
internal val Mint get() = P.live
internal val MintDeep get() = P.liveDeep
internal val OnMint get() = P.onLive
internal val Ground get() = P.ground
internal val Cream = Color(0xFFFEF7F6) // logo tile
internal val SosRed = Color(0xFFFF4D5E) // never changes: SOS is always this red
internal val RedDeep = Color(0xFFB0213A)
