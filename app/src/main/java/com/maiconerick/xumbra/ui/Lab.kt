@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maiconerick.xumbra.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.maiconerick.xumbra.ui.theme.PixelSprite
import com.maiconerick.xumbra.ui.theme.Spook
import com.maiconerick.xumbra.ui.theme.Sprites
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ───────────── model labels: source on one line, friendly name on the next ─────────────

fun sourceColor(provider: String) = when (provider) {
    "openrouter" -> Color(0xFFB07BFF)
    "openai" -> Color(0xFF7CFF4F)
    "gemini", "google" -> Color(0xFF6FB8FF)
    "anthropic" -> Color(0xFFFFA05A)
    "code" -> Color(0xFFFFD54A)
    else -> Color(0xFFB0A8C0)
}

@Composable
fun modelName(provider: String, model: String): String {
    val key = "$provider/$model"
    val names by Money.names.collectAsState()
    LaunchedEffect(key) { Money.ensureNames(listOf(key)) }
    val n = names[key]?.name?.ifBlank { null } ?: model.substringAfterLast('/')
    // "OpenAI GPT-5 Mini" under an "OpenAI" source line → "GPT-5 Mini"
    val vendor = when (provider) { "openai" -> "OpenAI "; "gemini" -> "Google "; "anthropic" -> "Anthropic "; else -> "" }
    return if (vendor.isNotEmpty() && n.startsWith(vendor)) n.removePrefix(vendor) else n
}

@Composable
fun ModelLabel(provider: String, model: String, modifier: Modifier = Modifier, nameSize: Float = 14f, showId: Boolean = false) {
    Column(modifier) {
        Text(Money.sourceName(provider), color = sourceColor(provider), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(modelName(provider, model), color = Spook.Bone, fontSize = nameSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showId) Text(model, color = Spook.Fog, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ───────────── wallet strip (main screen) ─────────────

@Composable
fun WalletStrip() {
    val b by Money.balances.collectAsState()
    LaunchedEffect(Unit) { Money.start() }
    val or = b?.optJSONObject("openrouter")?.takeIf { !it.has("error") }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        @Composable fun chip(provider: String, main: String, sub: String) {
            Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Spook.Crypt2.copy(alpha = 0.85f))
                .border(1.dp, sourceColor(provider).copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .clickable { ServerNav.tab = 2; Nav.go(Screen.Server) }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(Money.sourceName(provider), color = sourceColor(provider), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(main, color = Spook.Bone, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, color = Spook.Mist, fontSize = 10.5.sp)
            }
        }
        chip("openrouter", or?.let { "💳 " + Fmt.usd(it.optDouble("remaining")) + " left" } ?: "💳 …",
            "today " + Fmt.money(or?.optDouble("usage_daily") ?: 0.0))
        chip("gemini", "today " + Fmt.money(Money.spentToday("gemini")), "paid key")
        chip("openai", "today " + Fmt.money(Money.spentToday("openai")), "no balance API")
    }
}

/** Which tab the Pi screen opens on (wallet chips jump to Usage). */
object ServerNav { var tab by mutableIntStateOf(0) }

// ───────────── usage drill-down ─────────────

@Composable
fun UsageBreakdownPage(window: String, onClose: () -> Unit) {
    var w by remember { mutableStateOf(window) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(w) { data = null; try { data = Net.get("/api/v2/usage/breakdown?window=$w") } catch (_: Exception) { data = JSONObject() } }
    Page("Where it went", onClose) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Choice(listOf("today" to "Today", "7d" to "7 days", "30d" to "30 days", "all" to "All time"), w) { w = it }
            val d = data
            if (d == null) { Gap(8); LinearProgressIndicator(Modifier.fillMaxWidth(), color = Spook.Pumpkin, trackColor = Spook.Crypt3) }
            else {
                Gap(6)
                Text("Total ${Fmt.money(d.optDouble("total"))}", color = Spook.Pumpkin, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    d.optJSONArray("by_category").objects().forEach { c ->
                        Text("${c.optString("name")} ${Fmt.money(c.optDouble("cost"))}", color = Spook.Bone, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Spook.Crypt3).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(d.optJSONArray("chats").objects()) { ch -> BreakdownChat(ch, onClose) }
                }
            }
        }
    }
}

@Composable
private fun BreakdownChat(ch: JSONObject, onClose: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val sid = ch.optString("sid")
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(Spook.Crypt2.copy(alpha = 0.85f))
        .clickable { open = !open }.animateContentSize().padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ch.optString("title"), color = Spook.Bone, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Fmt.money(ch.optDouble("cost")), color = Spook.Pumpkin, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        val cats = ch.optJSONObject("by_category") ?: JSONObject()
        Text("${ch.optInt("count")} charges · " + cats.keys().asSequence().joinToString(" · ") { k -> "${catName(k)} ${Fmt.money(cats.optDouble(k))}" },
            color = Spook.Mist, fontSize = 11.sp)
        if (open) {
            Gap(6)
            ch.optJSONArray("items").objects().forEach { it2 ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.width(52.dp)) {
                        Text(Fmt.time(it2.optDouble("ts")), color = Spook.Mist, fontSize = 11.sp)
                        Text(catIcon(it2.optString("category")), fontSize = 13.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(Money.sourceName(it2.optString("provider")) + " · " + it2.optString("model").substringAfterLast('/'),
                            color = sourceColor(it2.optString("provider")), fontSize = 11.sp)
                        if (it2.optString("preview").isNotBlank()) Text(Notifier.plain(it2.optString("preview")), color = Spook.Bone, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("↑${Fmt.tokens(it2.optLong("input_tokens"))} ↓${Fmt.tokens(it2.optLong("output_tokens"))} · ${it2.optString("category_name")}", color = Spook.Fog, fontSize = 10.5.sp)
                    }
                    Text(Fmt.money(it2.optDouble("cost")), color = Spook.Bone, fontSize = 12.sp)
                }
            }
            if (sid.isNotBlank()) Text("open chat →", color = Spook.Pumpkin, fontSize = 12.sp, modifier = Modifier.clickable { onClose(); Nav.openChat(sid) }.padding(top = 4.dp))
        }
    }
}

private fun catName(k: String) = when (k) { "chat" -> "replies"; "background" -> "background"; "search" -> "search"; "voice" -> "voice"; "model_testing" -> "testing"; else -> k }
private fun catIcon(k: String) = when (k) { "chat" -> "💬"; "background" -> "⚙"; "search" -> "🔎"; "voice" -> "🎤"; "model_testing" -> "🧪"; else -> "•" }

// ───────────── Model Lab ─────────────

/** sid != null → started from a chat: costs are attached to that chat. */
@Composable
fun LabPage(sid: String?, seed: JSONObject? = null, onClose: () -> Unit) {
    var tests by remember { mutableStateOf<List<JSONObject>?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(rev) { try { tests = Net.get("/api/v2/lab").optJSONArray("tests").objects() } catch (_: Exception) { tests = emptyList() } }
    LaunchedEffect(seed) {
        if (seed != null) try {
            val t = Net.post("/api/v2/lab", seed.put("sid", sid ?: JSONObject.NULL))
            open = t.optString("id"); rev++
        } catch (_: Exception) { }
    }
    open?.let { id -> LabTestPage(id, sid, onChanged = { rev++ }) { open = null; rev++ } }
    Page("Model lab", onClose, actions = {
        IconButton(onClick = { scope.launch {
            try {
                val t = Net.post("/api/v2/lab", JSONObject().put("question", "").put("kinds", JSONArray(listOf("plain", "search")))
                    .put("models", JSONArray()).put("name", "New test").put("sid", sid ?: JSONObject.NULL))
                open = t.optString("id"); rev++
            } catch (_: Exception) { }
        } }) { Icon(Icons.Default.Add, "New test", tint = Spook.Pumpkin) }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Compare models side by side: plain answer, web-search answer, voice to text. Costs go to Usage as \"Model Testing\"" +
                    (if (sid != null) ", attached to this chat." else "."), color = Spook.Mist, fontSize = 12.5.sp)
            }
            items(tests ?: emptyList(), key = { it.optString("id") }) { t ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Spook.cardBrush).clickable { open = t.optString("id") }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (t.optBoolean("pinned")) "📌 " else "") + t.optString("name"), color = Spook.Bone, fontWeight = FontWeight.SemiBold)
                        Text("${t.optJSONArray("models")?.length() ?: 0} models · " + t.optJSONArray("kinds").strings().joinToString(" / ") +
                            " · " + Fmt.listTime(t.optDouble("updated")), color = Spook.Mist, fontSize = 12.sp)
                    }
                    Text("→", color = Spook.Pumpkin, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun LabTestPage(id: String, sid: String?, onChanged: () -> Unit, onClose: () -> Unit) {
    var t by remember { mutableStateOf<JSONObject?>(null) }
    var running by remember { mutableStateOf<Set<String>>(emptySet()) }
    var question by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var cell by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load() { try { t = Net.get("/api/v2/lab").optJSONArray("tests").objects().firstOrNull { it.optString("id") == id } } catch (_: Exception) { } }
    LaunchedEffect(id) { load(); t?.let { question = it.optString("question"); name = it.optString("name") } }
    fun patch(o: JSONObject) = scope.launch { try { t = Net.patch("/api/v2/lab/$id", o); onChanged() } catch (_: Exception) { } }
    fun run(provider: String? = null, model: String? = null, kind: String? = null) = scope.launch {
        val key = listOfNotNull(provider, model, kind).joinToString("|").ifEmpty { "all" }
        running = running + key
        if (question != t?.optString("question")) try { t = Net.patch("/api/v2/lab/$id", JSONObject().put("question", question)) } catch (_: Exception) { }
        try {
            val body = JSONObject().put("sid", sid ?: JSONObject.NULL)
            if (provider != null) body.put("provider", provider).put("model", model).put("kind", kind)
            t = Net.post("/api/v2/lab/$id/run", body); onChanged(); Money.soon()
        } catch (e: Exception) { Umbra.banner.value = "Test failed: ${e.message}" }
        running = running - key
    }
    if (picking) ModelPickerPage(null, "Add a model to compare", onPick = { m ->
        picking = false
        val ms = (t?.optJSONArray("models").objects().map { JSONObject().put("provider", it.optString("provider")).put("model", it.optString("model")) } + m.json())
        patch(JSONObject().put("models", JSONArray(ms)))
    }) { picking = false }
    if (askDelete) Confirm("Delete this test?", "Its results are removed (costs stay in Usage).", "Delete", danger = true,
        onYes = { scope.launch { try { Net.delete("/api/v2/lab/$id") } catch (_: Exception) { }; askDelete = false; onClose() } }, onNo = { askDelete = false })
    cell?.let { (title, r) ->
        AlertDialog(onDismissRequest = { cell = null }, containerColor = Spook.Crypt2, title = { Text(title, color = Spook.Bone, fontSize = 15.sp) },
            text = { SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(r.optString("error").takeIf { it.isNotBlank() && it != "null" } ?: r.optString("text"), color = Spook.Bone, fontSize = 13.sp) } },
            confirmButton = { TextButton(onClick = { cell = null }) { Text("Close", color = Spook.Pumpkin) } })
    }
    val test = t
    Page(name.ifBlank { "Test" }, onClose, actions = {
        if (test != null) TextButton(onClick = { patch(JSONObject().put("pinned", !test.optBoolean("pinned"))) }) {
            Text(if (test.optBoolean("pinned")) "📌 pinned" else "📌 pin", color = if (test.optBoolean("pinned")) Spook.Pumpkin else Spook.Mist)
        }
    }) {
        if (test == null) { Text("Loading…", color = Spook.Mist, modifier = Modifier.padding(16.dp)); return@Page }
        val kinds = test.optJSONArray("kinds").strings()
        val models = test.optJSONArray("models").objects()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(name, "Name") { name = it }
            if (name != test.optString("name")) TextButton(onClick = { patch(JSONObject().put("name", name)) }) { Text("Save name", color = Spook.Pumpkin) }
            if ("plain" in kinds || "search" in kinds) Field(question, "Question", singleLine = false) { question = it }
            if ("voice" in kinds) Text("🎤 Voice uses the sample voice note on the Pi (1 min, PT-BR).", color = Spook.Mist, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Columns", color = Spook.Mist, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                listOf("plain" to "plain answer", "search" to "search answer", "voice" to "voice").forEach { (k, l) ->
                    Pill(l, k in kinds, small = true) { patch(JSONObject().put("kinds", JSONArray(if (k in kinds) kinds - k else kinds + k))) }
                    Spacer(Modifier.width(4.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(if ("all" in running) "Running…" else "▶ Run all", enabled = "all" !in running && models.isNotEmpty()) { run() }
                GhostButton("＋ model") { picking = true }
                GhostButton("🗑", Spook.Blood) { askDelete = true }
            }
            val total = models.sumOf { m -> m.optJSONObject("results")?.let { r -> r.keys().asSequence().sumOf { r.optJSONObject(it)?.optDouble("cost") ?: 0.0 } } ?: 0.0 }
            Text("Cost of the last runs: ${Fmt.money(total)}", color = Spook.Mist, fontSize = 12.sp)
            // the table: one row per model, one column per kind
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row {
                    Text("Model", color = Spook.Mist, fontSize = 11.sp, modifier = Modifier.width(150.dp))
                    kinds.forEach { k -> Text(when (k) { "plain" -> "Plain answer"; "search" -> "Search answer"; else -> "Voice" }, color = Spook.Mist, fontSize = 11.sp, modifier = Modifier.width(170.dp)) }
                }
                models.forEach { m ->
                    val p = m.optString("provider"); val mo = m.optString("model")
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Column(Modifier.width(150.dp).padding(end = 6.dp)) {
                            ModelLabel(p, mo, nameSize = 13f)
                            Text("✕ remove", color = Spook.Fog, fontSize = 10.sp, modifier = Modifier.clickable {
                                patch(JSONObject().put("models", JSONArray(models.filter { it !== m }.map { JSONObject().put("provider", it.optString("provider")).put("model", it.optString("model")) })))
                            })
                        }
                        kinds.forEach { k ->
                            val r = m.optJSONObject("results")?.optJSONObject(k)
                            val busy = "all" in running || "$p|$mo|$k" in running
                            Column(Modifier.width(166.dp).padding(end = 4.dp).clip(RoundedCornerShape(8.dp)).background(Spook.Crypt2.copy(alpha = 0.8f))
                                .clickable(enabled = r != null) { cell = "${modelLabelPlain(p, mo)} · $k" to r!! }.padding(8.dp)) {
                                when {
                                    busy -> CircularProgressIndicator(Modifier.size(16.dp), color = Spook.Pumpkin, strokeWidth = 2.dp)
                                    r == null -> Text("not run", color = Spook.Fog, fontSize = 11.sp)
                                    else -> {
                                        val ok = r.optBoolean("ok")
                                        Text((if (ok) "%.1f s".format(r.optInt("ms") / 1000.0) else "failed") + " · " + Fmt.money(r.optDouble("cost")) +
                                            (if (r.optInt("sources") > 0) " · ${r.optInt("sources")} src" else ""),
                                            color = if (ok) Spook.Slime else Spook.Blood, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        Text((r.optString("error").takeIf { !ok && it.isNotBlank() } ?: r.optString("text")).take(140),
                                            color = Spook.Bone, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Text("↻ retry", color = Spook.Pumpkin, fontSize = 10.5.sp, modifier = Modifier.padding(top = 4.dp).clickable(enabled = !busy) { run(p, mo, k) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun modelLabelPlain(p: String, m: String) = Money.names.value["$p/$m"]?.name ?: m.substringAfterLast('/')
