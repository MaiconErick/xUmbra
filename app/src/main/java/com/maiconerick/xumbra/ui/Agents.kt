package com.maiconerick.xumbra.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.maiconerick.xumbra.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/** Scheduled agents (cron): shell, python or a prompt sent to a chat on a timer. */
@Composable
fun AgentsScreen() {
    var agents by remember { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    val running = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(rev) {
        try { agents = Net.get("/api/cron/agents").optJSONArray("agents").objects(); err = null } catch (e: Exception) { err = e.message }
    }
    LaunchedEffect(Unit) {
        Umbra.cronEvents.collect { ev ->
            val d = ev.data
            when (d.optString("event")) {
                "cron.start" -> running[d.optString("id")] = true
                "cron.end" -> { running.remove(d.optString("id")); rev++ }
                else -> rev++
            }
        }
    }
    // keep countdowns ticking and states fresh
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis(); if (now / 1000 % 30 == 0L) rev++ } }

    editing?.let { a -> AgentEditorPage(a, onSaved = { editing = null; rev++ }) { editing = null } }

    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Spook.skyBrush)) {
        ScreenHeader("Agents", { Nav.back() }) {
            if (tab == 1) IconButton(onClick = { editing = JSONObject() }) { Icon(Icons.Default.Add, "New agent", tint = Spook.Pumpkin) }
        }
        Tabs(listOf("🏠 Rooms", "⏰ Scheduled"), tab) { tab = it }
        if (tab == 0) { RoomsTab(); return@Column }
        Banner(err) { err = null }
        val list = agents
        if (list != null && list.isEmpty()) Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PixelSprite(Sprites.cauldron, 64.dp)
            Text("No agents yet.", color = Spook.Bone)
            Text("Agents run on a schedule: a shell command, Python, or a message to Umbra in one of your chats (great for reminders and daily checks). You can also just ask Umbra to create one.",
                color = Spook.Mist, fontSize = 13.sp)
            PrimaryButton("＋ New agent") { editing = JSONObject() }
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list ?: emptyList(), key = { it.optString("id") }) { a ->
                AgentRoom(a, running[a.optString("id")] == true, now,
                    onOpen = { editing = a },
                    onToggle = { on -> scope.launch {
                        try { Net.put("/api/cron/agents/${a.optString("id")}", payload(a).put("enabled", on)); rev++ } catch (e: Exception) { err = e.message }
                    } },
                    onRun = { scope.launch {
                        running[a.optString("id")] = true
                        try { Net.post("/api/cron/agents/${a.optString("id")}/run-now") } catch (e: Exception) { err = e.message; running.remove(a.optString("id")) }
                    } })
            }
        }
    }
}

private fun payload(a: JSONObject) = JSONObject().put("name", a.optString("name")).put("trigger", a.optJSONObject("trigger") ?: JSONObject())
    .put("action", a.optJSONObject("action") ?: JSONObject()).put("enabled", a.optBoolean("enabled", true))
    .put("tint", a.optString("tint", "#FF7A18")).put("sprite", a.optString("sprite", "bundled-default"))

fun triggerText(t: JSONObject?): String {
    t ?: return "?"
    return if (t.optString("type") == "interval") {
        val s = t.optInt("seconds")
        when {
            s % 86400 == 0 -> "every ${s / 86400} day" + if (s / 86400 > 1) "s" else ""
            s % 3600 == 0 -> "every ${s / 3600} h"
            s % 60 == 0 -> "every ${s / 60} min"
            else -> "every $s s"
        }
    } else cronText(t.optString("expr"))
}

/** Plain words for the common crontab shapes; the raw expression otherwise. */
fun cronText(e: String): String {
    val p = e.trim().split(Regex("\\s+"))
    if (p.size != 5) return e
    val (mi, h, dom, mon, dow) = p
    val time = if (mi.all(Char::isDigit) && h.all(Char::isDigit)) "%02d:%02d".format(h.toInt(), mi.toInt()) else null
    val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    return when {
        time != null && dom == "*" && mon == "*" && dow == "*" -> "daily at $time"
        time != null && dom == "*" && mon == "*" && dow.all(Char::isDigit) -> "${days[dow.toInt() % 7]}s at $time"
        time != null && dom == "*" && mon == "*" && dow == "1-5" -> "weekdays at $time"
        time != null && dom.all(Char::isDigit) && mon.all(Char::isDigit) -> "on $dom/$mon at $time"
        time != null && dom.all(Char::isDigit) && mon == "*" -> "monthly on day $dom at $time"
        mi.all(Char::isDigit) && h == "*" -> "hourly at :%02d".format(mi.toInt())
        mi.startsWith("*/") && h == "*" -> "every ${mi.drop(2)} min"
        else -> e
    }
}

@Composable
private fun AgentRoom(a: JSONObject, running: Boolean, now: Long, onOpen: () -> Unit, onToggle: (Boolean) -> Unit, onRun: () -> Unit) {
    val enabled = a.optBoolean("enabled", true)
    val state = if (running) "working" else a.optString("state")
    val tint = a.optString("tint").removePrefix("#").toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Spook.Pumpkin
    val next = a.optString("next_run").takeIf { it.isNotBlank() && it != "null" }?.let { try { OffsetDateTime.parse(it).toEpochSecond() } catch (_: Exception) { null } }
    val act = a.optJSONObject("action") ?: JSONObject()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Spook.cardBrush).border(1.dp, if (running) tint else Spook.Crypt3, RoundedCornerShape(14.dp))
        .clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            when (state) {
                "working" -> FlappingBat(30.dp, periodMs = 140)
                "starting", "walking" -> FlappingBat(26.dp)
                "sleeping" -> PixelSprite(Sprites.tomb, 34.dp)
                else -> FloatingGhost(32.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.optString("name"), color = if (enabled) Spook.Bone else Spook.Mist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(triggerText(a.optJSONObject("trigger")) + " · " + when (act.optString("type")) {
                "prompt" -> "💬 " + act.optString("prompt").take(50)
                "shell" -> "$ " + act.optString("command").take(50)
                else -> "🐍 python"
            }, color = Spook.Mist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                running -> "⚙ running now…"
                !enabled -> "💤 off"
                next != null -> "next in " + Fmt.secs((next - now / 1000).coerceAtLeast(0).toDouble()).substringBefore(" s").let { if (it.contains("min") || it.contains("h") || it.contains("d")) it else "$it s" }
                else -> "not scheduled"
            }
            val last = a.optString("last_run").takeIf { it.isNotBlank() && it != "null" }
            Text(status + (last?.let { " · last " + (if (a.optBoolean("last_ok")) "✓ " else "✗ ") + it.take(16).replace('T', ' ') } ?: ""),
                color = if (running) tint else Spook.Fog, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Switch(enabled, onToggle, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Spook.Pumpkin, uncheckedTrackColor = Spook.Crypt3))
            Text("▶ run", color = Spook.Pumpkin, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onRun).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun AgentEditorPage(a: JSONObject, onSaved: () -> Unit, onClose: () -> Unit) {
    val isNew = !a.has("id")
    val id = a.optString("id")
    val t0 = a.optJSONObject("trigger") ?: JSONObject().put("type", "cron").put("expr", "0 9 * * *")
    val act0 = a.optJSONObject("action") ?: JSONObject().put("type", "prompt")
    var name by remember { mutableStateOf(a.optString("name")) }
    var trigType by remember { mutableStateOf(t0.optString("type", "cron")) }
    var expr by remember { mutableStateOf(t0.optString("expr", "0 9 * * *")) }
    var amount by remember { mutableStateOf(t0.optInt("seconds", 3600).let { s -> when { s % 3600 == 0 -> s / 3600; s % 60 == 0 -> s / 60; else -> s } }.toString()) }
    var unit by remember { mutableStateOf(t0.optInt("seconds", 3600).let { s -> when { s % 3600 == 0 -> "h"; s % 60 == 0 -> "min"; else -> "s" } }) }
    var type by remember { mutableStateOf(act0.optString("type", "prompt")) }
    var command by remember { mutableStateOf(act0.optString("command")) }
    var code by remember { mutableStateOf(act0.optString("code")) }
    var prompt by remember { mutableStateOf(act0.optString("prompt")) }
    var sid by remember { mutableStateOf(act0.optString("session_id")) }
    var push by remember { mutableStateOf(act0.optBoolean("push", false)) }
    var timeout by remember { mutableStateOf(act0.optInt("timeout", 120).toString()) }
    var tint by remember { mutableStateOf(a.optString("tint", "#FF7A18")) }
    var err by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var askDelete by remember { mutableStateOf(false) }
    val rows by Umbra.sessions.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) { if (!isNew) try { history = Net.get("/api/cron/agents/$id/history?limit=30").optJSONArray("history").objects().reversed() } catch (_: Exception) { } }
    if (askDelete) Confirm("Delete \"$name\"?", "The agent stops and its history is removed.", "Delete", danger = true,
        onYes = { scope.launch { try { Net.delete("/api/cron/agents/$id"); onSaved() } catch (e: Exception) { err = e.message } }; askDelete = false }, onNo = { askDelete = false })

    fun save() = scope.launch {
        val secs = (amount.toIntOrNull() ?: 0) * when (unit) { "h" -> 3600; "min" -> 60; "day" -> 86400; else -> 1 }
        val trig = if (trigType == "cron") JSONObject().put("type", "cron").put("expr", expr.trim()) else JSONObject().put("type", "interval").put("seconds", secs)
        val action = JSONObject().put("type", type).put("timeout", timeout.toIntOrNull() ?: 120)
        when (type) {
            "shell" -> action.put("command", command)
            "python" -> action.put("code", code)
            else -> { action.put("prompt", prompt).put("push", push); if (sid.isNotBlank()) action.put("session_id", sid) }
        }
        val body = JSONObject().put("name", name.trim()).put("trigger", trig).put("action", action)
            .put("enabled", a.optBoolean("enabled", true)).put("tint", tint)
        try {
            if (isNew) Net.post("/api/cron/agents", body) else Net.put("/api/cron/agents/$id", body)
            if (push && sid.isNotBlank() && rows.firstOrNull { it.id == sid }?.proactive == false)
                Umbra.patchSettings(sid, JSONObject().put("proactive", true))
            onSaved()
        } catch (e: Exception) { err = e.message }
    }

    Page(if (isNew) "New agent" else name.ifBlank { "Agent" }, onClose, actions = {
        TextButton(onClick = { save() }, enabled = name.isNotBlank()) { Text("Save", color = Spook.Pumpkin, fontWeight = FontWeight.Bold) }
    }) {
        Banner(err) { err = null }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(name, "Name") { name = it }
            Section("When") {
                Choice(listOf("cron" to "At times", "interval" to "Every…"), trigType) { trigType = it }
                if (trigType == "cron") {
                    Field(expr, "min hour day month weekday", mono = true) { expr = it }
                    Text(cronText(expr), color = Spook.Slime, fontSize = 12.sp)
                    Choice(listOf("0 9 * * *" to "daily 9:00", "0 21 * * *" to "daily 21:00", "0 9 * * 1-5" to "weekdays 9:00", "0 10 * * 0" to "Sundays 10:00", "0 * * * *" to "hourly", "*/15 * * * *" to "every 15 min"), expr) { expr = it }
                } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallField(amount, 70, numeric = true) { amount = it.filter(Char::isDigit) }
                    Choice(listOf("min" to "minutes", "h" to "hours", "day" to "days", "s" to "seconds"), unit) { unit = it }
                }
            }
            Section("What") {
                Choice(listOf("prompt" to "💬 Ask Umbra", "shell" to "$ Shell", "python" to "🐍 Python"), type) { type = it }
                when (type) {
                    "shell" -> Field(command, "Command (runs on the Pi as pi)", mono = true, singleLine = false) { command = it }
                    "python" -> Field(code, "Python code", mono = true, singleLine = false, minLines = 5) { code = it }
                    else -> {
                        Field(prompt, "Message to Umbra (instructions to its future self)", singleLine = false, minLines = 3) { prompt = it }
                        Text("In chat", color = Spook.Mist, fontSize = 12.sp)
                        Choice(listOf("" to "new chat each time") + rows.take(30).map { it.id to it.title.take(26) }, sid) { sid = it }
                        Toggle("🔔 Notify me with the answer", push, "Needs a chat; turns on reminders for it") { push = it }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Timeout (s)", color = Spook.Mist, fontSize = 13.sp, modifier = Modifier.width(100.dp))
                    SmallField(timeout, 70, numeric = true) { timeout = it.filter(Char::isDigit) }
                }
            }
            Section("Colour") {
                Choice(listOf("#FF7A18", "#7CFF4F", "#B07BFF", "#FFD54A", "#FF5E7A", "#4FE0E8").map { it to "●" }, tint) { tint = it }
            }
            if (!isNew) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("▶ Run now") { scope.launch { try { Net.post("/api/cron/agents/$id/run-now"); err = "Started — the result shows up in its history" } catch (e: Exception) { err = e.message } } }
                    GhostButton("Delete", Spook.Blood) { askDelete = true }
                }
                Section("History", "Latest runs") {
                    if (history.isEmpty()) Text("No runs yet.", color = Spook.Mist, fontSize = 12.sp)
                    history.forEach { h ->
                        var open by remember { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Spook.Crypt3.copy(alpha = 0.5f)).clickable { open = !open }.padding(8.dp)) {
                            Text((if (h.optBoolean("ok")) "✓ " else "✗ ") + h.optString("started").take(19).replace('T', ' ') + " · " + "%.1f s".format(h.optDouble("duration_sec")),
                                color = if (h.optBoolean("ok")) Spook.Slime else Spook.Blood, fontSize = 12.sp)
                            Text(h.optString("output"), color = Spook.Bone, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = if (open) 200 else 2, overflow = TextOverflow.Ellipsis)
                            h.optString("session_id").takeIf { it.isNotBlank() }?.let { s -> Text("open chat →", color = Spook.Pumpkin, fontSize = 11.sp, modifier = Modifier.clickable { onClose(); Nav.openChat(s) }) }
                        }
                    }
                }
            }
        }
    }
}
