package `in`.security4her.app

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.rotate
import `in`.security4her.app.core.Store
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

// ---------------------------------------------------------------- UI kit
// Pressable surfaces, the brand mark, tip cards and step progress.
// Everything presses *down* onto a darker edge, like a physical key; that one idea carries the whole app.


internal enum class Tone {
    Accent, Plain, Good, Danger;

    val face: Color get() = when (this) { Accent -> Lilac; Plain -> Surface2; Good -> Mint; Danger -> SosRed }
    val edge: Color get() = when (this) { Accent -> LilacDeep; Plain -> Ground; Good -> MintDeep; Danger -> RedDeep }
    val on: Color get() = when (this) { Accent -> OnLilac; Plain -> Ink; Good -> OnMint; Danger -> Color.White }
}

/** A surface that sits on a darker edge and sinks onto it when pressed. */
@Composable
internal fun Chunky(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Accent,
    shape: Shape = RoundedCornerShape(18.dp),
    depth: Dp = 5.dp,
    enabled: Boolean = true,
    outline: Color? = null,
    face: Color? = null,
    edge: Color? = null,
    haptic: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val drop by animateDpAsState(if (pressed && enabled) depth else 0.dp, tween(55), label = "drop")
    val feel = LocalHapticFeedback.current
    Box(modifier, propagateMinConstraints = true) {
        Box(Modifier.matchParentSize().padding(top = depth).clip(shape).background(if (enabled) edge ?: tone.edge else Ground))
        Box(Modifier.padding(bottom = depth), propagateMinConstraints = true) {
            Box(
                Modifier.offset(y = drop).clip(shape).background(if (enabled) face ?: tone.face else Surface2)
                    .then(if (outline != null) Modifier.border(2.dp, outline, shape) else Modifier)
                    .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button) {
                        if (haptic) feel.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    },
                contentAlignment = Alignment.Center,
                content = content,
            )
        }
    }
}

/** Full-width chunky button with a label. */
@Composable
internal fun ChunkyButton(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    tone: Tone = Tone.Accent,
    icon: ImageVector? = null,
    busy: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val on = enabled && !busy
    Chunky(onClick, modifier.heightIn(min = 56.dp), tone = tone, enabled = on, depth = 4.dp) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = tone.on, strokeWidth = 2.dp)
            else {
                icon?.let { Icon(it, null, tint = if (on) tone.on else Muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
                Text(text, color = if (on) tone.on else Muted, fontFamily = Baloo, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- Brand mark and tips

/** The logo on its cream tile. Fades in once; no idle motion, so it reads calm and grown-up. */
@Composable
internal fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    Box(modifier.graphicsLayer { alpha = a.value; val k = 0.94f + 0.06f * a.value; scaleX = k; scaleY = k }) { Logo(size) }
}

/** A quiet card with the logo mark: used for the one suggestion the app makes at a time. */
@Composable
internal fun Tip(text: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1).padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Logo(40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Ink)
            content()
        }
    }
}

// ---------------------------------------------------------------- Lesson chrome

/** Close button and a fat progress bar with a highlight stripe. */
@Composable
internal fun LessonBar(progress: Float, onClose: () -> Unit) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), spring(dampingRatio = 0.6f, stiffness = 120f), label = "lessonProgress")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Close lesson", onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, "Close", tint = Muted, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.width(8.dp))
        Pill(p, Modifier.weight(1f).height(18.dp))
    }
}

@Composable
internal fun Pill(fraction: Float, modifier: Modifier, color: Color = Lilac) {
    Box(modifier.clip(RoundedCornerShape(50)).background(Surface2)) {
        if (fraction > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(50)).background(color)) {
            Box(Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.35f)))
        }
    }
}

// ---------------------------------------------------------------- Progress (safety-ready steps)

/** Which safety-ready steps she has done. Kept on this phone. */
object Progress {
    private val _tick = MutableStateFlow(0)
    /** Bumps whenever anything changes, so screens recompose. */
    val tick: StateFlow<Int> = _tick.asStateFlow()

    fun done(ctx: Context, key: String) = Store.prefs(ctx).getBoolean("path_$key", false)
    fun mark(ctx: Context, key: String) {
        if (done(ctx, key)) return
        Store.prefs(ctx).edit().putBoolean("path_$key", true).apply()
        _tick.value++
    }
    fun reset(ctx: Context) {
        val p = Store.prefs(ctx)
        p.edit().apply { p.all.keys.filter { it.startsWith("path_") }.forEach { remove(it) } }.apply()
        _tick.value++
    }
}
