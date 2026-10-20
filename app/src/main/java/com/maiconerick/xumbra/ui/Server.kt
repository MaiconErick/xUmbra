package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.data.*
import com.maiconerick.xumbra.ui.theme.Spook
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

@Composable
fun ServerScreen() {
    var tab by remember { mutableIntStateOf(ServerNav.tab) }
    DisposableEffect(Unit) { onDispose { ServerNav.tab = 0 } }
    Column(Modifier.fillMaxSize().background(Spook.skyBrush)) {
        ScreenHeader("The Pi", { Nav.back() })
        Tabs(listOf("🖥 Pi", "👻 Umbra", "💸 Usage", "🧠 Models", "🧹 Housekeeping"), tab) { tab = it }
        Box(Modifier.fillMaxSize().navigationBarsPadding()) {
            when (tab) {
                0 -> PiTab()
                1 -> UmbraTab()
                2 -> UsageTab()
                3 -> ModelsTab()
                4 -> HousekeepingTab()
            }
        }
    }
}

@Composable
private fun PiTab() {
    var s by remember { mutableStateOf<JSONObject?>(null) }
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var cpuHist by remember { mutableStateOf(listOf<Float>()) }
    LaunchedEffect(Unit) {
        try { info = Net.get("/api/system/info") } catch (_: Exception) { }
        while (true) {
            try { s = Net.get("/api/system/stats").also { cpuHist = (cpuHist + it.optDouble("cpu_percent").toFloat()).takeLast(60) } } catch (_: Exception) { }
            delay(2000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val st = s
        if (st == null) { Text("Reading the Pi…", color = Spook.Mist); return@Column }
        val mem = st.optJSONObject("mem") ?: JSONObject()
        val disk = st.optJSONObject("disk") ?: JSONObject()
        val temp = st.optDouble("temp_c")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("CPU", "%.0f%%".format(st.optDouble("cpu_percent")), Modifier.weight(1f), if (st.optDouble("cpu_percent") > 80) Spook.Blood else Spook.Bone)
            Stat("Temperature", "%.0f °C".format(temp), Modifier.weight(1f), when { temp > 75 -> Spook.Blood; temp > 62 -> Color(0xFFFFC94A); else -> Spook.Slime })
            Stat("Up", Fmt.secs(st.optDouble("uptime_s")).substringBefore(" min").substringBefore(" s"), Modifier.weight(1f))
        }
        Section("CPU (last 2 min)") { Spark(cpuHist) }
        Section("Memory", "${Fmt.bytes(mem.optLong("used"))} of ${Fmt.bytes(mem.optLong("total"))}") { UsageBar(mem.optDouble("used"), mem.optDouble("total"), Spook.Witch, 10) }
        Section("Disk /", "${Fmt.bytes(disk.optLong("free"))} free of ${Fmt.bytes(disk.optLong("total"))}" + if (disk.optDouble("percent") > 90) " — almost full!" else "") {
            UsageBar(disk.optDouble("used"), disk.optDouble("total"), Spook.Pumpkin, 10)
        }
        val load = st.optJSONArray("load")
        Section("Load average") { Text(if (load != null) "%.2f · %.2f · %.2f  (1 · 5 · 15 min)".format(load.optDouble(0), load.optDouble(1), load.optDouble(2)) else "?", color = Spook.Bone) }
        info?.let { i ->
            Section("Machine") {
                Text("${i.optString("hostname")} · ${i.optInt("cpu_count")} cores @ %.0f MHz".format(i.optDouble("cpu_freq_mhz")), color = Spook.Bone, fontSize = 13.sp)
                Text(i.optString("platform"), color = Spook.Mist, fontSize = 12.sp)
                Text("Python " + i.optString("python_version").substringBefore(' '), color = Spook.Mist, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun Spark(values: List<Float>, max: Float = 100f, color: Color = Spook.Pumpkin) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(60.dp)) {
        if (values.size < 2) return@Canvas
        val step = size.width / 59f
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { i, v ->
            val x = size.width - (values.size - 1 - i) * step
            val y = size.height * (1f - (v / max).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

@Composable
private fun UmbraTab() {
    var st by remember { mutableStateOf<JSONObject?>(null) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var errorsOnly by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf<String?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    LaunchedEffect(rev) {
        try { st = Net.get("/api/process/status") } catch (e: Exception) { st = null }
        try { lines = Net.get("/api/process/logs?lines=400").optJSONArray("lines").strings() } catch (_: Exception) { }
    }
    ask?.let { what ->
        Confirm(if (what == "restart") "Restart Umbra?" else "Stop Umbra?",
            if (what == "restart") "The Telegram bot and the API restart (~5 s). Replies being written are lost." else "systemd starts it again within seconds.", if (what == "restart") "Restart" else "Stop",
            danger = what == "stop", onYes = {
                ask = null
                scope.launch {
                    try { Net.post("/api/process/$what"); msg = if (what == "restart") "♻ Restarting…" else "⏹ Stopping…"; delay(6000); rev++ } catch (e: Exception) { msg = e.message }
                }
            }, onNo = { ask = null })
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Banner(msg) { msg = null }
        val s = st
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Status", if (s != null) "running" else "unreachable", Modifier.weight(1f), if (s != null) Spook.Slime else Spook.Blood)
            Stat("Up for", s?.let { Fmt.secs(it.optDouble("uptime_s")) } ?: "—", Modifier.weight(1f))
            Stat("Memory", s?.let { "%.0f MB".format(it.optDouble("memory_mb")) } ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("♻ Restart") { ask = "restart" }
            GhostButton("⏹ Stop", Spook.Blood) { ask = "stop" }
            GhostButton("⟳") { rev++ }
            GhostButton("🧹 Clear log") { scope.launch { try { Net.post("/api/process/logs/clear"); rev++ } catch (e: Exception) { msg = e.message } } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(q, "Filter log") { q = it } }
            Spacer(Modifier.width(6.dp))
            Pill("⚠ errors", errorsOnly, small = true) { errorsOnly = !errorsOnly }
        }
        val shown = lines.filter { (q.isBlank() || it.contains(q, true)) && (!errorsOnly || it.contains("ERROR") || it.contains("Traceback") || it.contains("WARN") || it.contains("Exception")) }
        LaunchedEffect(shown.size) { if (shown.isNotEmpty()) list.scrollToItem(shown.lastIndex) }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color(0xCC07040C)).padding(8.dp), state = list) {
                items(shown) { l ->
                    Text(l, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = when {
                        l.contains("ERROR") || l.contains("Traceback") -> Spook.Blood
                        l.contains("WARN") -> Color(0xFFFFC94A)
                        else -> Color(0xFFCFE6C0)
                    })
                }
            }
        }
    }
}

@Composable
private fun UsageTab() {
    var window by remember { mutableStateOf("7d") }
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    var recent by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val rows by Umbra.sessions.collectAsState()
    LaunchedEffect(window) {
        Money.stats[window]?.let { stats = it }
        try { stats = Net.get("/api/usage/stats?window=$window").also { Money.stats[window] = it } } catch (_: Exception) { }
        try { recent = Net.get("/api/usage/recent?limit=40").optJSONArray("turns").objects() } catch (_: Exception) { }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BalancesSection()
        SearchLogSection()
        Choice(listOf("24h" to "24 h", "7d" to "7 days", "30d" to "30 days", "all" to "all time"), window) { window = it }
        val s = stats ?: Money.stats[window]
        if (s == null) { Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Spook.Pumpkin) }; return@Column }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Spent", Fmt.money(s.optDouble("total_cost")), Modifier.weight(1f), Spook.Pumpkin)
            Stat("Replies", "${s.optInt("turns")}", Modifier.weight(1f))
            Stat("Per reply", Fmt.money(if (s.optInt("turns") > 0) s.optDouble("total_cost") / s.optInt("turns") else 0.0), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Tokens in", Fmt.tokens(s.optLong("input_tokens") + s.optLong("cache_read_tokens")), Modifier.weight(1f))
            Stat("Tokens out", Fmt.tokens(s.optLong("output_tokens")), Modifier.weight(1f))
            Stat("Cache hits", "%.0f%%".format(s.optDouble("cache_hit_rate") * 100), Modifier.weight(1f), Spook.Slime)
        }
        if (s.optDouble("saved_vs_uncached") > 0) Text("Prompt caching saved ${Fmt.money(s.optDouble("saved_vs_uncached"))}", color = Spook.Slime, fontSize = 12.sp)
        Section("By model") {
            val pm = s.optJSONArray("per_model").objects()
            val top = pm.maxOfOrNull { it.optDouble("total_cost") } ?: 0.0
            pm.forEach { m ->
                Column {
                    Row {
                        Text("${m.optString("provider")}/${m.optString("model")}", color = Spook.Bone, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Fmt.money(m.optDouble("total_cost")) + " · ${m.optInt("turns")}×", color = Spook.Mist, fontSize = 12.sp)
                    }
                    UsageBar(m.optDouble("total_cost"), top, Spook.Witch, 6)
                }
            }
        }
        Section("Most expensive chats (all time)") {
            rows.sortedByDescending { it.costUsd }.take(8).forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { Nav.openChat(r.id) }.padding(vertical = 3.dp)) {
                    Text(r.title, color = Spook.Bone, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Fmt.money(r.costUsd), color = Spook.Pumpkin, fontSize = 12.sp)
                }
            }
        }
        Section("Latest replies") {
            recent.forEach { t ->
                val cost = t.optDouble("input_cost") + t.optDouble("output_cost") + t.optDouble("cache_read_cost") + t.optDouble("cache_write_cost")
                Row(Modifier.fillMaxWidth().clickable(enabled = t.optString("sid").isNotBlank()) { Nav.openChat(t.optString("sid")) }.padding(vertical = 2.dp)) {
                    Text(Fmt.listTime(t.optDouble("ts")), color = Spook.Mist, fontSize = 11.sp, modifier = Modifier.width(54.dp))
                    Text(rows.firstOrNull { it.id == t.optString("sid") }?.title ?: t.optString("sid"), color = Spook.Bone, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.optString("model").substringAfterLast('/') + " " + Fmt.money(cost), color = Spook.Mist, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ModelsTab() {
    var roles by remember { mutableStateOf<JSONObject?>(null) }
    var desc by remember { mutableStateOf<JSONObject?>(null) }
    var caching by remember { mutableStateOf<JSONObject?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(rev) {
        try { val o = Net.get("/api/models/roles"); roles = o.optJSONObject("roles"); desc = o.optJSONObject("descriptions") } catch (e: Exception) { msg = e.message }
        try { caching = Net.get("/api/caching/status") } catch (_: Exception) { }
    }
    picking?.let { role ->
        ModelPickerPage(ModelRef.of(roles?.optJSONObject(role)), "Model for: $role", onPick = { m ->
            picking = null
            scope.launch { try { Net.put("/api/models/roles/$role", m.json()); rev++; msg = "✓ $role → ${m.provider}/${m.model}" } catch (e: Exception) { msg = e.message } }
        }) { picking = null }
    }
    val names = mapOf("chat" to "💬 Default chat model", "summary" to "📝 Chat titles & summaries", "router_fallback" to "🧭 Memory topic picker",
        "trait_extractor" to "🧬 Learns facts about you", "web_search" to "🌐 Web search", "image_gen" to "🖼 Image generation", "tts" to "🔊 Text to speech",
        "audio_transcribe" to "🎤 Voice to text (OpenAI)", "transcribe_gemini" to "🎤 Voice to text (Gemini)")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Banner(msg) { msg = null }
        Text("Which model does each job. Chats can still pick their own model in chat settings.", color = Spook.Mist, fontSize = 12.sp)
        roles?.let { r ->
            r.keys().asSequence().sortedBy { names.keys.indexOf(it).let { i -> if (i < 0) 99 else i } }.forEach { role ->
                val m = r.optJSONObject(role) ?: JSONObject()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Spook.cardBrush).clickable { picking = role }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(names[role] ?: role, color = Spook.Bone, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(desc?.optString(role).orEmpty(), color = Spook.Mist, fontSize = 11.sp, maxLines = 2)
                    }
                    ModelLabel(m.optString("provider"), m.optString("model"), Modifier.widthIn(max = 170.dp), nameSize = 12.5f)
                }
            }
        }
        caching?.let { c ->
            Section("Prompt caching", c.optString("note")) {
                Toggle("Send cache hints (Anthropic models)", c.optBoolean("enabled"), "OpenAI and Gemini cache automatically") { on ->
                    scope.launch { try { Net.put("/api/caching/config", JSONObject().put("enabled", on)); rev++ } catch (e: Exception) { msg = e.message } }
                }
                c.optJSONObject("current")?.let { cur -> Text("Now: ${cur.optString("provider")}/${cur.optString("model")} — ${cur.optString("mode")}" + if (cur.optBoolean("active")) " ✓ active" else "", color = Spook.Bone, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun HousekeepingTab() {
    var notes by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var files by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember { mutableLongStateOf(0) }
    var ask by remember { mutableStateOf(false) }
    var rev by remember { mutableIntStateOf(0) }
    val rows by Umbra.sessions.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(rev) {
        try { notes = Net.get("/api/notifications/recent?limit=30").optJSONArray("notifications").objects() } catch (_: Exception) { }
        try { val o = Net.get("/api/debug/files"); files = o.optJSONArray("files").objects(); total = o.optLong("total_bytes") } catch (_: Exception) { }
    }
    if (ask) Confirm("Delete all debug logs?", "Frees ${Fmt.bytes(total)}. Chats and memory stay; only the per-chat event logs go.", "Delete", danger = true,
        onYes = { ask = false; scope.launch { try { Net.delete("/api/debug/files"); rev++ } catch (_: Exception) { } } }, onNo = { ask = false })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Section("⏰ Reminders sent", "Messages Umbra pushed to your phone by itself.") {
            if (notes.isEmpty()) Text("None yet.", color = Spook.Mist, fontSize = 12.sp)
            notes.forEach { n ->
                Column(Modifier.fillMaxWidth().clickable { Nav.openChat(n.optString("sid")) }.padding(vertical = 4.dp)) {
                    Text(Fmt.listTime(n.optDouble("ts")) + " · " + (rows.firstOrNull { it.id == n.optString("sid") }?.title ?: n.optString("sid")) + if (n.optBoolean("ack")) "" else " · unseen",
                        color = Spook.Mist, fontSize = 11.sp)
                    Text(Notifier.plain(n.optString("text")), color = Spook.Bone, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (notes.isNotEmpty()) TextButton(onClick = { scope.launch { try { Net.delete("/api/notifications"); rev++ } catch (_: Exception) { } } }) { Text("Clear list", color = Spook.Mist) }
        }
        Section("🐞 Debug logs", "Every chat keeps a log of what happened in each reply: ${Fmt.bytes(total)} in ${files.size} files.") {
            files.take(15).forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(rows.firstOrNull { it.id == f.optString("sid") }?.title ?: "#${f.optString("sid")}", color = Spook.Bone, fontSize = 12.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Fmt.bytes(f.optLong("size")), color = Spook.Mist, fontSize = 11.sp)
                    Text("  ✕", color = Spook.Blood, modifier = Modifier.clickable { scope.launch { try { Net.delete("/api/debug/files/${f.optString("sid")}"); rev++ } catch (_: Exception) { } } })
                }
            }
            if (files.isNotEmpty()) GhostButton("Delete all debug logs", Spook.Blood) { ask = true }
        }
    }
}
