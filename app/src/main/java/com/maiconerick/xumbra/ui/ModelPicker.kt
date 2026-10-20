@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maiconerick.xumbra.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import org.json.JSONObject

/** One model in the explorer (OpenRouter list, also used to describe direct OpenAI/Gemini models). */
data class ExModel(
    val id: String, val name: String, val vendor: String, val description: String, val inPrice: Double, val outPrice: Double,
    val ctx: Int, val input: List<String>, val output: List<String>, val tools: Boolean, val reasoning: Boolean, val free: Boolean,
    val created: Long, val score: Double?, val coding: Double?, val agentic: Double? = null,
)

data class ExCard(val key: String, val title: String, val sub: String, val items: List<Pair<String, String>>)
data class GuidePick(val id: String, val role: String, val label: String)
data class GuideGoal(val key: String, val title: String, val sub: String, val picks: List<GuidePick>)
data class Explore(val cards: List<ExCard>, val guide: List<GuideGoal>, val categories: Map<String, Set<String>>, val models: List<ExModel>) {
    val byId by lazy { models.associateBy { it.id } }
}

object ExploreCache {
    val data = mutableStateOf<Explore?>(null)
    suspend fun load(refresh: Boolean = false) {
        if (data.value != null && !refresh) return
        try {
            val o = Net.get("/api/v2/models/explore" + if (refresh) "?refresh=true" else "")
            val models = o.optJSONArray("models").objects().map { m ->
                ExModel(m.optString("id"), m.optString("name"), m.optString("vendor"), m.optString("description"),
                    m.optDouble("in", 0.0), m.optDouble("out", 0.0), m.optInt("ctx"), m.optJSONArray("input").strings(),
                    m.optJSONArray("output").strings(), m.optBoolean("tools"), m.optBoolean("reasoning"), m.optBoolean("free"),
                    m.optLong("created"), if (m.has("score")) m.optDouble("score") else null, if (m.has("coding")) m.optDouble("coding") else null,
                    if (m.has("agentic")) m.optDouble("agentic") else null)
            }
            val guide = o.optJSONArray("guide").objects().map { g ->
                GuideGoal(g.optString("key"), g.optString("title"), g.optString("sub"),
                    g.optJSONArray("picks").objects().map { GuidePick(it.optString("id"), it.optString("role"), it.optString("label")) })
            }
            val cards = o.optJSONArray("cards").objects().map { c ->
                ExCard(c.optString("key"), c.optString("title"), c.optString("sub"), c.optJSONArray("items").objects().map { it.optString("id") to it.optString("note") })
            }
            val cats = o.optJSONObject("categories")?.let { co -> co.keys().asSequence().associateWith { co.optJSONArray(it).strings().toSet() } } ?: emptyMap()
            data.value = Explore(cards, guide, cats, models)
        } catch (_: Exception) { }
    }
}

private val useCases = listOf("programming" to "💻 Coding", "roleplay" to "🎭 Roleplay", "translation" to "🌍 Translation", "marketing" to "📣 Marketing",
    "technology" to "🔧 Tech", "science" to "🔬 Science", "finance" to "💰 Finance", "health" to "🩺 Health", "legal" to "⚖ Legal",
    "academia" to "🎓 Academia", "trivia" to "❓ Trivia")
private val abilities = listOf("image_out" to "🖼 Makes images", "audio" to "🎙 Audio / voice", "vision" to "👁 Sees images",
    "tools" to "🛠 Uses tools", "reasoning" to "🧠 Reasoning", "free" to "🆓 Free")

private fun ExModel.has(a: String) = when (a) {
    "image_out" -> "image" in output; "audio" -> "audio" in output || "audio" in input; "vision" -> "image" in input
    "tools" -> tools; "reasoning" -> reasoning; "free" -> free; else -> true
}

@Composable
fun ModelPickerPage(current: ModelRef?, title: String = "Pick a model", onPick: (ModelRef) -> Unit, onClose: () -> Unit) {
    val ex by ExploreCache.data
    var provider by remember { mutableStateOf(current?.provider ?: "openrouter") }
    var direct by remember { mutableStateOf<Map<String, List<CatalogModel>>>(emptyMap()) }
    var recent by remember { mutableStateOf<List<ModelRef>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var useCase by remember { mutableStateOf("") }
    var ability by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("smart") }
    var custom by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        loading = true
        ExploreCache.load()
        try {
            val o = Net.get("/api/models/catalog"); val ps = o.optJSONObject("providers") ?: JSONObject()
            direct = ps.keys().asSequence().associateWith { k -> ps.optJSONArray(k).objects().map(CatalogModel::of) }
        } catch (_: Exception) { }
        try { recent = Net.get("/api/providers").optJSONArray("history").objects().mapNotNull { ModelRef.of(it) } } catch (_: Exception) { }
        loading = false
    }
    // the list for this tab, in explorer shape
    val all: List<ExModel> = remember(provider, ex, direct) {
        val e = ex
        when (provider) {
            "openrouter" -> e?.models ?: direct["openrouter"].orEmpty().map { it.toEx("OpenRouter") }
            "openai" -> direct["openai"].orEmpty().map { c -> e?.byId?.get("openai/${c.id}")?.copy(id = c.id) ?: c.toEx("OpenAI") }
            "gemini" -> direct["gemini"].orEmpty().map { c -> e?.byId?.get("google/${c.id}")?.copy(id = c.id) ?: c.toEx("Google") }
            else -> emptyList()
        }
    }
    val shown = remember(all, q, useCase, ability, sort, ex) {
        all.filter { m ->
            (q.isBlank() || m.id.contains(q, true) || m.name.contains(q, true) || m.vendor.contains(q, true)) &&
                (useCase.isEmpty() || ex?.categories?.get(useCase)?.contains(m.id) == true) &&
                (ability.isEmpty() || m.has(ability))
        }.sortedWith(when (sort) {
            "cheap" -> compareBy { (3 * it.inPrice + it.outPrice) / 4 }
            "ctx" -> compareByDescending { it.ctx }
            "new" -> compareByDescending { it.created }
            else -> compareByDescending<ExModel> { it.score ?: -1.0 }.thenBy { it.name }
        })
    }
    val groups = remember(shown) { shown.groupBy { it.vendor }.entries.sortedByDescending { it.value.size } }
    val openGroups = remember { mutableStateMapOf<String, Boolean>() }
    fun pick(id: String) = onPick(ModelRef(provider, id))

    Page(title, onClose, actions = {
        if (provider == "openrouter") TextButton(onClick = { scope.launch { loading = true; ExploreCache.load(refresh = true); loading = false } }) { Text("⟳", color = Spook.Mist, fontSize = 18.sp) }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Choice(listOf("openrouter" to "OpenRouter", "openai" to "OpenAI", "gemini" to "Google Gemini", "ollama" to "Ollama"), provider) { provider = it; useCase = ""; ability = "" }
                if (recent.isNotEmpty()) {
                    Gap(6)
                    Text("Recently used", color = Spook.Mist, fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recent.take(8).forEach { m ->
                            Column(Modifier.clip(RoundedCornerShape(10.dp)).background(if (m == current) Spook.Crypt3 else Spook.Crypt2).clickable { onPick(m) }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                                Text(Money.sourceName(m.provider), color = sourceColor(m.provider), fontSize = 10.sp)
                                Text(modelName(m.provider, m.model), color = Spook.Bone, fontSize = 12.5.sp, maxLines = 1)
                                priceLine(priceOf(m.provider, m.model))?.let { Text(it, color = Spook.Moon, fontSize = 10.sp, maxLines = 1) }
                            }
                        }
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Spook.Pumpkin, trackColor = Spook.Crypt3)
            }
            if (provider == "openrouter" && q.isBlank() && useCase.isEmpty() && ability.isEmpty() && ex?.guide?.isNotEmpty() == true) item {
                ModelGuide(ex!!, current) { pick(it) }
            }
            // ranking cards (OpenRouter)
            if (provider == "openrouter" && q.isBlank() && useCase.isEmpty() && ability.isEmpty() && ex != null) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ex!!.cards) { c -> RankCard(c, ex!!, current) { pick(it) } }
                }
            }
            if (provider == "ollama") item {
                Text("Type a model id that your Ollama has:", color = Spook.Mist, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Field(custom, "model id") { custom = it } }
                    TextButton(onClick = { if (custom.isNotBlank()) pick(custom.trim()) }) { Text("Use", color = Spook.Pumpkin) }
                }
            }
            if (provider != "ollama") item {
                Field(q, "Search models or makers") { q = it }
                if (provider == "openrouter") {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        useCases.forEach { (k, l) -> Pill(l, useCase == k, small = true) { useCase = if (useCase == k) "" else k } }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    abilities.forEach { (k, l) -> Pill(l, ability == k, small = true, color = Spook.Witch) { ability = if (ability == k) "" else k } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                    Text("Sort ", color = Spook.Mist, fontSize = 12.sp)
                    Choice(listOf("smart" to "smartest", "cheap" to "cheapest", "ctx" to "biggest context", "new" to "newest"), sort) { sort = it }
                }
                Text("${shown.size} models · ${groups.size} makers" + if (useCase.isNotEmpty()) " · OpenRouter's top picks for ${useCases.first { it.first == useCase }.second}" else "",
                    color = Spook.Fog, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            // vendor groups
            groups.forEach { (vendor, list) ->
                val auto = q.isNotBlank() || shown.size <= 25 || list.any { it.id == current?.model && provider == current.provider }
                val open = openGroups[vendor] ?: auto
                item(key = "g-$vendor") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Spook.Crypt2.copy(alpha = 0.9f))
                        .clickable { openGroups[vendor] = !open }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(vendor, color = Spook.Bone, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        val best = list.mapNotNull { it.score }.maxOrNull()
                        if (best != null) Text("best 🧠${"%.0f".format(best)}  ", color = Spook.Mist, fontSize = 11.sp)
                        Text("${list.size} ${if (open) "▴" else "▾"}", color = Spook.Pumpkin, fontSize = 13.sp)
                    }
                }
                if (open) items(list, key = { "m-$vendor-${it.id}" }) { m -> ModelRow(provider, m, current, onPick = { pick(m.id) }) }
            }
        }
    }
}

private fun CatalogModel.toEx(vendor: String) = ExModel(id, name, vendor, description, inPrice ?: 0.0, outPrice ?: 0.0, context ?: 0,
    if (modality.contains("image")) listOf("text", "image") else listOf("text"), listOf("text"), false, false, false, 0, null, null)

/** "Which model?" — pick a goal, get three clear choices with score bars and the price of a typical reply. */
@Composable
private fun ModelGuide(ex: Explore, current: ModelRef?, onPick: (String) -> Unit) {
    var goal by remember { mutableStateOf(ex.guide.first().key) }
    val g = ex.guide.firstOrNull { it.key == goal } ?: ex.guide.first()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Spook.cardBrush).border(1.dp, Spook.Crypt3, RoundedCornerShape(14.dp))
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Which model should I use?", color = Spook.Pumpkin, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ex.guide.forEach { Pill(it.title, goal == it.key, small = true) { goal = it.key } }
        }
        Text(g.sub + " · scores are Artificial Analysis, 0-100", color = Spook.Fog, fontSize = 10.5.sp)
        if (g.picks.isEmpty()) Text("No scored models for this yet.", color = Spook.Mist, fontSize = 12.sp)
        g.picks.forEach { p ->
            val m = ex.byId[p.id] ?: return@forEach
            val on = current?.model == p.id
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) Spook.Crypt3 else Spook.Crypt2.copy(alpha = 0.7f))
                .clickable { onPick(p.id) }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.label, color = Spook.Slime, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(if (on) "in use" else "use ▸", color = Spook.Pumpkin, fontSize = 11.sp)
                }
                Text(m.name, color = Spook.Bone, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.vendor, color = Spook.Mist, fontSize = 10.5.sp)
                ScoreBar("Smart", m.score, g.key == "smart" || g.key == "free")
                ScoreBar("Coding", m.coding, g.key == "coding")
                ScoreBar("Tools", m.agentic, g.key == "agent")
                // a typical reply is ~2,000 tokens in and ~500 out
                val perReply = (m.inPrice * 2000 + m.outPrice * 500) / 1_000_000
                Text(if (m.free) "free · ctx ${Fmt.tokens(m.ctx.toLong())}" else
                    "in $${"%.2f".format(m.inPrice)}${Fmt.brl(m.inPrice)} · out $${"%.2f".format(m.outPrice)}${Fmt.brl(m.outPrice)} /M · ≈ " +
                    "$${if (perReply < 0.01) "%.4f".format(perReply) else "%.3f".format(perReply)}${Fmt.brl(perReply)} per reply · ctx ${Fmt.tokens(m.ctx.toLong())}",
                    color = Spook.Moon, fontSize = 10.5.sp)
            }
        }
    }
}

@Composable
private fun ScoreBar(label: String, score: Double?, highlight: Boolean) {
    if (score == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (highlight) Spook.Bone else Spook.Fog, fontSize = 10.5.sp, modifier = Modifier.width(46.dp))
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Spook.Crypt3)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((score / 100.0).coerceIn(0.02, 1.0).toFloat()).clip(RoundedCornerShape(3.dp))
                .background(if (highlight) Spook.Pumpkin else Spook.Mist.copy(alpha = 0.6f)))
        }
        Text("%.0f".format(score), color = if (highlight) Spook.Bone else Spook.Fog, fontSize = 10.5.sp, modifier = Modifier.width(30.dp).padding(start = 6.dp))
    }
}

@Composable
private fun RankCard(c: ExCard, ex: Explore, current: ModelRef?, onPick: (String) -> Unit) {
    var more by remember { mutableStateOf(false) }
    Column(Modifier.width(270.dp).clip(RoundedCornerShape(14.dp)).background(Spook.cardBrush).border(1.dp, Spook.Crypt3, RoundedCornerShape(14.dp))
        .animateContentSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(c.title, color = Spook.Pumpkin, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(c.sub, color = Spook.Fog, fontSize = 10.5.sp)
        c.items.take(if (more) 10 else 5).forEachIndexed { i, (id, note) ->
            val m = ex.byId[id]
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (current?.model == id) Spook.Crypt3 else Color.Transparent)
                .clickable { onPick(id) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Spook.Mist, fontSize = 11.sp, modifier = Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(m?.name ?: id.substringAfter('/'), color = Spook.Bone, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((m?.vendor?.let { "$it · " } ?: "") + note, color = Spook.Mist, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                priceLine(m)?.let { Text(it.removeSuffix(" /M").replace(" in · ", " / ").replace(" out", ""), color = Spook.Moon, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp)) }
            }
        }
        if (c.items.size > 5) Text(if (more) "less ▴" else "top 10 ▾", color = Spook.Pumpkin, fontSize = 11.sp, modifier = Modifier.clickable { more = !more })
    }
}

@Composable
private fun ModelRow(provider: String, m: ExModel, current: ModelRef?, onPick: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    // OpenRouter's list cuts long descriptions ("…long-horizon..."); "more" fetches the whole text once
    var full by remember(m.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cut = m.description.trimEnd().endsWith("...") || m.description.trimEnd().endsWith("…")
    fun loadFull() {
        if (!cut || full != null) return
        val orId = when (provider) { "openai" -> "openai/${m.id}"; "gemini" -> "google/${m.id}"; else -> m.id }
        scope.launch {
            full = try { Net.get("/api/v2/models/describe?id=" + Net.enc(orId)).optString("description").ifBlank { null } } catch (_: Exception) { null }
        }
    }
    val on = current?.provider == provider && current.model == m.id
    Column(Modifier.fillMaxWidth().padding(start = 8.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Spook.Crypt3 else Spook.Crypt2.copy(alpha = 0.55f))
        .clickable(onClick = onPick).animateContentSize().padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.name, color = if (on) Spook.Pumpkin else Spook.Bone, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.id, color = Spook.Fog, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            m.score?.let { Text("🧠${"%.0f".format(it)}", color = Spook.Slime, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            Text(if (m.free) "free" else "in $${"%.2f".format(m.inPrice)}${Fmt.brl(m.inPrice)} · out $${"%.2f".format(m.outPrice)}${Fmt.brl(m.outPrice)} /M", color = Spook.Moon, fontSize = 11.sp)
            if (m.ctx > 0) Text("ctx ${Fmt.tokens(m.ctx.toLong())}", color = Spook.Mist, fontSize = 11.sp)
            Text(buildString {
                if ("image" in m.output) append("🖼"); if ("audio" in m.output || "audio" in m.input) append("🎙"); if ("image" in m.input) append("👁")
                if (m.tools) append("🛠"); if (m.reasoning) append("🧠")
            }, fontSize = 11.sp)
        }
        if (m.description.isNotBlank()) {
            Text(if (open) (full ?: m.description) else m.description, color = Spook.Mist, fontSize = 12.sp, maxLines = if (open) 40 else 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Text(if (open) "less ▴" else "more ▾", color = Spook.Pumpkin, fontSize = 11.sp, modifier = Modifier.clickable { open = !open; if (open) loadFull() }.padding(top = 2.dp))
        }
    }
}
