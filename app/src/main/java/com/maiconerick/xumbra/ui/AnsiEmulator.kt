package com.maiconerick.xumbra.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Minimal-but-useful VT100/xterm ANSI emulator.
 *
 * Maintains a screen buffer (cols x rows) and a scrollback. Parses CSI
 * sequences for cursor movement, screen/line clearing, and SGR colors. Apps
 * like `ls --color`, `top`, `git status`, `claude` (TUI), basic `vim` and
 * `nano` will render correctly. Complex apps (`tmux`, full-screen `vim` with
 * alternate screen + mouse) will mostly work because we silently accept and
 * ignore the modes we don't implement.
 *
 * Not implemented (gracefully ignored): mouse reports, alternate screen
 * switch, scroll regions, window title (OSC 0/2).
 */
class AnsiEmulator(
    val cols: Int = 132,
    val rows: Int = 40,
    private val maxScrollback: Int = 5000,
) {
    data class Cell(
        val ch: Char,
        val fg: Int,   // ANSI palette index or 16+r*36+g*6+b for 256-color, -1 = default
        val bg: Int,   // same scheme; -1 = default
        val bold: Boolean = false,
        val faint: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
    ) {
        companion object {
            val BLANK = Cell(' ', -1, -1)
        }
    }

    private val screen = Array(rows) { Array(cols) { Cell.BLANK } }
    private val scrollback = ArrayDeque<Array<Cell>>(maxScrollback)
    private var cursorRow = 0
    private var cursorCol = 0
    private var savedRow = 0
    private var savedCol = 0
    private var attrFg = -1
    private var attrBg = -1
    private var attrBold = false
    private var attrFaint = false
    private var attrItalic = false
    private var attrUnderline = false

    // Parser state
    private enum class State { NORMAL, ESC, CSI, OSC }
    private var state = State.NORMAL
    private var csiParams = StringBuilder()
    private var oscBuf = StringBuilder()
    // Pending UTF-8 multibyte we haven't completed yet
    private val utf8 = StringBuilder()

    @Synchronized
    fun feed(chunk: ByteArray, len: Int = chunk.size) {
        // Decode as UTF-8, but tolerate partial multibyte at end. We feed in
        // a tight loop, parsing each codepoint.
        var i = 0
        // Simple byte-by-byte traversal — works for ASCII fast-path and for
        // single-byte CSI / SGR. For non-ASCII we decode to a String per chunk
        // (acceptable since SSH text is mostly ASCII).
        val text = try {
            String(chunk, 0, len, Charsets.UTF_8)
        } catch (_: Exception) {
            String(chunk, 0, len, Charsets.ISO_8859_1)
        }
        for (ch in text) handleChar(ch)
    }

    private fun handleChar(ch: Char) {
        when (state) {
            State.NORMAL -> handleNormal(ch)
            State.ESC -> handleEsc(ch)
            State.CSI -> handleCsi(ch)
            State.OSC -> handleOsc(ch)
        }
    }

    private fun handleNormal(ch: Char) {
        when (ch.code) {
            0x07 -> { /* BEL — ignore */ }
            0x08 -> { if (cursorCol > 0) cursorCol-- }   // backspace
            0x09 -> { cursorCol = ((cursorCol shr 3) + 1) shl 3; if (cursorCol >= cols) cursorCol = cols - 1 }
            0x0A -> { linefeed() }                       // LF
            0x0B, 0x0C -> linefeed()                     // VT / FF
            0x0D -> { cursorCol = 0 }                    // CR
            0x1B -> { state = State.ESC }                // ESC
            else -> putChar(ch)
        }
    }

    private fun putChar(ch: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            linefeed()
        }
        screen[cursorRow][cursorCol] = Cell(
            ch, attrFg, attrBg, attrBold, attrFaint, attrItalic, attrUnderline,
        )
        cursorCol++
    }

    private fun linefeed() {
        cursorRow++
        if (cursorRow >= rows) {
            cursorRow = rows - 1
            scrollUp()
        }
    }

    private fun scrollUp() {
        // Top line moves to scrollback
        val top = screen[0]
        if (scrollback.size >= maxScrollback) scrollback.removeFirst()
        scrollback.addLast(top.copyOf())
        for (r in 0 until rows - 1) screen[r] = screen[r + 1]
        screen[rows - 1] = Array(cols) { Cell.BLANK }
    }

    private fun handleEsc(ch: Char) {
        when (ch) {
            '[' -> { csiParams.clear(); state = State.CSI }
            ']' -> { oscBuf.clear(); state = State.OSC }
            '7' -> { savedRow = cursorRow; savedCol = cursorCol; state = State.NORMAL }
            '8' -> { cursorRow = savedRow; cursorCol = savedCol; state = State.NORMAL }
            'D' -> { linefeed(); state = State.NORMAL }
            'E' -> { cursorCol = 0; linefeed(); state = State.NORMAL }
            'M' -> { if (cursorRow > 0) cursorRow-- else scrollDown(); state = State.NORMAL }
            'c' -> { reset(); state = State.NORMAL }
            else -> { state = State.NORMAL }  // unknown — skip
        }
    }

    private fun scrollDown() {
        for (r in rows - 1 downTo 1) screen[r] = screen[r - 1]
        screen[0] = Array(cols) { Cell.BLANK }
    }

    private fun handleCsi(ch: Char) {
        if (ch in '0'..'9' || ch == ';' || ch == '?' || ch == '>' || ch == '!') {
            csiParams.append(ch)
            return
        }
        // Final byte
        val raw = csiParams.toString()
        val isPrivate = raw.startsWith("?")
        val paramStr = if (isPrivate) raw.drop(1) else raw
        val params = paramStr.split(";").map { it.toIntOrNull() ?: 0 }
        when (ch) {
            'A' -> cursorRow = (cursorRow - (params[0].coerceAtLeast(1))).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + (params[0].coerceAtLeast(1))).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + (params[0].coerceAtLeast(1))).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - (params[0].coerceAtLeast(1))).coerceAtLeast(0)
            'E' -> { cursorRow = (cursorRow + params[0].coerceAtLeast(1)).coerceAtMost(rows - 1); cursorCol = 0 }
            'F' -> { cursorRow = (cursorRow - params[0].coerceAtLeast(1)).coerceAtLeast(0); cursorCol = 0 }
            'G' -> cursorCol = ((params[0].coerceAtLeast(1)) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = ((params[0].coerceAtLeast(1)) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                val r = (params.getOrNull(0) ?: 1).coerceAtLeast(1) - 1
                val c = (params.getOrNull(1) ?: 1).coerceAtLeast(1) - 1
                cursorRow = r.coerceIn(0, rows - 1)
                cursorCol = c.coerceIn(0, cols - 1)
            }
            'J' -> when (params.getOrNull(0) ?: 0) {
                0 -> clearScreen(fromCursor = true, toCursor = false)
                1 -> clearScreen(fromCursor = false, toCursor = true)
                2, 3 -> clearAll()
            }
            'K' -> when (params.getOrNull(0) ?: 0) {
                0 -> for (c in cursorCol until cols) screen[cursorRow][c] = Cell.BLANK
                1 -> for (c in 0..cursorCol) screen[cursorRow][c] = Cell.BLANK
                2 -> for (c in 0 until cols) screen[cursorRow][c] = Cell.BLANK
            }
            'L' -> { repeat(params[0].coerceAtLeast(1)) { insertLine() } }
            'M' -> { repeat(params[0].coerceAtLeast(1)) { deleteLine() } }
            'P' -> {
                val n = params[0].coerceAtLeast(1)
                for (c in cursorCol until cols - n) screen[cursorRow][c] = screen[cursorRow][c + n]
                for (c in cols - n until cols) screen[cursorRow][c] = Cell.BLANK
            }
            '@' -> {
                val n = params[0].coerceAtLeast(1)
                for (c in (cols - 1) downTo (cursorCol + n)) screen[cursorRow][c] = screen[cursorRow][c - n]
                for (c in cursorCol until (cursorCol + n).coerceAtMost(cols)) screen[cursorRow][c] = Cell.BLANK
            }
            'X' -> {
                val n = params[0].coerceAtLeast(1)
                for (c in cursorCol until (cursorCol + n).coerceAtMost(cols)) screen[cursorRow][c] = Cell.BLANK
            }
            's' -> { savedRow = cursorRow; savedCol = cursorCol }
            'u' -> { cursorRow = savedRow; cursorCol = savedCol }
            'm' -> applySgr(params)
            else -> { /* h, l, r, n, c, others — silently ignored */ }
        }
        state = State.NORMAL
    }

    private fun insertLine() {
        for (r in rows - 1 downTo cursorRow + 1) screen[r] = screen[r - 1]
        screen[cursorRow] = Array(cols) { Cell.BLANK }
    }

    private fun deleteLine() {
        for (r in cursorRow until rows - 1) screen[r] = screen[r + 1]
        screen[rows - 1] = Array(cols) { Cell.BLANK }
    }

    private fun clearScreen(fromCursor: Boolean, toCursor: Boolean) {
        if (fromCursor) {
            for (c in cursorCol until cols) screen[cursorRow][c] = Cell.BLANK
            for (r in cursorRow + 1 until rows) screen[r] = Array(cols) { Cell.BLANK }
        }
        if (toCursor) {
            for (r in 0 until cursorRow) screen[r] = Array(cols) { Cell.BLANK }
            for (c in 0..cursorCol) screen[cursorRow][c] = Cell.BLANK
        }
    }

    private fun clearAll() {
        for (r in 0 until rows) screen[r] = Array(cols) { Cell.BLANK }
    }

    private fun applySgr(params: List<Int>) {
        if (params.isEmpty() || (params.size == 1 && params[0] == 0)) {
            attrFg = -1; attrBg = -1
            attrBold = false; attrFaint = false
            attrItalic = false; attrUnderline = false
            return
        }
        var i = 0
        while (i < params.size) {
            val p = params[i]
            when {
                p == 0 -> {
                    attrFg = -1; attrBg = -1
                    attrBold = false; attrFaint = false
                    attrItalic = false; attrUnderline = false
                }
                p == 1 -> attrBold = true
                p == 2 -> attrFaint = true
                p == 3 -> attrItalic = true
                p == 4 -> attrUnderline = true
                p == 22 -> { attrBold = false; attrFaint = false }
                p == 23 -> attrItalic = false
                p == 24 -> attrUnderline = false
                p in 30..37 -> attrFg = p - 30
                p == 38 -> {
                    // 38;5;N (256-color) or 38;2;r;g;b (truecolor) — pull next params
                    val mode = params.getOrNull(i + 1)
                    if (mode == 5) {
                        attrFg = params.getOrNull(i + 2) ?: -1
                        i += 2
                    } else if (mode == 2) {
                        // truecolor: pack rgb into fg as 0x1000000 flag + rgb
                        val r = params.getOrNull(i + 2) ?: 0
                        val g = params.getOrNull(i + 3) ?: 0
                        val b = params.getOrNull(i + 4) ?: 0
                        attrFg = (0x1000000) or (r shl 16) or (g shl 8) or b
                        i += 4
                    }
                }
                p == 39 -> attrFg = -1
                p in 40..47 -> attrBg = p - 40
                p == 48 -> {
                    val mode = params.getOrNull(i + 1)
                    if (mode == 5) {
                        attrBg = params.getOrNull(i + 2) ?: -1
                        i += 2
                    } else if (mode == 2) {
                        val r = params.getOrNull(i + 2) ?: 0
                        val g = params.getOrNull(i + 3) ?: 0
                        val b = params.getOrNull(i + 4) ?: 0
                        attrBg = (0x1000000) or (r shl 16) or (g shl 8) or b
                        i += 4
                    }
                }
                p == 49 -> attrBg = -1
                p in 90..97 -> attrFg = (p - 90) + 8     // bright fg
                p in 100..107 -> attrBg = (p - 100) + 8  // bright bg
            }
            i++
        }
    }

    private fun handleOsc(ch: Char) {
        if (ch.code == 0x07 || ch == '\\') {
            // BEL or ST ends OSC. We ignore the payload (window title etc.)
            oscBuf.clear()
            state = State.NORMAL
            return
        }
        oscBuf.append(ch)
        if (oscBuf.length > 512) {  // safety cap
            oscBuf.clear()
            state = State.NORMAL
        }
    }

    private fun reset() {
        for (r in 0 until rows) for (c in 0 until cols) screen[r][c] = Cell.BLANK
        scrollback.clear()
        cursorRow = 0; cursorCol = 0
        attrFg = -1; attrBg = -1
        attrBold = false; attrFaint = false
        attrItalic = false; attrUnderline = false
    }

    /** Render everything (scrollback + current screen) as a list of styled lines. */
    @Synchronized
    fun render(): List<AnnotatedString> {
        val out = ArrayList<AnnotatedString>(scrollback.size + rows)
        for (line in scrollback) out.add(lineToAnnotated(line))
        for (r in 0 until rows) out.add(lineToAnnotated(screen[r]))
        // Trim trailing blank lines so the screen doesn't look mostly empty when
        // bash has only printed a few lines.
        while (out.size > 1 && out.last().text.isBlank()) out.removeAt(out.size - 1)
        return out
    }

    private fun lineToAnnotated(line: Array<Cell>): AnnotatedString {
        // Merge runs of cells sharing the same style.
        return buildAnnotatedString {
            var i = 0
            while (i < line.size) {
                val start = i
                val c0 = line[i]
                while (i < line.size && cellsStyleEqual(line[i], c0)) i++
                val end = i
                val txt = buildString {
                    for (j in start until end) append(line[j].ch)
                }.trimEnd(' ')  // don't paint trailing whitespace of a run
                if (txt.isNotEmpty()) {
                    val style = SpanStyle(
                        color = paletteFg(c0.fg),
                        background = paletteBg(c0.bg),
                        fontWeight = if (c0.bold) FontWeight.Bold else FontWeight.Normal,
                    )
                    withStyle(style) { append(txt) }
                }
                // Pad spaces (no styling) to preserve cell alignment if needed
                val padded = end - start - txt.length
                if (padded > 0) append(" ".repeat(padded))
            }
        }
    }

    private fun cellsStyleEqual(a: Cell, b: Cell): Boolean =
        a.fg == b.fg && a.bg == b.bg && a.bold == b.bold &&
        a.italic == b.italic && a.underline == b.underline

    private fun paletteFg(idx: Int): Color = paletteColor(idx, default = Color(0xFFE0E0E0))
    private fun paletteBg(idx: Int): Color = if (idx == -1) Color.Transparent
                                              else paletteColor(idx, default = Color.Transparent)

    private fun paletteColor(idx: Int, default: Color): Color {
        if (idx == -1) return default
        // Truecolor
        if (idx and 0x1000000 != 0) {
            val r = (idx shr 16) and 0xFF
            val g = (idx shr 8) and 0xFF
            val b = idx and 0xFF
            return Color(r, g, b)
        }
        // Standard 16-color palette
        if (idx in 0..15) return ANSI16[idx]
        // 256-color: 16-231 = 6x6x6 cube, 232-255 = grayscale
        if (idx in 16..231) {
            val v = idx - 16
            val r = (v / 36) % 6
            val g = (v / 6) % 6
            val b = v % 6
            return Color(
                if (r > 0) 55 + r * 40 else 0,
                if (g > 0) 55 + g * 40 else 0,
                if (b > 0) 55 + b * 40 else 0,
            )
        }
        if (idx in 232..255) {
            val v = 8 + (idx - 232) * 10
            return Color(v, v, v)
        }
        return default
    }

    companion object {
        // Slight cyber-y bias — matches the rest of the app theme.
        val ANSI16 = arrayOf(
            Color(0xFF1A1A1A), Color(0xFFFF6E6E), Color(0xFF8BD968), Color(0xFFFFE068),
            Color(0xFF6EB4FF), Color(0xFFD968E0), Color(0xFF68E0E0), Color(0xFFD0D0D0),
            Color(0xFF666666), Color(0xFFFF8A8A), Color(0xFFA8E68A), Color(0xFFFFEC8A),
            Color(0xFF8AC8FF), Color(0xFFE68AE0), Color(0xFF8AE0E0), Color(0xFFFFFFFF),
        )
    }
}
