package com.maiconerick.xumbra.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.data.*
import com.maiconerick.xumbra.ui.theme.Sprites
import com.maiconerick.xumbra.ui.theme.PixelSprite
import com.maiconerick.xumbra.ui.theme.Spook
import org.json.JSONObject

// ───────────── context window (like Claude's /context) ─────────────

private fun ctxColor(pct: Float) = when {
    pct >= 0.85f -> Color(0xFFFF5A5A)
    pct >= 0.6f -> Color(0xFFFFC94A)
    else -> Color(0xFF7CFF4F)
}

/** Small ring + percentage for the chat header. */
@Composable
fun ContextChip(msgs: List<Msg>, onClick: () -> Unit) {
    val t = msgs.lastOrNull { it.turn?.contextWindow?.let { w -> w > 0 } == true }?.turn ?: return
    val pct = (t.contextTokens.toFloat() / t.contextWindow).coerceIn(0f, 1f)
    Row(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Ring(pct, 16)
        Spacer(Modifier.width(4.dp))
        Text(if (pct < 0.01f) "<1%" else "${(pct * 100).toInt()}%", color = Spook.Mist, fontSize = 11.sp)
    }
}

@Composable
private fun Ring(pct: Float, sizeDp: Int) {
    val c = ctxColor(pct)
    val track = Spook.Crypt3
    Canvas(Modifier.size(sizeDp.dp)) {
        val w = 2.5.dp.toPx()
        drawArc(track, 0f, 360f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
        drawArc(c, -90f, 360f * pct.coerceAtLeast(0.02f), false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
    }
}

private val partNames = mapOf(
    "system_prompt" to "Umbra's core prompt", "tools_schema" to "Tool definitions", "conversation" to "Conversation",
    "rules" to "Rules", "system_info" to "System facts", "summary" to "Chat summary", "summary_rules" to "Memory instructions",
    "proactive_mode" to "Reminder settings", "chat_sys_primary" to "Chat identity", "chat_sys_secondary" to "Chat rules",
    "images" to "Images", "trait_warnings" to "Preference change note",
)
private val partColors = listOf(Color(0xFFFF7A18), Color(0xFFB07BFF), Color(0xFF4FE0E8), Color(0xFF7CFF4F), Color(0xFFFFD54A),
    Color(0xFFFF5E7A), Color(0xFF9BE06A), Color(0xFFE08BFF), Color(0xFFFFA05A), Color(0xFFB8E1FF))

@Composable
fun ContextPage(sid: String, onClose: () -> Unit) {
    var c by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sid) { try { c = Net.get("/api/v2/sessions/$sid/context") } catch (e: Exception) { err = e.message } }
    Page("Context", onClose) {
        val o = c
        if (o == null) { Text(err ?: "Reading…", color = Spook.Mist, modifier = Modifier.padding(16.dp)); return@Page }
        val window = o.optInt("window"); val used = o.optInt("used")
        val pct = if (window > 0) used.toFloat() / window else 0f
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(pct, 56)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("${Fmt.tokens(used.toLong())} of ${if (window > 0) Fmt.tokens(window.toLong()) else "?"} tokens" +
                        (if (window > 0) "  ·  ${"%.1f".format(pct * 100)}%" else ""), color = Spook.Bone, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("${o.optString("provider")} / ${o.optString("model")}", color = Spook.Mist, fontSize = 12.sp)
                    Text("Umbra keeps the last ~30 messages of this chat in view (${o.optInt("messages_total")} in total); older ones live in the summary and memory.",
                        color = Spook.Fog, fontSize = 11.sp)
                }
            }
            val bd = o.optJSONObject("breakdown") ?: JSONObject()
            val parts = bd.keys().asSequence().filter { it != "total_estimated" && bd.optInt(it) > 0 }
                .map { it to bd.optInt(it) }.sortedByDescending { it.second }.toList()
            val total = parts.sumOf { it.second }.coerceAtLeast(1)
            Section("What fills it", "Estimated tokens of the last prompt sent to the model") {
                // stacked bar of the window
                Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Spook.Crypt3)) {
                    parts.forEachIndexed { i, (_, v) ->
                        val f = if (window > 0) v.toFloat() / window else v.toFloat() / total
                        if (f > 0.002f) Box(Modifier.fillMaxHeight().weight(f).background(partColors[i % partColors.size]))
                    }
                    val free = if (window > 0) (1f - total.toFloat() / window).coerceAtLeast(0f) else 0f
                    if (free > 0.002f) Spacer(Modifier.weight(free))
                }
                parts.forEachIndexed { i, (k, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(partColors[i % partColors.size], 9)
                        Spacer(Modifier.width(8.dp))
                        val name = partNames[k] ?: k.replace("topic:", "Topic: ").replace("trait:", "Traits: ").replace("persona:", "Persona: ")
                        Text(name, color = Spook.Bone, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Fmt.tokens(v.toLong()), color = Spook.Mist, fontSize = 12.sp)
                    }
                }
            }
            val lr = o.optJSONObject("last_reply") ?: JSONObject()
            Section("This chat so far") {
                Text("${o.optInt("session_replies")} replies · ${Fmt.money(o.optDouble("session_cost_usd"))}", color = Spook.Bone, fontSize = 14.sp)
                val tin = o.optLong("session_tokens_in"); val tout = o.optLong("session_tokens_out")
                Text("Total tokens: ${Fmt.tokens(tin + tout)}  (↑ ${Fmt.tokens(tin)} sent · ↓ ${Fmt.tokens(tout)} written, incl. searches & background)",
                    color = Spook.Bone, fontSize = 13.sp)
                if (!lr.isNull("input_tokens")) Text("Last reply: ↑${Fmt.tokens(lr.optLong("input_tokens"))} in" +
                    (if (lr.optLong("cache_read_tokens") > 0) " (${Fmt.tokens(lr.optLong("cache_read_tokens"))} cached)" else "") +
                    " · ↓${Fmt.tokens(lr.optLong("output_tokens"))} out · ${Fmt.money(lr.optDouble("cost_usd"))}", color = Spook.Mist, fontSize = 12.sp)
            }
        }
    }
}

// ───────────── tools ─────────────

@Composable
fun ToolsScreen() {
    var tools by remember { mutableStateOf<List<JSONObject>?>(null) }
    val rows by Umbra.sessions.collectAsState()
    LaunchedEffect(Unit) { try { tools = Net.get("/api/v2/tools").optJSONArray("tools").objects() } catch (_: Exception) { tools = emptyList() } }
    Column(Modifier.fillMaxSize().background(Spook.skyBrush)) {
        ScreenHeader("Tools", { Nav.back() })
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("What Umbra can do. The first three are always there; the rest Umbra loads by itself when a message needs them, and they stay loaded in that chat.",
                    color = Spook.Mist, fontSize = 12.5.sp)
                Gap(6)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Spook.cardBrush).border(1.dp, Spook.Crypt3, RoundedCornerShape(12.dp))
                    .clickable { Nav.go(Screen.Agents) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PixelSprite(Sprites.cauldron, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Scheduled agents", color = Spook.Bone, fontWeight = FontWeight.SemiBold)
                        Text("Jobs and reminders created with the agents tool — see, edit, run, delete", color = Spook.Mist, fontSize = 12.sp)
                    }
                    Text("→", color = Spook.Pumpkin, fontSize = 18.sp)
                }
            }
            if (tools == null) item { Text("Loading…", color = Spook.Mist) }
            items(tools ?: emptyList(), key = { it.optString("name") }) { t ->
                var open by remember { mutableStateOf(false) }
                val always = t.optString("kind") == "always"
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Spook.Crypt2.copy(alpha = 0.85f)).clickable { open = !open }.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.optString("name"), color = Spook.Bone, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(8.dp))
                        Text(if (always) "always" else "on demand", color = if (always) Spook.Slime else Spook.Witch, fontSize = 10.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Spook.Crypt3).padding(horizontal = 7.dp, vertical = 2.dp))
                        Spacer(Modifier.weight(1f))
                        Text("${t.optInt("uses")}×", color = Spook.Pumpkin, fontSize = 12.sp)
                    }
                    Text(t.optString("description"), color = Spook.Mist, fontSize = 12.5.sp)
                    val last = t.optDouble("last_used", 0.0)
                    Text(listOfNotNull(
                        if (last > 0) "last used ${Fmt.ago(last)}" else "never used yet",
                        t.optInt("used_in_chats").takeIf { it > 0 }?.let { "in $it chats" },
                        t.optJSONArray("loaded_in")?.length()?.takeIf { it > 0 && !always }?.let { "loaded in $it" },
                    ).joinToString(" · "), color = Spook.Fog, fontSize = 11.sp)
                    if (open) {
                        Gap(6)
                        val params = t.optJSONArray("params").strings()
                        if (params.isNotEmpty()) Text("fields: " + params.joinToString(", "), color = Spook.Moon, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                        if (t.optString("details") != t.optString("description")) SelectionContainer {
                            Text(t.optString("details"), color = Spook.Bone, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                        }
                        val chats = t.optJSONArray("loaded_in").strings()
                        if (chats.isNotEmpty() && !always) {
                            Text("Loaded in:", color = Spook.Mist, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                            chats.take(12).forEach { sid ->
                                Text("• " + (rows.firstOrNull { it.id == sid }?.title ?: "#$sid"), color = Spook.Pumpkin, fontSize = 12.sp,
                                    modifier = Modifier.clickable { Nav.openChat(sid) }.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────── money + quotas (Usage tab) ─────────────

@Composable
fun BalancesSection() {
    val b by Money.balances.collectAsState()
    val q by Money.quota.collectAsState()
    var drill by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Money.start(); Money.refresh() }
    drill?.let { UsageBreakdownPage(it) { drill = null } }
    val or = b?.optJSONObject("openrouter")
    // fixed-height cards: numbers fill in without the screen jumping
    Section("💳 OpenRouter", "Live from your OpenRouter account") {
        val ok = or != null && !or.has("error")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Credit left", if (ok) Fmt.usd(or!!.optDouble("remaining")) else "…", Modifier.weight(1f),
                if (ok && or!!.optDouble("remaining") < 2) Spook.Blood else Spook.Slime)
            Stat("Spent all time", if (ok) Fmt.usd(or!!.optDouble("total_usage")) else "…", Modifier.weight(1f))
            Stat("Today", if (ok) Fmt.money(or!!.optDouble("usage_daily")) else "…", Modifier.weight(1f))
        }
        UsageBar(if (ok) or!!.optDouble("total_usage") else 0.0, if (ok) or!!.optDouble("total_credits") else 1.0, Spook.Pumpkin, 8)
        Text(if (ok) "of " + Fmt.usd(or!!.optDouble("total_credits")) + " added · week ${Fmt.money(or.optDouble("usage_weekly"))} · month ${Fmt.money(or.optDouble("usage_monthly"))}"
             else if (or?.has("error") == true) "Couldn't read: ${or.optString("error")}" else " ", color = Spook.Mist, fontSize = 12.sp, maxLines = 1)
        val lim = if (ok && !or!!.isNull("key_limit")) or.optDouble("key_limit") else null
        Text(if (lim != null) "This key is capped at " + Fmt.usd(lim) + " per month — " + Fmt.usd(or!!.optDouble("key_limit_remaining")) + " left" else " ",
            color = Spook.Bone, fontSize = 12.sp, maxLines = 1)
    }
    Section("🧾 Where the money went", "Tap a period to see each chat and each charge.") {
        val sp = b?.optJSONObject("spend") ?: JSONObject()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("today" to "Today", "week" to "7 days", "month" to "30 days").forEach { (k, l) ->
                val total = sp.keys().asSequence().sumOf { sp.optJSONObject(it)?.optDouble(k, 0.0) ?: 0.0 }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Spook.Crypt3.copy(alpha = 0.6f))
                    .clickable { drill = when (k) { "today" -> "today"; "week" -> "7d"; else -> "30d" } }.padding(10.dp)) {
                    Text(if (b == null) "…" else Fmt.money(total), color = Spook.Bone, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("$l ›", color = Spook.Pumpkin, fontSize = 11.sp)
                }
            }
        }
        listOf("openrouter", "openai", "gemini").forEach { prov ->
            val o = sp.optJSONObject(prov) ?: JSONObject()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Money.sourceName(prov), color = sourceColor(prov), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp))
                Text("today ${Fmt.money(o.optDouble("today"))} · 7d ${Fmt.money(o.optDouble("week"))} · 30d ${Fmt.money(o.optDouble("month"))}",
                    color = Spook.Mist, fontSize = 12.sp)
            }
        }
    }
    Section("♊ Gemini quota", q?.optString("note") ?: "Counts Umbra's own Gemini calls") {
        val qq = q
        Text(if (qq == null) " " else "Resets in ${Fmt.secs(qq.optDouble("resets_at") - System.currentTimeMillis() / 1000.0)} (midnight Pacific)",
            color = Spook.Mist, fontSize = 12.sp)
        val ms = qq?.optJSONArray("models").objects()
        if (qq != null && ms.isEmpty()) Text("No Gemini calls from Umbra today.", color = Spook.Mist, fontSize = 12.sp)
        ms.forEach { m ->
            val lim = if (m.isNull("limit_per_day")) null else m.optInt("limit_per_day")
            val used = m.optInt("used_today")
            Row {
                Text(m.optString("model"), color = Spook.Bone, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(if (m.optBoolean("exhausted")) "hit a limit today ($used calls)" else "$used" + (lim?.let { " / $it" } ?: "") + " calls today",
                    color = if (m.optBoolean("exhausted")) Spook.Blood else Spook.Mist, fontSize = 12.sp)
            }
            if (lim != null) UsageBar(used.toDouble(), lim.toDouble(), Spook.Witch, 6)
        }
    }
}

// ───────────── search cost log ─────────────

@Composable
fun SearchLogSection() {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var window by remember { mutableStateOf("today") }
    LaunchedEffect(window) {
        try { data = Net.get("/api/v2/usage/searches?window=$window") } catch (_: Exception) { }
    }
    Section("🔍 Web Search Log", "Every web_search call via Umbra (OpenRouter / Gemini). Each search costs real money.") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("today" to "Today", "7d" to "7 days", "30d" to "30 days", "all" to "All").forEach { (k, l) ->
                val sel = window == k
                Text(l, color = if (sel) Color.Black else Spook.Pumpkin, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (sel) Spook.Pumpkin else Spook.Crypt3.copy(alpha = 0.6f))
                        .clickable { window = k }
                        .padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Gap(8)
        val d = data
        if (d == null) { Text("Loading…", color = Spook.Mist, fontSize = 12.sp); return@Section }
        val count = d.optInt("count")
        val total = d.optDouble("total_cost")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Searches", "$count", Modifier.weight(1f), Spook.Slime)
            Stat("Total cost", Fmt.money(total), Modifier.weight(1f), if (total > 0.5) Spook.Blood else Spook.Slime)
        }
        val searches = d.optJSONArray("searches").objects()
        if (searches.isEmpty()) { Text("No searches in this period.", color = Spook.Mist, fontSize = 12.sp); return@Section }
        Gap(6)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 300.dp)) {
            items(searches) { s ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Spook.Crypt2.copy(alpha = 0.7f)).padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.optString("query").ifBlank { "(no query)" }, color = Spook.Bone, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(Fmt.money(s.optDouble("cost")), color = Spook.Pumpkin, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row {
                        Text("${s.optString("provider")}/${s.optString("model")}", color = Spook.Mist, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        Text(Fmt.listTime(s.optDouble("ts")), color = Spook.Fog, fontSize = 10.5.sp)
                    }
                }
            }
        }
    }
}
