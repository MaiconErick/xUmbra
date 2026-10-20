package com.maiconerick.xumbra.ui

import com.maiconerick.xumbra.App
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.ui.theme.Spook
import kotlinx.coroutines.delay
import java.text.BreakIterator

/**
 * Matrix-style typewriter for assistant streaming: letters decode one by one with a green
 * scrambling trail, ported from xNotificationSystem's Typewriter. Used in LiveBubble when
 * the user enables "Matrix stream" in settings.
 *
 * Design notes:
 * - The revealed counter belongs to the turn, not to the text. A streaming delta only raises
 *   the target count, so the reveal continues from where it was instead of restarting at zero.
 * - Only the revealed prefix is laid out. Hidden letters are not padded with transparent text,
 *   otherwise the bubble takes its final height and wrapping before a single letter is decoded.
 * - The reveal rate follows the measured incoming token rate (with a margin), so a fast stream
 *   decodes fast. When the backlog grows past a line, it drains in whole-line jumps instead of
 *   crawling behind the model forever.
 *
 * IMPORTANT: remote-build.py compiles the tree on the PC over SMB
 * (C:\DEV\Android\Projects\xUmbra), not /home/pi/MaiconErick/xUmbra. Edits must land on the share
 * copy or the build silently keeps the old file.
 */
object MatrixStream {
    const val MAX_MS = 4000L
    const val TRAIL = 3
    const val CURSOR_TAIL_MS = 1200L

    /** Tick of the reveal loop. Lower = smoother, at the cost of more recompositions. */
    const val TICK_MS = 32L

    /** After the last delta, idle for this long before parking the loop. */
    const val IDLE_STOP_MS = 3000L

    /** Max letters revealed in a single animation tick (roughly one wrapped line). */
    const val MAX_JUMP = 56

    /** Reveal rate relative to the measured incoming letter rate. */
    const val CATCH_UP_FACTOR = 1.35f

    /** Backlog, in letters, above which pacing is dropped and the text is flushed. */
    const val BACKLOG_LINE = 48

    /** Time budget to flush a full backlog. */
    const val FLUSH_MS = 450f

    private const val GLYPHS = "ｦｱｳｴｵｶｷｹｺｻｼｽｾｿﾀﾂﾃﾅﾆﾇﾈﾊﾋﾎﾏﾐﾑﾒﾓﾔﾕﾗﾘﾜ0123456789Z:.=*+<>"
    val Green = Color(0xFF39FF6A)

    fun graphemes(text: String): List<String> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val out = ArrayList<String>(text.length.coerceAtLeast(1))
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out += text.substring(start, end)
            start = end; end = it.next()
        }
        return if (out.isEmpty()) listOf("") else out
    }

    fun msPerLetter(count: Int): Long {
        val base = App.prefs.matrixStreamSpeed.toLong().coerceIn(5, 200)
        return if (count <= 0) base else minOf(base, MAX_MS / count).coerceAtLeast(5)
    }

    /** Letters per second allowed by the user's speed setting. */
    fun baseRate(count: Int): Float = 1000f / msPerLetter(count).toFloat()

    fun glyph(seed: Long): Char = GLYPHS[((seed * 1103515245L + 12345L) ushr 8).mod(GLYPHS.length.toLong()).toInt()]
}

@Composable
fun MatrixStreamText(
    text: String,
    key: Any,
    animate: Boolean,
    size: Float = 15f * App.prefs.textScale,
) {
    val letters = remember(text) { MatrixStream.graphemes(text) }
    // Char offset of every grapheme, so the settled prefix can be appended in a single slice.
    val offsets = remember(letters) {
        val out = IntArray(letters.size + 1)
        var acc = 0
        for (i in letters.indices) { out[i] = acc; acc += letters[i].length }
        out[letters.size] = acc
        out
    }
    val count = letters.size

    // Revealed letters, shared by the whole turn so deltas extend it instead of resetting it.
    var shown by remember(key) { mutableIntStateOf(if (animate) 0 else Int.MAX_VALUE / 2) }
    // Wall clock for the scramble glyphs and the cursor blink.
    var now by remember(key) { mutableLongStateOf(System.currentTimeMillis()) }
    // When the last pending letter arrived, to keep the cursor blinking a bit after the end.
    var doneAt by remember(key) { mutableLongStateOf(0L) }

    val target by rememberUpdatedState(count)

    // Keyed only by the turn: the ticker keeps running across deltas, so waiting for the next
    // letter never gets interrupted by recomposition of the bubble.
    LaunchedEffect(key, animate) {
        if (!animate) return@LaunchedEffect
        var pos = 0f
        var lastTick = System.currentTimeMillis()
        var lastTarget = 0
        var streamRate = 0f // smoothed letters/second arriving from the model
        while (true) {
            delay(MatrixStream.TICK_MS)
            val t = System.currentTimeMillis()
            val dt = (t - lastTick).coerceAtLeast(1L)
            lastTick = t
            now = t

            val tgt = target
            if (tgt > lastTarget) {
                val inst = (tgt - lastTarget) * 1000f / dt
                streamRate = if (streamRate <= 0f) inst else streamRate * 0.7f + inst * 0.3f
                lastTarget = tgt
            } else {
                streamRate *= 0.85f
                if (streamRate < 1f) streamRate = 0f
            }

            if (pos < tgt) {
                val backlog = tgt - pos
                var rate = maxOf(MatrixStream.baseRate(tgt), streamRate * MatrixStream.CATCH_UP_FACTOR)
                if (backlog > MatrixStream.BACKLOG_LINE) {
                    rate = maxOf(rate, backlog * 1000f / MatrixStream.FLUSH_MS)
                }
                val step = (rate * dt / 1000f).coerceAtMost(MatrixStream.MAX_JUMP.toFloat())
                pos = (pos + step).coerceAtMost(tgt.toFloat())
                shown = pos.toInt()
                doneAt = 0L
            } else if (tgt > 0 && doneAt == 0L) {
                doneAt = t
            } else if (doneAt > 0L && t - doneAt > MatrixStream.IDLE_STOP_MS) {
                // Nothing new for a while: park the loop instead of recomposing forever.
                break
            }
        }
    }

    val visible = shown.coerceIn(0, count)
    val done = count > 0 && visible >= count
    val pending = !done || (doneAt > 0L && now - doneAt < MatrixStream.CURSOR_TAIL_MS)
    val cursorOn = pending && (now % 520) < 300
    val glow = SpanStyle(color = MatrixStream.Green, shadow = Shadow(MatrixStream.Green.copy(alpha = 0.7f), blurRadius = 8f))
    val frame = now / 50
    val cursor = SpanStyle(color = if (cursorOn) MatrixStream.Green else Color.Transparent, shadow = Shadow(MatrixStream.Green.copy(alpha = 0.6f), blurRadius = 6f))

    // Only the revealed prefix is laid out: settled letters go out as one span, and only the
    // scrambling tail costs a span per character.
    val styled = buildAnnotatedString {
        if (visible > 0) {
            val tailStart = (visible - MatrixStream.TRAIL).coerceAtLeast(0)
            if (tailStart > 0) {
                withStyle(SpanStyle(color = Spook.Bone)) { append(text.substring(0, offsets[tailStart])) }
            }
            for (i in tailStart until visible) {
                val l = letters[i]
                val age = visible - 1 - i
                if (done || age >= MatrixStream.TRAIL) {
                    withStyle(SpanStyle(color = Spook.Bone)) { append(l) }
                } else {
                    withStyle(glow.copy(color = MatrixStream.Green.copy(alpha = 1f - age * 0.18f))) {
                        append(if (age < 2 && l.isNotBlank()) MatrixStream.glyph(frame * 31 + i).toString() else l)
                    }
                }
            }
            if (visible < count) {
                val next = letters[visible]
                withStyle(cursor) { append(if (next.isBlank()) next else "_") }
            }
        }
        if (done && animate) withStyle(cursor) { append("_") }
    }
    Text(styled, fontSize = size.sp, overflow = TextOverflow.Visible, lineHeight = (size * 1.35f).sp)
}
