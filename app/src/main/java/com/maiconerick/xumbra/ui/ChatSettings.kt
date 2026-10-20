@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val iconChoices = listOf("", "💬", "🧠", "💻", "🎮", "🎵", "🎬", "📚", "🛠", "🖨", "📱", "🏠", "✈", "🍳", "💡", "📝", "🐛", "❤", "💊", "🏋", "🎃", "👻", "🦇", "🕷")
private val colorChoices = listOf("#FF7A18", "#7CFF4F", "#B07BFF", "#FFD54A", "#FF5E7A", "#4FE0E8", "#FFA05A", "#E08BFF", "#9CCB5A", "#B8E1FF")

/** Every per-chat option the server keeps: identity, model, thinking, tools, prompts, memory, reminders, look. */
@Composable
fun ChatSettingsPage(sid: String, onClose: () -> Unit) {
    val st by Umbra.chat(sid).collectAsState()
    val rows by Umbra.sessions.collectAsState()
    val personas by Umbra.personas.collectAsState()
    val tools by Umbra.tools.collectAsState()
    val row = rows.firstOrNull { it.id == sid }
    val s = st.settings
    val scope = rememberCoroutineScope()
    var pickModel by remember { mutableStateOf(false) }
    var pickImage by remember { mutableStateOf(false) }
    var inspect by remember { mutableStateOf(false) }
    var debug by remember { mutableStateOf(false) }
    var wipe by remember { mutableStateOf(false) }
    var topics by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        Umbra.loadPersonas(); Umbra.loadTools(); ExploreCache.load()
        try { topics = Net.get("/api/memory/topics").optJSONArray("topics").objects().map { it.optString("name") } } catch (_: Exception) { }
    }
    fun patch(o: JSONObject) = Umbra.patch(sid, o)

    if (pickModel) ModelPickerPage(s?.model, onPick = { m -> patch(JSONObject().put("model", m.json())); pickModel = false }) { pickModel = false }
    if (pickImage) ImageModelPage(sid, s) { pickImage = false }
    if (inspect) PromptInspectorPage(sid) { inspect = false }
    if (debug) DebugEventsPage(sid) { debug = false }
    if (wipe) Confirm("Erase this chat?", "History, summary, debug log and settings are deleted from the Pi.", "Erase forever", danger = true,
        onYes = { Umbra.delete(sid, true); wipe = false; onClose(); Nav.back() }, onNo = { wipe = false })

    Page("Chat settings", onClose) {
        if (s == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…", color = Spook.Mist) }; return@Page }
        val persona = personas.firstOrNull { it.name == s.persona }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("Name") {
                var title by remember(s.summary) { mutableStateOf(s.summary) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Field(title, "Title (empty = Umbra names it)") { title = it } }
                    if (title != s.summary) TextButton(onClick = { patch(JSONObject().put("summary", title)) }) { Text("Save", color = Spook.Pumpkin) }
                }
                Toggle("⭐ Starred", s.starred, "Kept at the top of the list") { patch(JSONObject().put("starred", it)) }
                Toggle("🕯 Vanishing messages", s.vanish, "Each word fades after a few seconds (Settings → Chats sets how long). The Pi keeps the history.") { patch(JSONObject().put("vanish", it)) }
                Toggle("👻 Hidden", s.hidden, "Not shown in the chat list (Settings → show hidden chats)") { patch(JSONObject().put("hidden", it)) }
            }

            Section("🎭 Persona", "Who answers in this chat. Changing it keeps the history.") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Plain Umbra", s.persona == null) { patch(JSONObject().put("persona", "")) }
                    personas.forEach { p -> Pill(p.displayName, s.persona == p.name) { patch(JSONObject().put("persona", p.name)) } }
                }
                persona?.let { Text(it.tagline, color = Spook.Mist, fontSize = 12.sp) }
            }

            Section("🧠 Model", "Who thinks. Order: this chat's choice → persona's model → the global default.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val em = s.effectiveModel
                        if (em != null) ModelLabel(em.provider, em.model, showId = true)
                        if (em != null) priceLine(priceOf(em.provider, em.model))?.let { Text("💲 $it", color = Spook.Moon, fontSize = 11.5.sp) }
                        Text(when (s.modelWhy) { "chat" -> "picked for this chat"; "persona" -> "from the persona"; else -> "global default" },
                            color = Spook.Fog, fontSize = 11.sp)
                    }
                    Pill("Change", false, small = true) { pickModel = true }
                    if (s.model != null) { Spacer(Modifier.width(6.dp)); Pill("Reset", false, small = true) { patch(JSONObject().put("clear_model", true)) } }
                }
                Text("Thinking effort (GPT-5 / o-series)", color = Spook.Mist, fontSize = 12.sp)
                Choice(listOf("" to "default", "minimal" to "minimal", "low" to "low", "medium" to "medium", "high" to "high"), s.reasoning ?: "") {
                    patch(JSONObject().put("reasoning", it))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Images: " + (s.imageModel?.let { ImageModels.name(it) } ?: "server default"), color = Spook.Bone, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Pill("Change", false, small = true) { pickImage = true }
                }
            }

            Section("🌐 Web search", "Which engine Umbra searches with in this chat.") {
                var pickSearch by remember { mutableStateOf(false) }
                var testSearch by remember { mutableStateOf(false) }
                if (pickSearch) ModelPickerPage(s.webSearchModel, "Search engine for this chat", onPick = { m ->
                    pickSearch = false; patch(JSONObject().put("web_search_model", m.json())) }) { pickSearch = false }
                if (testSearch) LabPage(sid, JSONObject().put("name", "Search engines").put("question", st.messages.lastOrNull { it.isUser }?.text ?: "")
                    .put("kinds", JSONArray(listOf("search"))).put("models", JSONArray(listOf(
                        JSONObject().put("provider", "openrouter").put("model", "perplexity/sonar"),
                        JSONObject().put("provider", "gemini").put("model", "gemini-3.5-flash-lite"),
                        JSONObject().put("provider", "openrouter").put("model", "openai/gpt-4.1-mini"))))) { testSearch = false }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val w = s.webSearchModel ?: ModelRef("openrouter", "perplexity/sonar")
                        ModelLabel(w.provider, w.model)
                        Text(if (s.webSearchModel != null) "picked for this chat" else "app default (Pi → Models → Web search)", color = Spook.Fog, fontSize = 11.sp)
                    }
                    Pill("Change", false, small = true) { pickSearch = true }
                    if (s.webSearchModel != null) { Spacer(Modifier.width(6.dp)); Pill("Reset", false, small = true) { patch(JSONObject().put("clear_web_search_model", true)) } }
                }
                GhostButton("🧪 Compare engines on my last question") { testSearch = true }
                Text("Reddit", color = Spook.Mist, fontSize = 12.sp)
                Choice(listOf("" to "app default", "auto" to "Umbra decides", "always" to "always", "never" to "never"), s.redditMode ?: "") {
                    patch(JSONObject().put("reddit_mode", it))
                }
                Text("After a search", color = Spook.Mist, fontSize = 12.sp)
                Choice(listOf("default" to "app default", "rewrite" to "Umbra rewrites it", "direct" to "show search answer (faster)"),
                    when (s.searchDirect) { null -> "default"; true -> "direct"; false -> "rewrite" }) {
                    patch(when (it) { "default" -> JSONObject().put("clear_search_direct", true); "direct" -> JSONObject().put("search_direct", true); else -> JSONObject().put("search_direct", false) })
                }
                Text("\"Umbra rewrites it\" = the model reads the search result and answers in its own voice (one more model call, ~1.5 s). " +
                    "\"Show search answer\" = the engine's answer goes straight to you.", color = Spook.Fog, fontSize = 11.sp)
            }

            Section("🛠 Tools", "Umbra loads tools by itself when it needs them (memory and web search are always there). Ticked ones are loaded from the start.") {
                if (s.loadedTools.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Loaded by Umbra in this chat: " + s.loadedTools.joinToString(", "), color = Spook.Slime, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Pill("forget", false, small = true) { patch(JSONObject().put("loaded_tools", JSONArray())) }
                }
                val on = s.tools.toSet()
                tools.forEach { t ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                        val next = if (t.name in on) on - t.name else on + t.name
                        patch(JSONObject().put("tools", JSONArray(next.toList())))
                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(t.name in on, null, colors = CheckboxDefaults.colors(checkedColor = Spook.Pumpkin, checkmarkColor = Color.Black, uncheckedColor = Spook.Fog))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, color = Spook.Bone, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(t.description, color = Spook.Mist, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Toggle("Show live shell output", s.showShell, "Last lines of running commands inside the reply bubble") { patch(JSONObject().put("show_shell", it)) }
            }

            Section("📜 Instructions", "Added to Umbra's prompt in this chat only.") {
                var prim by remember(s.sysPrimary) { mutableStateOf(s.sysPrimary) }
                var sec by remember(s.sysSecondary) { mutableStateOf(s.sysSecondary) }
                Field(prim, "Identity (who Umbra is here)", singleLine = false, minLines = 2) { prim = it }
                Field(sec, "Rules (how to answer here)", singleLine = false, minLines = 2) { sec = it }
                if (prim != s.sysPrimary || sec != s.sysSecondary) PrimaryButton("Save instructions") {
                    patch(JSONObject().put("sys_primary", prim).put("sys_secondary", sec))
                }
            }

            Section("🗂 Memory", "Topics always loaded in this chat (others load only when the message mentions them).") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val pinned = s.pinnedTopics.toSet()
                    (topics + pinned).distinct().sorted().forEach { t ->
                        Pill(t, t in pinned, small = true) {
                            patch(JSONObject().put("pinned_topics", JSONArray((if (t in pinned) pinned - t else pinned + t).toList())))
                        }
                    }
                }
                if (st.summaryText.isNotBlank()) Text("Rolling summary: ${st.summaryText}", color = Spook.Mist, fontSize = 12.sp)
            }

            Section("⏰ Reminders", "Lets Umbra schedule itself to message you later in this chat (\"remind me tomorrow…\"). They arrive as notifications.") {
                Toggle("Allow Umbra to speak first", s.proactive) { patch(JSONObject().put("proactive", it)) }
            }

            Section("📁 Organize") {
                var group by remember(s.group) { mutableStateOf(s.group ?: "") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Field(group, "Group") { group = it } }
                    if (group != (s.group ?: "")) TextButton(onClick = { patch(JSONObject().put("group", group)) }) { Text("Save", color = Spook.Pumpkin) }
                }
                var tag by remember { mutableStateOf("") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.tags.forEach { t -> Pill("#$t ✕", true, small = true) { patch(JSONObject().put("tags", JSONArray(s.tags - t))) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Field(tag, "Add tag") { tag = it.replace(" ", "-").lowercase() } }
                    if (tag.isNotBlank()) TextButton(onClick = { patch(JSONObject().put("tags", JSONArray((s.tags + tag).distinct()))); tag = "" }) { Text("Add", color = Spook.Pumpkin) }
                }
                Text("Icon", color = Spook.Mist, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    iconChoices.forEach { e ->
                        val on = (s.customIcon ?: "") == e
                        Box(Modifier.size(38.dp).clip(CircleShape).background(if (on) Spook.Pumpkin else Spook.Crypt3).clickable { patch(JSONObject().put("custom_icon", e)) },
                            contentAlignment = Alignment.Center) { Text(e.ifEmpty { "∅" }, fontSize = 18.sp, color = Spook.Mist) }
                    }
                    colorChoices.forEach { c ->
                        val col = Color(0xFF000000 or c.removePrefix("#").toLong(16))
                        Box(Modifier.size(38.dp).clip(CircleShape).background(col).border(if (s.customIcon == c) 3.dp else 0.dp, Spook.Bone, CircleShape)
                            .clickable { patch(JSONObject().put("custom_icon", c)) })
                    }
                }
            }

            Section("🔍 Look inside") {
                Text("#$sid · ${row?.count ?: 0} messages · spent ${Fmt.money(row?.costUsd ?: 0.0)} in ${row?.turns ?: 0} replies" +
                    (row?.created?.takeIf { it > 0 }?.let { " · since ${Fmt.listTime(it)}" } ?: ""), color = Spook.Mist, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("What Umbra sees") { inspect = true }
                    GhostButton("What Umbra did (debug)") { debug = true }
                }
            }
            GhostButton("🗑 Erase this chat from the Pi", Spook.Blood) { wipe = true }
            Gap(30)
        }
    }
}

// ───────────── what the model saw ─────────────

@Composable
fun PromptInspectorPage(sid: String, onClose: () -> Unit) {
    var snaps by remember { mutableStateOf<List<JSONObject>?>(null) }
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { try { snaps = Net.get("/api/sessions/$sid/prompts?limit=10").optJSONArray("snapshots").objects().reversed() } catch (_: Exception) { snaps = emptyList() } }
    Page("What Umbra sees", onClose) {
        val list = snaps
        when {
            list == null -> Text("Loading…", color = Spook.Mist, modifier = Modifier.padding(16.dp))
            list.isEmpty() -> Text("No prompt saved yet for this chat.", color = Spook.Mist, modifier = Modifier.padding(16.dp))
            else -> {
                val s = list[idx.coerceIn(0, list.lastIndex)]
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(list.indices.map { "$it" to (if (it == 0) "latest" else "−$it") }, "$idx") { idx = it.toInt() }
                    Text("${s.optString("ts").take(19).replace('T', ' ')} · ${s.optString("model")}", color = Spook.Mist, fontSize = 12.sp)
                    val bd = s.optJSONObject("breakdown") ?: JSONObject()
                    val total = bd.optInt("total_estimated").coerceAtLeast(1)
                    Section("Token budget", "Estimated tokens per part of the prompt") {
                        bd.keys().asSequence().filter { it != "total_estimated" }.sortedByDescending { bd.optInt(it) }.forEach { k ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(k, color = Spook.Bone, fontSize = 12.sp, modifier = Modifier.width(140.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Box(Modifier.weight(1f)) { UsageBar(bd.optInt(k).toDouble(), total.toDouble(), Spook.Witch) }
                                Text(" ${bd.optInt(k)}", color = Spook.Mist, fontSize = 11.sp, modifier = Modifier.width(52.dp))
                            }
                        }
                        Text("total ≈ $total tokens", color = Spook.Pumpkin, fontSize = 12.sp)
                    }
                    s.optJSONObject("router")?.let { r ->
                        if (r.length() > 0) Section("Topic router", "Why these memory topics were loaded") {
                            Text("path: ${r.optString("path")} · loaded: ${r.optJSONArray("loaded").strings().joinToString().ifBlank { "none" }}", color = Spook.Bone, fontSize = 12.sp)
                        }
                    }
                    val seg = s.optJSONObject("segments") ?: JSONObject()
                    seg.keys().forEach { k -> Expandable(k, seg.optString(k)) }
                    Expandable("conversation (${s.optJSONArray("conversation")?.length() ?: 0} messages)", s.optJSONArray("conversation")?.toString(2) ?: "")
                }
            }
        }
    }
}

@Composable
fun Expandable(title: String, body: String, mono: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Spook.Crypt2.copy(alpha = 0.8f)).clickable { open = !open }.padding(10.dp)) {
        Text((if (open) "▾ " else "▸ ") + title, color = Spook.Pumpkin, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (open) SelectionContainer {
            Text(body.take(60_000), color = Spook.Bone, fontSize = 11.5.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ───────────── debug events ─────────────

@Composable
fun RawEventsPage(sid: String, onClose: () -> Unit) {
    var events by remember { mutableStateOf<List<JSONObject>?>(null) }
    var q by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { try { events = Net.get("/api/sessions/$sid/events?limit=1500").optJSONArray("events").objects().reversed() } catch (_: Exception) { events = emptyList() } }
    Page("Raw event log", onClose) {
        val all = events ?: emptyList()
        val types = remember(all) { all.map { it.optString("type").substringBefore(':').substringBefore('.') }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
        Column(Modifier.padding(horizontal = 12.dp)) {
            Field(q, "Search type or content") { q = it }
            Choice(listOf("" to "all") + types.map { it to it }, type) { type = it }
            Text(if (events == null) "Loading…" else "${all.size} events, newest first", color = Spook.Mist, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(all.filter { e ->
                    (type.isEmpty() || e.optString("type").startsWith(type)) && (q.isBlank() || e.toString().contains(q, true))
                }.take(600)) { e ->
                    val d = e.optJSONObject("data") ?: JSONObject()
                    var open by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(Spook.Crypt2.copy(alpha = 0.75f)).clickable { open = !open }.padding(8.dp)) {
                        Row {
                            Text(d.optString("title").ifBlank { e.optString("type") }, color = Spook.Bone, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(Fmt.time(e.optDouble("ts")), color = Spook.Mist, fontSize = 10.5.sp)
                        }
                        Text(e.optString("type") + (d.optString("reason").takeIf { it.isNotBlank() && it != d.optString("title") }?.let { " — $it" } ?: ""),
                            color = Spook.Mist, fontSize = 11.sp, maxLines = if (open) 10 else 1, overflow = TextOverflow.Ellipsis)
                        if (open) SelectionContainer { Text(d.toString(2).take(30_000), color = Spook.Moon, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        }
    }
}
