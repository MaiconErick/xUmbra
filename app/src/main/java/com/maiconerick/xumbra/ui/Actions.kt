@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.data.Msg
import com.maiconerick.xumbra.data.ToolCall
import com.maiconerick.xumbra.data.TurnInfo
import com.maiconerick.xumbra.ui.theme.Spook

/** What one reply did, grouped. Order = order the icons appear under the message. */
private data class Group(val key: String, val icon: String, val title: String, val calls: List<ToolCall>)

private fun groupsOf(t: TurnInfo): List<Group> {
    val by = t.tools.filter { it.name != "load_tools" }.groupBy {
        when (it.name) {
            "web_search", "browser" -> "web"
            "read_file", "write_file", "list_dir" -> "files"
            "shell", "python", "system_snapshot" -> "run"
            "memory" -> "memory"
            "generate_image", "tts" -> "media"
            "agents" -> "agents"
            else -> "other"
        }
    }
    val order = listOf(
        Triple("web", "🔎", "Web"), Triple("files", "📁", "Files touched"), Triple("run", "💻", "Commands"),
        Triple("memory", "🧠", "Memory"), Triple("media", "🖼", "Made"), Triple("agents", "⏰", "Scheduled"), Triple("other", "🛠", "Other tools"),
    )
    val out = order.mapNotNull { (k, i, title) -> by[k]?.let { Group(k, i, title, it) } }.toMutableList()
    // memory writes reported by the server without a tool call (e.g. chat categorised)
    if (out.none { it.key == "memory" } && t.memory.isNotEmpty()) out.add(Group("memory", "🧠", "Memory", emptyList()))
    return out
}

/** Small icons under a reply: 🔎2 📁1 🧠 … tap the bubble for details. */
@Composable
fun ActionIcons(t: TurnInfo) {
    val gs = remember(t) { groupsOf(t) }
    if (gs.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        gs.forEach { g ->
            val n = if (g.calls.isNotEmpty()) g.calls.size else t.memory.size
            val failed = g.calls.any { failedCall(it) }
            Row(Modifier.clip(RoundedCornerShape(50)).background(if (failed) Color(0x55C8243F) else Spook.Crypt3.copy(alpha = 0.8f))
                .padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(g.icon, fontSize = 11.sp)
                if (n > 1) Text(" $n", color = Spook.Mist, fontSize = 10.5.sp)
            }
            // Reddit badge right after the web icon when a search included it
            if (g.key == "web" && g.calls.any { it.raw?.optBoolean("reddit") == true })
                Text("r/", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFF4500)).padding(horizontal = 7.dp, vertical = 2.dp))
        }
    }
}

private fun failedCall(c: ToolCall) = c.result?.let { it.startsWith("ERROR") || it.startsWith("[exit") || it.startsWith("Web search failed") || it.startsWith("Browser error") } == true

private fun sourcesIn(result: String?): List<String> =
    Regex("""https?://[^\s)\]]+""").findAll(result.orEmpty().substringAfter("Sources:", "")).map { it.value.trimEnd('.', ',') }.distinct().toList()

/** Expanded card under a reply: one block per kind of thing it did, then tokens & cost. */
@Composable
fun TurnGroups(m: Msg, t: TurnInfo) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x66000000)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (t.inTok + t.outTok > 0) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("🧮 Where the tokens went", color = Spook.Pumpkin, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            UsageBreakdown(t)
        }
        groupsOf(t).forEach { g ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${g.icon} ${g.title}", color = Spook.Pumpkin, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                when (g.key) {
                    "web" -> g.calls.forEach { c ->
                        val q = c.arg("query").ifBlank { c.arg("url").ifBlank { c.args } }
                        Text((if (c.name == "browser") "🌐 ${c.arg("action")} " else "“") + q + (if (c.name == "browser") "" else "”") +
                            (if (c.raw?.optBoolean("reddit") == true) "  · + Reddit" else "") + (c.ms?.let { "  · ${Fmt.secs(it / 1000.0)}" } ?: ""),
                            color = Spook.Bone, fontSize = 12.sp)
                        val src = sourcesIn(c.result)
                        src.take(6).forEach { u ->
                            Text("↗ " + u.removePrefix("https://").removePrefix("www.").take(60), color = Spook.Moon, fontSize = 11.sp,
                                modifier = Modifier.clickable { openUrl(ctx, u) }.padding(start = 10.dp))
                        }
                        if (failedCall(c)) Text(c.result.orEmpty().take(200), color = Spook.Blood, fontSize = 11.sp)
                    }
                    "files" -> {
                        listOf("read_file" to "read", "write_file" to "wrote", "list_dir" to "listed").forEach { (n, verb) ->
                            g.calls.filter { it.name == n }.forEach { c ->
                                Text("$verb  ${c.arg("path").ifBlank { c.args }}" + (if (failedCall(c)) "  ✗" else ""), color = Spook.Bone, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    "run" -> g.calls.forEach { c ->
                        var open by remember { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Spook.Crypt3.copy(alpha = 0.5f)).clickable { open = !open }.padding(6.dp)) {
                            Text((if (c.name == "python") "🐍 " else "$ ") + c.arg("command").ifBlank { c.arg("code").ifBlank { c.name } } +
                                (c.ms?.let { "  · ${Fmt.secs(it / 1000.0)}" } ?: "") + (if (failedCall(c)) "  ✗" else ""),
                                color = if (failedCall(c)) Spook.Blood else Spook.Bone, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                                maxLines = if (open) 30 else 2, overflow = TextOverflow.Ellipsis)
                            if (open && !c.result.isNullOrBlank()) SelectionContainer { Text(c.result, color = Spook.Mist, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                        }
                    }
                    "memory" -> {
                        g.calls.forEach { c ->
                            val op = c.arg("op")
                            val what = c.arg("rule").ifBlank { c.arg("fact").ifBlank { c.arg("text").ifBlank { c.arg("content").ifBlank { c.arg("word") } } } }
                            val where = listOf(c.arg("topic"), c.arg("category"), c.arg("section")).filter { it.isNotBlank() }.joinToString(" / ")
                            Text("${opName(op)}${if (where.isNotBlank()) " · $where" else ""}${if (what.isNotBlank()) " — " + what.take(140) else ""}",
                                color = Spook.Bone, fontSize = 12.sp)
                        }
                        if (g.calls.isEmpty()) t.memory.forEach { Text(it.title + if (it.detail.isNotBlank()) " — ${it.detail}" else "", color = Spook.Bone, fontSize = 12.sp) }
                    }
                    "agents" -> g.calls.forEach { c -> Text("${c.arg("op")} ${c.arg("name").ifBlank { c.arg("id") }}", color = Spook.Bone, fontSize = 12.sp) }
                    else -> g.calls.forEach { c -> Text("${c.name}  ${c.args.take(120)}", color = Spook.Bone, fontSize = 12.sp) }
                }
            }
        }
        // tokens & cost
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("🤖 Model", color = Spook.Pumpkin, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModelLabel(t.provider, t.model, nameSize = 12f, modifier = Modifier.weight(1f))
                Text(Fmt.secs(t.durationSec), color = Spook.Mist, fontSize = 11.sp)
            }
            priceLine(priceOf(t.provider, t.model))?.let { Text("💲 $it", color = Spook.Moon, fontSize = 11.sp) }
            if (t.directSearch) Text("answered straight from the search (no rewrite)", color = Spook.Mist, fontSize = 11.sp)
        }
        if (t.breakdown.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Text((if (open) "▾ " else "▸ ") + "What was in the prompt", color = Spook.Mist, fontSize = 11.sp, modifier = Modifier.clickable { open = !open })
            if (open) {
                val parts = t.breakdown.filterKeys { it != "total_estimated" }.entries.sortedByDescending { it.value }
                val total = t.breakdown["total_estimated"] ?: parts.sumOf { it.value }
                parts.forEach { (k, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(k, color = Spook.Bone, fontSize = 11.sp, modifier = Modifier.width(130.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.weight(1f)) { UsageBar(v.toDouble(), total.toDouble(), Spook.Witch, height = 6) }
                        Text(" ${Fmt.tokens(v.toLong())}", color = Spook.Mist, fontSize = 10.5.sp, modifier = Modifier.width(44.dp))
                    }
                }
            }
        }
    }
}

private fun opName(op: String) = when (op) {
    "rule" -> "saved a rule"; "fact" -> "saved a fact"; "trait" -> "learned about you"; "remember" -> "remembered"
    "topic" -> "updated a topic"; "new_topic" -> "started a topic"; "word" -> "logged a word"; "read" -> "opened"; "trait_history" -> "looked back at"
    else -> op
}
