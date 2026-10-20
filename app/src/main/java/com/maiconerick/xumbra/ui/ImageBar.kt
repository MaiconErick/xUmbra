@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maiconerick.xumbra.data.ChatSettings
import com.maiconerick.xumbra.data.Money
import com.maiconerick.xumbra.data.Net
import com.maiconerick.xumbra.data.Umbra
import com.maiconerick.xumbra.data.objects
import com.maiconerick.xumbra.data.strings
import com.maiconerick.xumbra.ui.theme.Spook
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Image models for the picker (loaded once, refreshed when the page opens). */
object ImageModels {
    val list = mutableStateOf<List<JSONObject>>(emptyList())
    val default = mutableStateOf("")
    suspend fun load() {
        try {
            val o = Net.get("/api/v2/image-models")
            list.value = o.optJSONArray("models").objects()
            default.value = o.optString("default")
        } catch (_: Exception) { }
    }
    suspend fun ensure() { if (list.value.isEmpty()) load() }
    fun name(id: String?): String = list.value.firstOrNull { it.optString("id") == id }?.optString("name") ?: (id ?: "")
}

private fun price(m: JSONObject): Double = if (m.has("avg")) m.optDouble("avg") else m.optDouble("est")
private fun priceText(m: JSONObject) = Fmt.money(price(m)) + "/picture"

/** The 🎨 button next to the ghost toggle in image chats. */
@Composable
fun ImageModelButton(sid: String, s: ChatSettings) {
    var open by remember { mutableStateOf(false) }
    Text("🎨", fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable { open = true }.padding(10.dp))
    if (open) ImageModelPage(sid, s) { open = false }
}

/**
 * Pick the image model for this chat: ordered, with what each one is good at,
 * its Arena rank, the real price and speed measured on the Pi and a sample picture.
 */
@Composable
fun ImageModelPage(sid: String, s: ChatSettings?, onClose: () -> Unit) {
    LaunchedEffect(Unit) { ImageModels.load() }
    val models by ImageModels.list
    val def by ImageModels.default
    val scope = rememberCoroutineScope()
    var sort by remember { mutableStateOf("recommended") }
    var filter by remember { mutableStateOf("all") }
    var ask by remember { mutableStateOf<JSONObject?>(null) }
    var viewer by remember { mutableStateOf<String?>(null) }
    val current = s?.imageModel

    val shown = remember(models, sort, filter) {
        val f = models.filter { m ->
            val tags = m.optJSONArray("tags").strings()
            when (filter) {
                "edits" -> tags.any { it == "edits" || it == "exact pixel version" || it == "keeps characters" }
                "pixel" -> m.optString("provider") == "code"
                "text" -> tags.contains("text in images")
                "fanart" -> m.optString("provider") != "openai"
                else -> true
            }
        }
        when (sort) {
            "quality" -> f.sortedByDescending { if (it.has("score")) it.optInt("score") else 0 }
            "cheap" -> f.sortedBy { price(it) }
            "fast" -> f.sortedBy { if (it.has("secs")) it.optDouble("secs") else 999.0 }
            else -> f
        }
    }

    fun pick(m: JSONObject?) {
        val id = m?.optString("id") ?: ""
        Umbra.patch(sid, JSONObject().put("image_model", id))
        if (m != null && !s?.lastImagePrompt.isNullOrBlank()) ask = m else onClose()
    }

    Page("Image model", onClose) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Spook.Crypt2.copy(alpha = 0.7f)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("How to choose", color = Spook.Pumpkin, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("• Best value: GPT Image 2.5 Flare — top quality for about a cent.\n" +
                        "• Fan art (Pokémon…) or editing your own pictures: Nano Banana 2.1.\n" +
                        "• Quick drafts: Nano Banana 2 Lite (~4 s).\n" +
                        "• Real pixel art / an exact pixel version of your picture: Code art · Claude — the others paint pixel-STYLE pictures.",
                        color = Spook.Bone, fontSize = 12.5.sp, lineHeight = 17.sp)
                    Text("🏆 = rank on arena.ai's text-to-image leaderboard (people vote blind). Price and time were measured here on the Pi; tap a picture to see it big.",
                        color = Spook.Mist, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort", color = Spook.Mist, fontSize = 12.sp)
                    listOf("recommended" to "Recommended", "quality" to "Best quality", "cheap" to "Cheapest", "fast" to "Fastest")
                        .forEach { (k, l) -> Pill(l, sort == k, small = true) { sort = k } }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Show", color = Spook.Mist, fontSize = 12.sp)
                    listOf("all" to "All", "edits" to "Edits my pictures", "pixel" to "True pixel art", "text" to "Text in images", "fanart" to "Fan art OK")
                        .forEach { (k, l) -> Pill(l, filter == k, small = true, color = Spook.Moon) { filter = k } }
                }
            }
            item {
                val on = current.isNullOrBlank()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Spook.Pumpkin.copy(alpha = 0.16f) else Spook.Crypt2.copy(alpha = 0.6f))
                    .border(1.dp, if (on) Spook.Pumpkin else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { pick(null) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Server default", color = Spook.Bone, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("now: " + ImageModels.name(def), color = Spook.Mist, fontSize = 12.sp)
                    }
                    if (on) Text("✓", color = Spook.Pumpkin, fontSize = 18.sp)
                }
            }
            items(shown, key = { it.optString("id") }) { m ->
                ModelCard(m, selected = m.optString("id") == current, isDefault = m.optString("id") == def,
                    onPick = { pick(m) }, onSample = { viewer = it },
                    onDefault = {
                        scope.launch {
                            try { ImageModels.default.value = Net.post("/api/v2/image-models/default", JSONObject().put("id", m.optString("id"))).optString("default") }
                            catch (e: Exception) { Umbra.banner.value = "Couldn't save: ${e.message}" }
                        }
                    })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    viewer?.let { ImageViewer(it) { viewer = null } }
    ask?.let { m ->
        AlertDialog(
            onDismissRequest = { ask = null; onClose() },
            containerColor = Spook.Crypt2,
            title = { Text("Paint the last picture again?", color = Spook.Bone) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("“" + (s?.lastImagePrompt ?: "").take(220) + "”", color = Spook.Mist, fontSize = 13.sp)
                    Text("with ${m.optString("name")} · ${Money.sourceName(m.optString("provider"))} · ${priceText(m)}",
                        color = sourceColor(m.optString("provider")), fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = m.optString("id"); ask = null; onClose()
                    scope.launch {
                        try { Net.post("/api/v2/sessions/$sid/regen-image", JSONObject().put("model", id)) }
                        catch (e: Exception) { Umbra.banner.value = "Couldn't start: ${e.message}" }
                    }
                }) { Text("Generate", color = Spook.Pumpkin, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { ask = null; onClose() }) { Text("Just switch", color = Spook.Mist) } },
        )
    }
}

@Composable
private fun ModelCard(m: JSONObject, selected: Boolean, isDefault: Boolean, onPick: () -> Unit, onSample: (String) -> Unit, onDefault: () -> Unit) {
    val prov = m.optString("provider")
    val color = sourceColor(prov)
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(if (selected) color.copy(alpha = 0.14f) else Spook.Crypt2.copy(alpha = 0.75f))
        .border(1.dp, if (selected) color else color.copy(alpha = 0.18f), shape)
        .clickable(onClick = onPick).padding(10.dp)) {
        val sample = m.optString("sample")
        if (sample.isNotBlank()) {
            val url = Net.url("/api/v2/image-samples/$sample")
            AsyncImage(url, null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).clickable { onSample(url) })
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Money.sourceName(prov), color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                if (isDefault) Text("★ default", color = Spook.Pumpkin, fontSize = 10.5.sp)
                Spacer(Modifier.weight(1f))
                if (selected) Text("✓ this chat", color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(m.optString("name"), color = Spook.Bone, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            // the numbers that matter: quality rank, real price, real speed
            val stats = buildList {
                if (m.has("rank")) add("🏆 #${m.optInt("rank")} · ${m.optInt("score")}")
                add("💲 " + priceText(m) + if (m.has("made")) "" else " (est.)")
                if (m.has("secs")) add("⏱ ~${m.optDouble("secs").toInt()} s")
            }
            Text(stats.joinToString("   "), color = Spook.Slime, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                m.optJSONArray("tags").strings().forEach { t ->
                    val good = t.startsWith("#1") || t.startsWith("#2") || t in setOf("best value", "Google's best", "true pixel art", "fastest", "exact pixel version")
                    val bad = t in setOf("older", "oldest", "slow", "pricey", "crude")
                    Text(t, color = if (good) Color.Black else Spook.Bone, fontSize = 10.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(if (good) Spook.Pumpkin else if (bad) Spook.Crypt3.copy(alpha = 0.6f) else Spook.Crypt3)
                            .padding(horizontal = 7.dp, vertical = 2.dp))
                }
            }
            Text(m.optString("note"), color = Spook.Mist, fontSize = 12.sp, lineHeight = 16.sp)
            if (!isDefault) Text("Make it the default for all chats", color = Spook.Moon, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDefault).padding(vertical = 2.dp))
        }
    }
}
