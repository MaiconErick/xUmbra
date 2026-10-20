@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maiconerick.xumbra.data.*
import com.maiconerick.xumbra.ui.theme.Spook
import org.json.JSONObject

// ───────────────────────── prices ─────────────────────────

/** A model's OpenRouter entry (prices per 1M tokens), also for direct OpenAI / Gemini models. */
fun priceOf(provider: String, model: String): ExModel? {
    val ex = ExploreCache.data.value ?: return null
    return when (provider) {
        "openai" -> ex.byId["openai/$model"]
        "gemini" -> ex.byId["google/$model"]
        else -> ex.byId[model]
    } ?: ex.byId[model]
}

/** "$0.25 in · $2.00 out /M" — or "free". */
fun priceLine(m: ExModel?): String? = when {
    m == null -> null
    m.free || (m.inPrice == 0.0 && m.outPrice == 0.0) -> "free"
    else -> "$${"%.2f".format(m.inPrice)}${Fmt.brl(m.inPrice)} in · " +
            "$${"%.2f".format(m.outPrice)}${Fmt.brl(m.outPrice)} out /M"
}

// ───────────────────────── composer buttons ─────────────────────────

/** The model button next to the ghost toggle: same plain style as the attach icon. */
@Composable
fun ModelButton(sid: String, st: ChatState) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.AutoAwesome, "Model", tint = Spook.Mist) }
    if (open) ModelSheet(sid, st) { open = false }
}

/** Image chats: the picture-model button, monochrome like the others. */
@Composable
fun ImageModelIcon(sid: String, s: ChatSettings) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.Palette, "Image model", tint = Spook.Mist) }
    if (open) ImageModelPage(sid, s) { open = false }
}

// ───────────────────────── the pop-up ─────────────────────────

private val EFFORTS = listOf(
    "minimal" to "Fastest and cheapest. Barely thinks before answering.",
    "low" to "Thinks a little. Good for most chats.",
    "medium" to "Thinks properly. Better for tricky questions, slower.",
    "high" to "Thinks the longest. Best answers on hard problems, slowest and priciest.",
)

/** Current model, its price, what this chat costs per reply, change it, and how hard it thinks. */
@Composable
fun ModelSheet(sid: String, st: ChatState, onClose: () -> Unit) {
    val s = st.settings
    var pick by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ExploreCache.load() }
    val ex by ExploreCache.data
    if (pick) {
        ModelPickerPage(s?.model ?: s?.effectiveModel, onPick = { m -> Umbra.patch(sid, JSONObject().put("model", m.json())); pick = false }) { pick = false }
        return
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.92f).widthIn(max = 520.dp).clip(RoundedCornerShape(20.dp)).background(Spook.Crypt2)
            .border(1.dp, Spook.Crypt3, RoundedCornerShape(20.dp)).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Spook.Pumpkin, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Model", color = Spook.Bone, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("✕", color = Spook.Mist, fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).padding(6.dp))
            }
            if (s == null) { Text("Loading…", color = Spook.Mist); return@Column }

            // current model
            val em = s.effectiveModel ?: s.model
            val price = em?.let { priceOf(it.provider, it.model) }
            ex.hashCode()   // recompose when prices arrive
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Spook.Crypt3.copy(alpha = 0.6f)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (em != null) ModelLabel(em.provider, em.model, nameSize = 16f, showId = true)
                Text(when (s.modelWhy) { "chat" -> "Picked for this chat"; "persona" -> "Comes from this chat's persona"; else -> "The global default" },
                    color = Spook.Fog, fontSize = 11.5.sp)
                priceLine(price)?.let { Text("💲 $it", color = Spook.Moon, fontSize = 13.sp) }
                if (price != null && price.ctx > 0) Text("Remembers up to ${Fmt.tokens(price.ctx.toLong())} tokens of the chat", color = Spook.Mist, fontSize = 11.5.sp)
                val paid = st.messages.mapNotNull { it.turn }.filter { it.costUsd > 0 }
                if (paid.isNotEmpty()) {
                    val avg = paid.sumOf { it.costUsd } / paid.size
                    Text("This chat: ${Fmt.money(avg)} per reply on average · ${Fmt.money(paid.sumOf { it.costUsd })} over ${paid.size} replies",
                        color = Spook.Bone, fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Change model", true) { pick = true }
                if (s.model != null) Pill("Back to default", false) { Umbra.patch(sid, JSONObject().put("clear_model", true)) }
            }

            // thinking effort
            val thinks = price?.reasoning == true || em?.model?.let { Regex("gpt-5|o\\d|o-|thinking|reason|r1|grok-4", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true
            Text("🧠 Thinking", color = Spook.Pumpkin, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (!thinks) Text("This model doesn't have a thinking setting.", color = Spook.Mist, fontSize = 12.sp)
            else {
                val cur = s.reasoning ?: ""
                Text("Thinking happens before the answer and is billed like answer text" +
                    (price?.takeIf { !it.free }?.let { " ($${"%.2f".format(it.outPrice)}${Fmt.brl(it.outPrice)} per 1M tokens)" } ?: "") +
                    ", so more thinking = slower and pricier replies.", color = Spook.Mist, fontSize = 11.5.sp)
                EffortRow("", "Default", "Whatever this chat's persona uses, otherwise minimal.", cur == "") { Umbra.patch(sid, JSONObject().put("reasoning", "")) }
                EFFORTS.forEach { (k, desc) ->
                    EffortRow(k, k.replaceFirstChar { it.uppercase() }, desc, cur == k) { Umbra.patch(sid, JSONObject().put("reasoning", k)) }
                }
            }
        }
    }
}

@Composable
private fun EffortRow(key: String, title: String, desc: String, on: Boolean, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Spook.Pumpkin.copy(alpha = 0.18f) else Spook.Crypt3.copy(alpha = 0.35f))
        .border(1.dp, if (on) Spook.Pumpkin else Color.Transparent, RoundedCornerShape(12.dp)).clickable(onClick = onPick)
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        val bars = when (key) { "minimal" -> 1; "low" -> 2; "medium" -> 3; "high" -> 4; else -> 0 }
        Row(Modifier.width(30.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            if (bars == 0) Text("auto", color = Spook.Mist, fontSize = 9.sp)
            else (1..4).forEach { i ->
                Box(Modifier.width(5.dp).height((4 + i * 3).dp).clip(RoundedCornerShape(1.dp))
                    .background(if (i <= bars) (if (on) Spook.Pumpkin else Spook.Bone) else Spook.Crypt3))
            }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, color = if (on) Spook.Pumpkin else Spook.Bone, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = Spook.Mist, fontSize = 11.5.sp)
        }
        if (on) Text("✓", color = Spook.Pumpkin, fontSize = 16.sp)
    }
}

// ───────────────────────── one reply's usage bar ─────────────────────────

/** How this reply's tokens split: cached input, new input, output — and how full the model's memory is. */
@Composable
fun UsageBreakdown(t: TurnInfo) {
    LaunchedEffect(Unit) { ExploreCache.load() }
    ExploreCache.data.value   // recompose when prices arrive
    val cached = t.cacheRead.coerceAtLeast(0)
    val fresh = (t.inTok - cached).coerceAtLeast(0)
    val out = t.outTok.coerceAtLeast(0)
    val total = (cached + fresh + out).coerceAtLeast(1)
    val price = priceOf(t.provider, t.model)
    val segs = listOf(
        Triple("Cached", cached, Color(0xFF4FA8FF)),
        Triple("New input", fresh, Spook.Witch),
        Triple("Answer", out, Spook.Pumpkin),
    )
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Spook.Crypt3)) {
            segs.forEach { (_, n, c) -> if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(c)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            segs.forEach { (label, n, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                    Text(" $label ${Fmt.tokens(n.toLong())} (${100 * n / total}%)", color = Spook.Bone, fontSize = 11.sp)
                }
            }
        }
        // what the answer cost vs what reading the conversation cost (output priced exactly; input is the rest)
        if (t.costUsd > 0) {
            val text = (t.costUsd - t.imageCost).coerceAtLeast(0.0)          // pictures are billed apart from tokens
            val outCost = price?.let { out * it.outPrice / 1e6 }?.coerceAtMost(text)
            val bits = mutableListOf("${Fmt.money(t.costUsd)} total")
            if (outCost != null) { bits += "reading ${Fmt.money(text - outCost)}"; bits += "writing ${Fmt.money(outCost)}" }
            if (t.imageCost > 0) bits += "🎨 picture ${Fmt.money(t.imageCost)}"
            Text(bits.joinToString(" · "), color = Spook.Mist, fontSize = 11.sp)
        }
        if (cached > 0) Text("Cached tokens are parts of the chat the model already read last time, billed at a big discount.",
            color = Spook.Fog, fontSize = 10.5.sp)
        if (t.contextWindow > 0) {
            Text("Model memory: ${Fmt.tokens(t.contextTokens.toLong())} of ${Fmt.tokens(t.contextWindow.toLong())} used " +
                "(${"%.1f".format(100.0 * t.contextTokens / t.contextWindow)}%)", color = Spook.Mist, fontSize = 11.sp)
            UsageBar(t.contextTokens.toDouble(), t.contextWindow.toDouble(), Spook.Slime, height = 6)
        }
    }
}
