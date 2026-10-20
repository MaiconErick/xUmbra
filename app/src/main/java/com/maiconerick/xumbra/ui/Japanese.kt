package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.App
import com.maiconerick.xumbra.ui.theme.Spook

/**
 * Japanese study helpers. The Sensei persona marks words in its example sentences:
 *   {n|学校(がっこう)} noun, {v|…} verb, {a|…} adjective, {d|…} adverb, {p|…} particle, {o|…} other,
 *   {r|romaji text}   the romaji of the whole line, hidden behind an eye icon.
 * Kanji size and colours are settings of this phone; they only touch Japanese characters.
 */
object Jp {
    /** key → label, default colour. "kanji" and "kana" colour untagged text; the rest colour whole tagged words. */
    val kinds: List<Triple<String, String, Long>> = listOf(
        Triple("kanji", "Kanji", 0xFFFFB454),
        Triple("kana", "Kana", 0xFF7FD6FF),
        Triple("v", "Verbs", 0xFFFF6F91),
        Triple("n", "Nouns", 0xFF7BE495),
        Triple("a", "Adjectives", 0xFFC792FF),
        Triple("d", "Adverbs", 0xFF4FD1C5),
        Triple("p", "Particles", 0xFF9AA3B5),
        Triple("o", "Other words", 0xFFE6C07B),
    )

    fun color(kind: String): Color {
        val def = kinds.firstOrNull { it.first == kind }?.third ?: 0xFFFFFFFF
        return Color(App.prefs.jpColor(kind, def))
    }

    fun isHan(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c in '豈'..'﫿' || c == '々' || c == '〆'
    fun isKana(c: Char) = c in '぀'..'ゟ' || c in '゠'..'ヿ' || c in 'ｦ'..'ﾟ'
    fun hasHan(s: String) = s.any { isHan(it) }

    /** Line height multiplier so bigger kanji don't touch the line above. */
    fun lineBoost(text: String): Float {
        val k = App.prefs.kanjiScale
        return if (k > 1f && hasHan(text)) k * 0.92f else 1f
    }
}

private val Float.em get() = TextUnit(this, TextUnitType.Em)

private val romajiTagRe = Regex("""\s*\{r\|([^\{\}]*)\}""")
private val partialTagRe = Regex("""\{[nvadpor]\|[^\{\}]*$|\{[nvadpor]?$""")

/** (text without the romaji tag, romaji or null). */
fun splitRomaji(raw: String): Pair<String, String?> {
    val m = romajiTagRe.find(raw) ?: return raw to null
    val roma = m.groupValues[1].trim()
    return raw.removeRange(m.range).trimEnd() to roma.ifBlank { null }
}

/** A tag still being streamed ("{v|行(い") would flash as raw text; hide it until it is closed. */
fun hidePartialTag(raw: String): String = if (raw.contains('{')) partialTagRe.replace(raw, "") else raw

/** Bigger kanji + kanji/kana colours on top of an already built string. Tagged words keep their own colour. */
fun styleJapanese(a: AnnotatedString): AnnotatedString {
    val t = a.text
    if (t.isEmpty()) return a
    val scale = App.prefs.kanjiScale
    val colors = App.prefs.jpColors
    if (scale <= 1.001f && !colors) return a
    if (t.none { Jp.isHan(it) || Jp.isKana(it) }) return a
    val covered = BooleanArray(t.length)
    for (r in a.getStringAnnotations("jp", 0, t.length)) for (i in r.start until r.end.coerceAtMost(t.length)) covered[i] = true
    val kanjiColor = Jp.color("kanji")
    val kanaColor = Jp.color("kana")
    return buildAnnotatedString {
        append(a)
        var i = 0
        while (i < t.length) {
            val han = Jp.isHan(t[i])
            val kana = !han && Jp.isKana(t[i])
            if (!han && !kana) { i++; continue }
            val cov = covered[i]
            var j = i + 1
            while (j < t.length && covered[j] == cov && (if (han) Jp.isHan(t[j]) else Jp.isKana(t[j]))) j++
            if (han && scale > 1.001f) addStyle(SpanStyle(fontSize = scale.em), i, j)
            if (colors && !cov) addStyle(SpanStyle(color = if (han) kanjiColor else kanaColor), i, j)
            i = j
        }
    }
}

// ───────────── settings ─────────────

private const val SAMPLE = "私(わたし)は{n|学校(がっこう)}{p|に}{v|行(い)きます}。 日本語(にほんご)を勉強(べんきょう)しています。 {a|新(あたら)しい}{n|本(ほん)}{p|を}{v|読(よ)む}"

@Composable
fun JapaneseSettings() {
    val p = App.prefs
    var rev by remember { mutableIntStateOf(0) }
    rev.let { }
    fun set(block: () -> Unit) { block(); rev++ }
    var picking by remember { mutableStateOf<String?>(null) }
    picking?.let { k ->
        val label = Jp.kinds.first { it.first == k }.second
        ColorPickerDialog(label, p.jpColor(k, Jp.kinds.first { it.first == k }.third) and 0xFFFFFFFFL,
            onPick = { v -> set { p.setJpColor(k, if (v == 0L) null else v or 0xFF000000L) }; picking = null }) { picking = null }
    }
    Section("日本語 Japanese text", "Only kanji get bigger; kana and normal letters keep the text size above.") {
        DramaSlider("Kanji size", p.kanjiScale, 1f, 2.5f, "×") { v -> set { p.kanjiScale = v } }
        Toggle("Colour Japanese text", p.jpColors, "Kanji, kana, and (when Sensei tags them) verbs, nouns, adjectives, adverbs and particles") { set { p.jpColors = it } }
        if (p.jpColors) {
            Jp.kinds.forEach { (k, label, def) ->
                val c = p.jpColor(k, def)
                val changed = p.jpColor(k, -1L) != -1L
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { picking = k }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Color(c)).border(1.dp, Spook.Fog, RoundedCornerShape(7.dp)))
                    Text(label, color = Color(c), fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    if (changed) Text("↺", color = Spook.Mist, fontSize = 16.sp, modifier = Modifier.clickable { set { p.setJpColor(k, null) } }.padding(horizontal = 8.dp))
                }
            }
        }
        Text("Preview", color = Spook.Mist, fontSize = 12.sp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Spook.InBubble.copy(alpha = 0.92f)).padding(10.dp)) {
            val line = hidePartialTag(SAMPLE)
            Text(inline(line, Spook.Moon), color = Spook.Bone, fontSize = (15f * p.textScale).sp, lineHeight = (15f * p.textScale * 1.38f * Jp.lineBoost(line)).sp)
        }
    }
}
