package com.maiconerick.xumbra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.maiconerick.xumbra.ui.theme.FlappingBat
import com.maiconerick.xumbra.ui.theme.PixelSprite
import com.maiconerick.xumbra.ui.theme.Spook
import com.maiconerick.xumbra.ui.theme.Sprites
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The pet bat (Settings → Themes): wanders over every screen, sometimes roosts upside down on the top edge,
 * and darts away when you poke it. Only the bat itself takes touches; everything under it works as usual.
 */
@Composable
fun BatPet() {
    if (!Spook.batPet) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val sizeDp = 30.dp * Spook.batPetSize
        val size = with(density) { sizeDp.toPx() }
        var pos by remember { mutableStateOf(Offset(w * 0.75f, h * 0.25f)) }
        var target by remember { mutableStateOf(pos) }
        var vel by remember { mutableStateOf(Offset.Zero) }
        var restUntil by remember { mutableStateOf(0L) }
        var scaredUntil by remember { mutableStateOf(0L) }
        var faceLeft by remember { mutableStateOf(false) }
        var t by remember { mutableStateOf(0f) }

        fun newTarget(far: Boolean = false) {
            val margin = size
            target = if (!far && Random.nextFloat() < 0.22f) Offset(Random.nextFloat() * (w - 2 * margin) + margin, 0f) // a roost on the top edge
            else {
                var p: Offset
                do {
                    p = Offset(Random.nextFloat() * (w - 2 * margin) + margin, Random.nextFloat() * (h * 0.85f - margin) + margin)
                } while (far && hypot(p.x - pos.x, p.y - pos.y) < w * 0.45f)
                p
            }
        }

        LaunchedEffect(w, h) {
            var last = 0L
            while (true) withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                t += dt
                val ms = now / 1_000_000
                if (ms < restUntil) return@withFrameNanos
                val scared = ms < scaredUntil
                val maxSpeed = (if (scared) 900f else 210f) * Spook.batPetSize.coerceIn(0.8f, 1.6f)
                val d = target - pos
                val dist = hypot(d.x, d.y)
                if (dist < 6f) {
                    if (target.y <= 1f) restUntil = ms + Random.nextLong(3000, 9000) // roosting
                    newTarget()
                    return@withFrameNanos
                }
                // steer: accelerate toward the target, slow down when close, with a fluttery wobble
                val desired = Offset(d.x / dist, d.y / dist) * (maxSpeed * (dist / 120f).coerceIn(0.25f, 1f))
                vel += (desired - vel) * (dt * (if (scared) 7f else 2.6f))
                val wobble = Offset(0f, sin(t * 9f) * 38f)
                pos = Offset((pos.x + (vel.x + wobble.x) * dt).coerceIn(0f, w - size), (pos.y + (vel.y + wobble.y) * dt).coerceIn(0f, h - size))
                if (kotlin.math.abs(vel.x) > 12f) faceLeft = vel.x < 0
            }
        }

        val resting = System.currentTimeMillis() < restUntil
        Box(
            Modifier.offset { IntOffset(pos.x.toInt(), pos.y.toInt()) }.size(sizeDp)
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    restUntil = 0; scaredUntil = System.currentTimeMillis() + 1400; newTarget(far = true)
                },
        ) {
            if (resting) PixelSprite(Sprites.bat2, sizeDp, Modifier.graphicsLayer { rotationZ = 180f }) // hanging upside down
            else FlappingBat(sizeDp, Modifier.graphicsLayer { scaleX = if (faceLeft) -1f else 1f },
                periodMs = if (System.currentTimeMillis() < scaredUntil) 110 else 240)
        }
    }
}
