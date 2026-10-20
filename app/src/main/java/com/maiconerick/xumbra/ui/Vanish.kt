@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.App
import com.maiconerick.xumbra.data.Msg
import com.maiconerick.xumbra.ui.theme.Spook
import kotlin.math.sin
import kotlin.random.Random

/** Which vanishing messages already played (and when they started), for this app session. */
object Vanished {
    val gone = mutableStateMapOf<String, Boolean>()
    val started = HashMap<String, Long>()
}

private const val APPEAR_STEP = 55L     // words pop in one after another
private const val CHAR_STEP = 28L       // letters leave one after another
private const val FLIGHT = 950L         // how long one letter takes to drift away

/**
 * A message that vanishes: each word stays for [App.prefs.vanishSeconds] after it appears,
 * then its letters drift up and away like smoke, one by one, until the bubble is gone.
 * Messages older than their own lifetime (reopening the chat) are simply not shown.
 */
@Composable
fun VanishBubble(m: Msg, mine: Boolean) {
    if (Vanished.gone[m.id] == true) return
    val life = (App.prefs.vanishSeconds * 1000).toLong()
    val words = remember(m.text) { m.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }
    val total = words.size * APPEAR_STEP + life + words.sumOf { it.length } * CHAR_STEP / maxOf(1, words.size) + FLIGHT + 600
    val now = System.currentTimeMillis()
    val start = remember(m.id) {
        Vanished.started.getOrPut(m.id) {
            // a message from earlier that has had its time already
            if (now - (m.ts * 1000).toLong() > total + 15_000 && !m.pending) -1L else now
        }
    }
    if (start < 0 || words.isEmpty()) { SideEffect { Vanished.gone[m.id] = true }; return }

    var t by remember(m.id) { mutableLongStateOf(System.currentTimeMillis() - start) }
    LaunchedEffect(m.id) {
        while (t < total) { withFrameMillis { t = System.currentTimeMillis() - start } }
        Vanished.gone[m.id] = true
    }
    val seed = remember(m.id) { m.id.hashCode() }
    // the bubble itself fades once the last word has gone
    val lastLeaves = (words.size - 1) * APPEAR_STEP + life + (words.lastOrNull()?.length ?: 0) * CHAR_STEP + FLIGHT
    val bubbleAlpha = (1f - ((t - lastLeaves + 300).coerceAtLeast(0) / 500f)).coerceIn(0f, 1f)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        FlowRow(
            Modifier.widthIn(max = 340.dp).graphicsLayer { alpha = bubbleAlpha }
                .clip(RoundedCornerShape(14.dp))
                .background(if (mine) Color(0xFF1A0D10) else Color(0xFF0D0D12))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            words.forEachIndexed { wi, w ->
                val appear = wi * APPEAR_STEP
                val leave = appear + life
                Row {
                    w.forEachIndexed { ci, ch ->
                        val rnd = remember(seed, wi, ci) { Random(seed * 31 + wi * 977 + ci) }
                        val dx = remember(rnd) { 30f + rnd.nextFloat() * 70f }
                        val dy = remember(rnd) { 25f + rnd.nextFloat() * 60f }
                        val spin = remember(rnd) { rnd.nextFloat() * 260f - 130f }
                        val wob = remember(rnd) { rnd.nextFloat() * 6.28f }
                        val shown = ((t - appear) / 180f).coerceIn(0f, 1f)
                        val p = ((t - leave - ci * CHAR_STEP).toFloat() / FLIGHT).coerceIn(0f, 1f)
                        Text(ch.toString(), color = if (mine) Color(0xFFFFB4A8) else Color(0xFFE8E4F0),
                            fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                            modifier = Modifier.graphicsLayer {
                                val e = p * p
                                translationX = e * dx + sin(p * 6f + wob) * 6f * p
                                translationY = -e * dy
                                rotationZ = e * spin
                                scaleX = 1f - 0.45f * p; scaleY = 1f - 0.45f * p
                                alpha = shown * (1f - p)
                            })
                    }
                }
            }
        }
    }
}

/** Little marker under the header so it's clear this chat vanishes. */
@Composable
fun VanishNote() {
    Text("🕯 vanishing chat — each word lasts ${"%.1f".format(App.prefs.vanishSeconds)} s",
        color = Spook.Mist, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}
