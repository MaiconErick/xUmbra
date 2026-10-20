package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.maiconerick.xumbra.App
import com.maiconerick.xumbra.data.Net
import com.maiconerick.xumbra.ui.theme.Spook

/** Markdown blocks the replies use. Parsing is line based and tolerant of half-streamed text. */
sealed class Block {
    data class Para(val text: String) : Block()
    data class Heading(val level: Int, val text: String) : Block()
    data class Code(val lang: String, val code: String, val open: Boolean) : Block()
    data class Bullet(val indent: Int, val marker: String, val text: String) : Block()
    data class Quote(val text: String) : Block()
    data class Table(val rows: List<List<String>>, val aligns: List<String> = emptyList()) : Block()
    data class Image(val alt: String, val url: String) : Block()
    object Rule : Block()
}

fun parseMarkdown(src: String): List<Block> {
    val out = mutableListOf<Block>()
    // pictures (![alt](url)) always get their own line so they render as blocks
    val lines = src.replace("\r\n", "\n").split('\n').flatMap { l ->
        if (!l.contains("![") || l.trim().startsWith("```")) listOf(l)
        else imageRe.replace(l) { "\n" + it.value + "\n" }.split('\n').filter { it.isNotBlank() }
    }
    var i = 0
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val lang = t.removePrefix("```").trim()
                val code = StringBuilder(); i++
                var closed = false
                while (i < lines.size) {
                    if (lines[i].trim().startsWith("```")) { closed = true; break }
                    code.append(lines[i]).append('\n'); i++
                }
                out += Block.Code(lang, code.toString().trimEnd('\n'), !closed)
            }
            t.isEmpty() -> flush()
            imageLineRe.matches(t) -> { flush(); val m = imageLineRe.find(t)!!; out += Block.Image(m.groupValues[1], m.groupValues[2]) }
            Regex("^#{1,6}\\s").containsMatchIn(t) -> { flush(); val lv = t.takeWhile { it == '#' }.length; out += Block.Heading(lv, t.drop(lv).trim()) }
            Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> { flush(); out += Block.Rule }
            t.startsWith(">") -> { flush(); out += Block.Quote(t.removePrefix(">").trim()) }
            Regex("^\\s*([-*+•]|\\d{1,3}[.)])\\s+").containsMatchIn(line) -> {
                flush()
                val m = Regex("^(\\s*)([-*+•]|\\d{1,3}[.)])\\s+(.*)").find(line)!!
                val indent = m.groupValues[1].replace("\t", "    ").length / 2
                val mk = m.groupValues[2].let { if (it in setOf("-", "*", "+", "•")) "•" else it }
                out += Block.Bullet(indent.coerceAtMost(4), mk, m.groupValues[3])
            }
            t.startsWith("|") && t.count { it == '|' } >= 2 -> {
                flush()
                val rows = mutableListOf<List<String>>()
                var aligns = emptyList<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val cells = lines[i].trim().trim('|').split('|').map { it.trim() }
                    if (cells.all { Regex("^:?-{2,}:?$").matches(it) })
                        aligns = cells.map { c -> if (c.startsWith(":") && c.endsWith(":")) "c" else if (c.endsWith(":")) "r" else "l" }
                    else rows += cells
                    i++
                }
                out += Block.Table(rows, aligns); continue
            }
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    flush()
    return out
}

private val imageRe = Regex("""!\[([^\]]*)]\((https?://[^)\s]+)\)""")
private val imageLineRe = Regex("""^!\[([^\]]*)]\((https?://[^)\s]+)\)$""")

private val inlineRe = Regex(
    """(\*\*|__)(.+?)\1|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)|(?<![\w_])_(?!\s)(.+?)(?<!\s)_(?![\w])|~~(.+?)~~|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)|(https?://[^\s<>()\[\]]+)|\{([nvadpor])\|([^\{\}]*)\}"""
)

private val boldItalicRe = Regex("""\*\*\*(.+?)\*\*\*""")

fun inline(raw: String, link: Color, codeBg: Color = Color(0x33FFFFFF)): AnnotatedString = styleJapanese(inlineRaw(hidePartialTag(raw), link, codeBg))

private fun inlineRaw(raw: String, link: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    // ***bold italic*** → **_bold italic_** so the bold rule wraps an italic one
    val text = if (raw.contains("***")) boldItalicRe.replace(raw) { "**_" + it.groupValues[1] + "_**" } else raw
    var pos = 0
    for (m in inlineRe.findAll(text)) {
        if (m.range.first < pos) continue
        append(text.substring(pos, m.range.first))
        val g = m.groupValues
        when {
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inlineRaw(g[2], link, codeBg)) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inlineRaw(g[3], link, codeBg)) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inlineRaw(g[4], link, codeBg)) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[5]) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 0.92.em)) { append(" " + g[6] + " ") }
            g[7].isNotEmpty() -> withLink(LinkAnnotation.Url(g[8], TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(g[7]) }
            g[10].isNotEmpty() -> if (g[10] != "r") {
                // a word the tutor tagged (noun/verb/…): its own colour, and remembered so kanji/kana colouring skips it
                val start = length
                if (App.prefs.jpColors) withStyle(SpanStyle(color = Jp.color(g[10]))) { append(inlineRaw(g[11], link, codeBg)) }
                else append(inlineRaw(g[11], link, codeBg))
                addStringAnnotation("jp", g[10], start, length)
            }
            else -> {
                val url = g[9].trimEnd('.', ',', ';', ':', '!', '?')
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(url) }
                append(g[9].substring(url.length))
            }
        }
        pos = m.range.last + 1
    }
    if (pos < text.length) append(text.substring(pos))
}

private val Double.em get() = TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

@Composable
fun Markdown(text: String, color: Color = Spook.Bone, size: Float = 15f, cursor: Boolean = false, onImage: ((String) -> Unit)? = null) {
    val blocks = remember(text) { parseMarkdown(text) }
    val link = Spook.Moon
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { idx, b ->
            val last = cursor && idx == blocks.lastIndex
            when (b) {
                is Block.Para -> if (b.text.contains("{r|") && b.text.contains("\n")) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val ls = b.text.split("\n")
                    ls.forEachIndexed { i, l -> JpLine(l, last && i == ls.lastIndex, color, size, 1.38f, link) }
                } else JpLine(b.text, last, color, size, 1.38f, link)
                is Block.Heading -> Text(inline(b.text, link), color = if (b.level <= 2) Spook.Pumpkin else color, fontWeight = FontWeight.Bold,
                    fontSize = (size + when (b.level) { 1 -> 5f; 2 -> 3f; 3 -> 1.5f; else -> 0f }).sp, modifier = Modifier.padding(top = 2.dp))
                is Block.Code -> CodeBlock(b)
                is Block.Bullet -> Row(Modifier.padding(start = (b.indent * 14).dp)) {
                    Text(if (b.marker == "•") (if (b.indent % 2 == 0) "•" else "◦") else b.marker, color = Spook.Pumpkin, fontSize = size.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 16.dp).padding(end = 6.dp))
                    JpLine(b.text, last, color, size, 1.36f, link, Modifier.weight(1f))
                }
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Spook.Pumpkin))
                    Text(inline(b.text, link), color = color.copy(alpha = 0.85f), fontStyle = FontStyle.Italic, fontSize = size.sp, modifier = Modifier.padding(start = 8.dp))
                }
                is Block.Table -> TableBlock(b, color, size)
                Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(Spook.Fog))
                is Block.Image -> WebPicture(b, onImage)
            }
        }
        if (cursor && blocks.isEmpty()) Text("▍", color = color, fontSize = size.sp)
    }
}

/**
 * A line of text. When the tutor appended {r|romaji} to it, an eye icon sits at its end and shows the romaji below on tap.
 * Bigger kanji get a taller line so rows don't touch.
 */
@Composable
private fun JpLine(raw: String, last: Boolean, color: Color, size: Float, mult: Float, link: Color, modifier: Modifier = Modifier) {
    val (body, roma) = remember(raw) { splitRomaji(raw) }
    var show by remember { mutableStateOf(false) }
    val shown = hidePartialTag(body) + if (last) " ▍" else ""
    Column(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Text(inline(shown, link), color = color, fontSize = size.sp, lineHeight = (size * mult * Jp.lineBoost(body)).sp, modifier = Modifier.weight(1f))
            if (roma != null) Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "romaji",
                tint = if (show) Spook.Pumpkin else Spook.Mist,
                modifier = Modifier.padding(start = 4.dp).size(30.dp).clip(RoundedCornerShape(50)).clickable { show = !show }.padding(6.dp))
        }
        if (roma != null && show) Text(roma, color = Spook.Moon, fontStyle = FontStyle.Italic, fontSize = (size - 1f).sp, lineHeight = (size * 1.3f).sp,
            modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun CodeBlock(b: Block.Code) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xCC07040C)).border(1.dp, Spook.Crypt3, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().background(Spook.Crypt3.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(b.lang.ifBlank { "code" }, color = Spook.Mist, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(if (copied) "✓ copied" else "⧉ copy", color = Spook.Pumpkin, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { copyText(ctx, b.code); copied = true }.padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Text(b.code, color = Color(0xFFE6F1D8), fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 17.sp, softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp))
    }
}

private val numericCell = Regex("""^[-+]?[${'$'}R€£]?\s?[\d.,]+\s?(%|[kKMGT]?B|MB|GB|KB|ms|s|h|min|x|×)?$""")

/** A table that reads like a spreadsheet: grid lines, row numbers, numbers right-aligned, ⤢ for full screen. */
@Composable
private fun TableBlock(b: Block.Table, color: Color, size: Float) {
    if (b.rows.isEmpty()) return
    var full by remember { mutableStateOf(false) }
    if (full) androidx.compose.ui.window.Dialog(onDismissRequest = { full = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xF00A0710)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${b.rows.size - 1} rows × ${b.rows.maxOf { it.size }} columns", color = Spook.Mist, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("✕", color = Spook.Bone, fontSize = 20.sp, modifier = Modifier.clickable { full = false }.padding(8.dp))
            }
            Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { SheetGrid(b, color, size + 1f) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("▦ ${b.rows.size - 1} rows", color = Spook.Mist, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
            Text("⤢ full screen", color = Spook.Pumpkin, fontSize = 10.5.sp, modifier = Modifier.clickable { full = true }.padding(horizontal = 4.dp, vertical = 2.dp))
        }
        SheetGrid(b, color, size)
    }
}

@Composable
private fun SheetGrid(b: Block.Table, color: Color, size: Float) {
    val cols = b.rows.maxOf { it.size }
    val body = b.rows.drop(1)
    // a column is numeric when most of its body cells look like numbers → right-aligned, monospace
    val numeric = remember(b) { List(cols) { c -> body.isNotEmpty() && body.count { numericCell.matches(it.getOrElse(c) { "" }.replace("**", "")) } * 2 > body.size } }
    // one width per column so the grid lines up (from the longest text in it)
    val widths = remember(b) { List(cols) { c -> (b.rows.maxOf { it.getOrElse(c) { "" }.length } * 7.2f + 20f).coerceIn(52f, 260f) } }
    val grid = Spook.Crypt3
    Column(Modifier.horizontalScroll(rememberScrollState()).clip(RoundedCornerShape(6.dp)).border(1.dp, grid, RoundedCornerShape(6.dp))) {
        b.rows.forEachIndexed { r, row ->
            Row(Modifier.height(IntrinsicSize.Min).background(when {
                r == 0 -> Spook.Crypt3.copy(alpha = 0.95f)
                r % 2 == 0 -> Spook.Crypt2.copy(alpha = 0.55f)
                else -> Color(0x22000000)
            })) {
                // row number gutter, like a spreadsheet
                Text(if (r == 0) "" else "$r", color = Spook.Mist.copy(alpha = 0.7f), fontSize = (size - 3.5f).sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(26.dp).fillMaxHeight().background(Color(0x33000000)).padding(top = 5.dp, end = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                for (c in 0 until cols) {
                    val cell = row.getOrElse(c) { "" }
                    val align = b.aligns.getOrNull(c) ?: if (numeric[c]) "r" else "l"
                    Box(Modifier.width(widths[c].dp).fillMaxHeight().border(0.5.dp, grid.copy(alpha = 0.7f))
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                        contentAlignment = when { r == 0 -> Alignment.CenterStart; align == "r" -> Alignment.TopEnd; align == "c" -> Alignment.TopCenter; else -> Alignment.TopStart }) {
                        Text(inline(cell, Spook.Moon), color = if (r == 0) Spook.Pumpkin else color,
                            fontSize = (size - 1.5f).sp, lineHeight = ((size - 1.5f) * 1.3f).sp,
                            fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                            fontFamily = if (r > 0 && numeric[c]) FontFamily.Monospace else null)
                    }
                }
            }
        }
    }
}

/** A picture the model placed in its reply; fetched through the Pi (proper User-Agent + cache). */
@Composable
private fun WebPicture(b: Block.Image, onImage: ((String) -> Unit)?) {
    val url = Net.url("/api/v2/img-proxy?u=" + Net.enc(b.url))
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        PictureWithActions(url, onImage) { m ->
            AsyncImage(url, b.alt, contentScale = ContentScale.FillWidth, onError = { failed = true },
                modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 260.dp).clip(RoundedCornerShape(10.dp)).then(m))
        }
        if (b.alt.isNotBlank()) Text(b.alt, color = Spook.Mist, fontSize = 11.sp, fontStyle = FontStyle.Italic)
    }
}
