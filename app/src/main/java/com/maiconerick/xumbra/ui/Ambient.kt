package com.maiconerick.xumbra.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import com.maiconerick.xumbra.ui.theme.Spook
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Background animations; the same set exists in the Windows app (Ambient.cs). */
val Ambients = listOf(
    "off" to "Off",
    "fireflies" to "🌟 Fireflies",
    "embers" to "🔥 Embers",
    "snow" to "❄ Snow",
    "rain" to "🌧 Rain",
    "leaves" to "🍂 Autumn leaves",
    "petals" to "🌸 Petals",
    "bats" to "🦇 Bat swarm",
    "stars" to "✨ Starfall",
    "wisps" to "👻 Ghost wisps",
    "aurora" to "🌌 Aurora",
    "bubbles" to "🧪 Cauldron bubbles",
)

/** Drama dials (Settings → Themes), per screen: particle count ×, size ×, and how far down top-anchored effects reach. */
data class AmbTune(val n: Float = 1.8f, val sc: Float = 1.4f, val reach: Float = 0.6f)
private typealias Tune = AmbTune

private class P(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float, val drift: Float, val k: Int)

private fun particles(n: Int, seed: Int) = Random(seed).let { rnd ->
    List(n) { P(rnd.nextFloat(), rnd.nextFloat(), 1.2f + rnd.nextFloat() * 2.6f, 0.4f + rnd.nextFloat(), rnd.nextFloat() * 6.28f, rnd.nextFloat(), rnd.nextInt(1000)) }
}

private fun frac(v: Float) = v - kotlin.math.floor(v)

/** Seconds since this composable appeared, ticking every frame. */
@Composable
private fun clock(): Float {
    val t by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1_000_000_000f }
    }
    return t
}

/** Home screen background. */
@Composable
fun HomeAmbient(modifier: Modifier = Modifier) {
    if (Spook.animations) Ambient(Spook.homeAmbient, modifier, tune = Spook.homeTune)
}

/** Chat screen background. */
@Composable
fun ChatAmbient(modifier: Modifier = Modifier) {
    if (Spook.animations) Ambient(Spook.chatAmbient, modifier, tune = Spook.chatTune)
}

@Composable
fun Ambient(kind: String, modifier: Modifier = Modifier, density: Float = 1f, tune: AmbTune = Spook.homeTune) {
    if (kind == "off") return
    val base = when (kind) { "rain" -> 70; "snow" -> 60; "stars" -> 70; "bubbles" -> 26; "bats" -> 9; "aurora" -> 4; else -> 28 }
    val ps = remember(kind) { particles(base * 3, kind.hashCode()) }
    val t = clock()
    val c = AmbientColors(Spook.Pumpkin, Spook.Moon, Spook.Slime, Spook.Mist, Spook.Witch, Spook.Bone)
    Canvas(modifier.fillMaxSize()) {
        val n = (base * density * tune.n).toInt().coerceIn(3, ps.size)
        val list = ps.take(n)
        when (kind) {
            "fireflies" -> fireflies(t, list, c, tune)
            "embers" -> embers(t, list, c, tune)
            "snow" -> snow(t, list, c, tune)
            "rain" -> rain(t, list, c, tune)
            "leaves" -> leaves(t, list, tune, listOf(Color(0xFFD9822B), Color(0xFFB4441C), Color(0xFFE8B23A), Color(0xFF8C5A2B)))
            "petals" -> leaves(t, list, tune, listOf(Color(0xFFF7B6C8), Color(0xFFF29BB5), Color(0xFFFFD6E0)), petal = true)
            "bats" -> bats(t, list, c, tune)
            "stars" -> stars(t, list, c, tune)
            "wisps" -> wisps(t, list, c, tune)
            "aurora" -> aurora(t, c, tune)
            "bubbles" -> bubbles(t, list, c, tune)
        }
    }
}

private class AmbientColors(val accent: Color, val moon: Color, val glow: Color, val mist: Color, val witch: Color, val text: Color)

private fun DrawScope.glowDot(o: Offset, r: Float, color: Color, alpha: Float, halo: Float = 5f) {
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.32f * alpha), Color.Transparent), o, r * halo), r * halo, o)
    drawCircle(color.copy(alpha = 0.85f * alpha), r, o)
}

private fun DrawScope.fireflies(t: Float, ps: List<P>, c: AmbientColors, k: Tune) {
    drawCircle(Brush.radialGradient(listOf(c.moon.copy(alpha = 0.12f), Color.Transparent), Offset(size.width * 0.85f, size.height * 0.08f), size.width * 0.45f),
        size.width * 0.45f, Offset(size.width * 0.85f, size.height * 0.08f))
    for (k in 0..1) {
        val y = size.height * (0.78f + 0.1f * k)
        val x = frac(t / (40f / (1 + k)) + k * 0.4f) * size.width * 2 - size.width * 0.5f
        drawCircle(Brush.radialGradient(listOf(c.mist.copy(alpha = 0.07f), Color.Transparent), Offset(x, y), size.width * 0.6f), size.width * 0.6f, Offset(x, y))
    }
    ps.forEach { f ->
        val a = t * 0.16f * f.speed * 2 * PI.toFloat() + f.phase
        val x = frac(f.x + sin(a) * 0.05f + t * f.drift * 0.0015f) * size.width
        val y = frac(f.y + sin(a * 0.7f + 1f) * 0.04f) * size.height
        glowDot(Offset(x, y), f.r * k.sc, c.glow, (0.35f + 0.65f * ((sin(a * 3) + 1) / 2)), 6f)
    }
}

private fun DrawScope.embers(t: Float, ps: List<P>, c: AmbientColors, k: Tune) {
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.witch.copy(alpha = 0.08f)), startY = size.height * 0.6f, endY = size.height))
    ps.forEachIndexed { i, e ->
        val rise = frac(t * e.speed * 0.05f + e.y)
        val x = (e.x + sin(rise * 6 + e.phase) * 0.03f) * size.width
        glowDot(Offset(x, size.height * (1f - rise)), e.r * 0.8f * k.sc, if (i % 3 == 0) c.moon else c.accent, sin(rise * PI.toFloat()), 6f)
    }
}

private fun DrawScope.snow(t: Float, ps: List<P>, c: AmbientColors, k: Tune) {
    ps.forEach { s ->
        val fall = frac(t * (0.025f + s.speed * 0.03f) + s.y)
        val x = frac(s.x + sin(t * 0.6f * s.speed + s.phase) * 0.02f + t * 0.004f) * size.width
        val depth = 0.5f + s.drift * 0.5f
        drawCircle(Color.White.copy(alpha = 0.55f * depth), s.r * depth * 1.1f * k.sc, Offset(x, fall * size.height))
    }
}

private fun DrawScope.rain(t: Float, ps: List<P>, c: AmbientColors, k: Tune) {
    val len = size.height * 0.035f * k.sc
    ps.forEach { d ->
        val fall = frac(t * (0.45f + d.speed * 0.35f) + d.y)
        val x = frac(d.x + fall * 0.04f) * size.width
        val y = fall * size.height * 1.05f
        drawLine(c.mist.copy(alpha = 0.18f + 0.18f * d.drift), Offset(x, y), Offset(x + len * 0.12f, y + len), strokeWidth = (1.3f + d.drift) * k.sc, cap = StrokeCap.Round)
        if (fall > 0.97f) drawCircle(c.mist.copy(alpha = 0.15f), 6f * (fall - 0.97f) / 0.03f + 1f, Offset(x, size.height - 4f), style = Stroke(1f))
    }
}

private fun DrawScope.leaves(t: Float, ps: List<P>, k: Tune, colors: List<Color>, petal: Boolean = false) {
    ps.forEach { l ->
        val fall = frac(t * (0.03f + l.speed * 0.025f) + l.y)
        val sway = sin(t * (0.8f + l.speed * 0.6f) + l.phase)
        val x = frac(l.x + sway * 0.06f + t * 0.006f) * size.width
        val y = fall * size.height * 1.1f - size.height * 0.05f
        val s = ((if (petal) 5f else 7f) + l.r * 2.2f) * k.sc
        rotate(sway * 70f + l.k, Offset(x, y)) {
            val p = Path().apply {
                moveTo(x, y - s)
                quadraticBezierTo(x + s * 0.9f, y, x, y + s)
                quadraticBezierTo(x - s * 0.9f, y, x, y - s)
            }
            drawPath(p, colors[l.k % colors.size].copy(alpha = 0.55f))
            if (!petal) drawLine(Color.Black.copy(alpha = 0.18f), Offset(x, y - s), Offset(x, y + s), 1f)
        }
    }
}

private fun DrawScope.bats(t: Float, ps: List<P>, c: AmbientColors, k: Tune) {
    drawCircle(Brush.radialGradient(listOf(c.moon.copy(alpha = 0.16f), Color.Transparent), Offset(size.width * 0.8f, size.height * 0.12f), size.width * 0.3f),
        size.width * 0.3f, Offset(size.width * 0.8f, size.height * 0.12f))
    ps.forEach { b ->
        val p = frac(t * (0.03f + b.speed * 0.025f) + b.x)
        val x = p * size.width * 1.3f - size.width * 0.15f
        val y = size.height * (0.06f + b.y * (k.reach - 0.06f)) + sin(t * 1.3f + b.phase) * size.height * 0.03f
        val flap = sin(t * (9f + b.speed * 5f) + b.phase)
        val w = (10f + b.r * 4f) * k.sc
        val body = Path().apply {
            moveTo(x - w, y - flap * w * 0.6f)
            quadraticBezierTo(x - w * 0.5f, y - w * 0.15f, x, y + w * 0.12f)
            quadraticBezierTo(x + w * 0.5f, y - w * 0.15f, x + w, y - flap * w * 0.6f)
            quadraticBezierTo(x + w * 0.45f, y + w * 0.1f, x, y + w * 0.35f)
            quadraticBezierTo(x - w * 0.45f, y + w * 0.1f, x - w, y - flap * w * 0.6f)
        }
        drawPath(body, Color.Black.copy(alpha = 0.55f))
    }
}

private fun DrawScope.stars(t: Float, ps: List<P>, c: AmbientColors, k: Tune) {
    ps.forEach { s ->
        val tw = 0.3f + 0.7f * ((sin(t * (1f + s.speed * 2f) + s.phase) + 1) / 2)
        drawCircle(c.text.copy(alpha = 0.55f * tw * (0.4f + s.drift * 0.6f)), s.r * 0.55f * k.sc, Offset(s.x * size.width, s.y * size.height * k.reach))
    }
    // a shooting star every few seconds
    for (j in 0..1) {
        val period = 6f + j * 3.5f
        val ph = frac((t + j * 2.3f) / period)
        if (ph < 0.18f) {
            val rnd = Random(((t + j * 2.3f) / period).toInt() * 31 + j)
            val sx = size.width * (0.2f + rnd.nextFloat() * 0.8f)
            val sy = size.height * rnd.nextFloat() * k.reach * 0.7f
            val q = ph / 0.18f
            val head = Offset(sx - q * size.width * 0.45f, sy + q * size.width * 0.22f)
            val tail = Offset(head.x + size.width * 0.12f, head.y - size.width * 0.06f)
            val a = sin(q * PI.toFloat())
            drawLine(Brush.linearGradient(listOf(c.moon.copy(alpha = 0.9f * a), Color.Transparent), head, tail), head, tail, 2.2f * k.sc, StrokeCap.Round)
            glowDot(head, 1.8f, c.moon, a, 4f)
        }
    }
}

private fun DrawScope.wisps(t: Float, ps: List<P>, c: AmbientColors, k: Tune) {
    ps.take((9 * k.n).toInt().coerceAtLeast(3)).forEach { w ->
        val p = frac(t * (0.012f + w.speed * 0.01f) + w.x)
        val x = p * size.width * 1.4f - size.width * 0.2f
        val y = size.height * (0.1f + w.y * (k.reach * 1.3f).coerceAtMost(0.85f)) + sin(t * 0.7f + w.phase) * 30f
        val r = (28f + w.r * 12f) * k.sc
        val a = 0.10f + 0.06f * sin(t + w.phase)
        // tail of fading puffs behind the head
        for (i in 4 downTo 0) {
            val o = Offset(x - i * r * 0.55f, y + sin(t * 2f + w.phase + i) * 6f)
            drawCircle(Brush.radialGradient(listOf(c.mist.copy(alpha = a * (1f - i / 5f)), Color.Transparent), o, r * (1f - i * 0.13f)), r * (1f - i * 0.13f), o)
        }
        drawCircle(Color.Black.copy(alpha = 0.25f), 2.4f, Offset(x - 5f, y - 3f))
        drawCircle(Color.Black.copy(alpha = 0.25f), 2.4f, Offset(x + 5f, y - 3f))
    }
}

/** Aurora: rippling vertical curtains of light hanging from a wavy line. */
private fun DrawScope.aurora(t: Float, c: AmbientColors, k: Tune) {
    val bands = listOf(c.glow, c.witch, Color(0xFF4FD8FF))
    val n = 60
    bands.forEachIndexed { i, col ->
        for (s in 0 until n) {
            val u = s.toFloat() / n
            val x0 = kotlin.math.floor(u * size.width)
            val x1 = kotlin.math.floor((s + 1f) / n * size.width) // whole pixels: no overlapping seams
            val top = size.height * (0.04f + i * 0.09f * k.reach * 1.6f) + sin(u * 7 + t * (0.3f + i * 0.1f) + i * 2) * size.height * 0.05f + sin(u * 17 - t * 0.5f) * size.height * 0.012f
            val len = size.height * (0.16f + 0.09f * sin(u * 11 + t * 0.7f + i)) * k.reach * 2.2f * k.sc.coerceAtMost(1.6f)
            val wave = (sin(u * 9 - t * (0.8f + i * 0.2f) + i) + 1) / 2
            val a = (0.05f + 0.07f * wave * wave) * (0.6f + 0.4f * sin(u * PI.toFloat())) * (0.7f + 0.3f * k.n).coerceAtMost(1.8f)
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.3f to col.copy(alpha = a), 0.7f to col.copy(alpha = a * 0.35f), 1f to Color.Transparent,
                startY = top, endY = top + len), Offset(x0, top), Size(x1 - x0, len))
        }
    }
}

private fun DrawScope.bubbles(t: Float, ps: List<P>, c: AmbientColors, k: Tune) {
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.glow.copy(alpha = 0.07f)), startY = size.height * 0.7f, endY = size.height))
    ps.forEach { b ->
        val rise = frac(t * (0.04f + b.speed * 0.04f) + b.y)
        val x = (b.x + sin(rise * 9 + b.phase) * 0.015f) * size.width
        val y = size.height * (1f - rise * 0.75f)
        val r = (3f + b.r * 3.2f * (0.6f + rise)) * k.sc
        val pop = if (rise > 0.93f) 1f - (rise - 0.93f) / 0.07f else 1f
        drawCircle(c.glow.copy(alpha = 0.32f * pop), r, Offset(x, y), style = Stroke(1.4f))
        drawCircle(Color.White.copy(alpha = 0.25f * pop), r * 0.22f, Offset(x - r * 0.35f, y - r * 0.35f))
    }
}
