package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.data.Net
import com.maiconerick.xumbra.data.objects
import com.maiconerick.xumbra.ui.theme.Spook
import org.json.JSONObject

/** 🟢 under 0.5 s · 🟡 under 2 s · 🟠 under 5 s · 🔴 5 s or more */
private fun speedColor(ms: Int): Color = when {
    ms < 500 -> Color(0xFF5BD44A)
    ms < 2000 -> Color(0xFFFFD54A)
    ms < 5000 -> Color(0xFFFF9A2E)
    else -> Color(0xFFFF4D5E)
}

private fun dur(ms: Int): String = if (ms < 1000) "$ms ms" else String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)

@Composable
private fun DurChip(ms: Int?) {
    if (ms == null) return
    val c = speedColor(ms)
    Text(dur(ms), color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 1.dp))
}

private fun JSONObject.intOrNull(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)

/**
 * "What Umbra did": every message, oldest first, split into sections (your message,
 * memory, preparing the prompt, web search, pictures, writing the reply, after the reply)
 * with a plain explanation of why each happens, and every step's time and duration.
 */
@Composable
fun DebugEventsPage(sid: String, onClose: () -> Unit) {
    var turns by remember { mutableStateOf<List<JSONObject>?>(null) }
    var raw by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        turns = try { Net.get("/api/v2/sessions/$sid/story").optJSONArray("turns").objects() } catch (_: Exception) { emptyList() }
    }
    if (raw) RawEventsPage(sid) { raw = false }
    val list = rememberLazyListState()
    LaunchedEffect(turns?.size) { turns?.let { if (it.isNotEmpty()) list.scrollToItem(it.size) } }   // newest at the bottom, like the chat
    Page("What Umbra did", onClose, actions = {
        Text("↻", color = Spook.Mist, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { reload++ }.padding(10.dp))
        Text("raw log", color = Spook.Mist, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { raw = true }.padding(10.dp))
    }) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Each message you sent, oldest first. Tap a section to see what Umbra did, why, and how long each step took.\n" +
                    "🟢 under 0.5 s · 🟡 under 2 s · 🟠 under 5 s · 🔴 5 s or more",
                    color = Spook.Mist, fontSize = 12.sp, lineHeight = 16.sp)
            }
            when {
                turns == null -> item { Text("Loading…", color = Spook.Mist) }
                turns!!.isEmpty() -> item { Text("Nothing yet — send a message in this chat first.", color = Spook.Mist) }
                else -> items(turns!!) { TurnCard(it) }
            }
        }
    }
}

@Composable
private fun TurnCard(t: JSONObject) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Spook.Crypt2.copy(alpha = 0.8f)).border(1.dp, Spook.Crypt3, shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.optString("time"), color = Spook.Pumpkin, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text("“" + t.optString("user") + "”", color = Spook.Bone, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            t.intOrNull("total_ms")?.let { Column(horizontalAlignment = Alignment.End) { DurChip(it); Text("until the reply", color = Spook.Mist, fontSize = 9.5.sp) } }
        }
        t.optJSONArray("groups").objects().forEach { GroupRow(it) }
    }
}

@Composable
private fun GroupRow(g: JSONObject) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val isAfter = g.optString("key") == "after"
    val isErr = g.optString("key") == "error"
    Column(Modifier.fillMaxWidth().clip(shape).background(if (isErr) Color(0x33FF4D5E) else Color(0x33000000))
        .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.optString("emoji"), fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(g.optString("title"), color = Spook.Bone, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                val sub = buildList {
                    add(g.optString("time"))
                    val n = g.optJSONArray("steps")?.length() ?: 0
                    add("$n step" + if (n == 1) "" else "s")
                    if (isAfter && !g.isNull("after_reply_s")) add("started ${g.optDouble("after_reply_s")} s after you got the reply")
                }
                Text(sub.joinToString(" · "), color = Spook.Mist, fontSize = 10.5.sp)
            }
            DurChip(g.intOrNull("ms"))
            Text(if (open) "  ▴" else "  ▾", color = Spook.Mist, fontSize = 12.sp)
        }
        if (open) {
            Text(g.optString("why"), color = Spook.Moon, fontSize = 12.sp, fontStyle = FontStyle.Italic, lineHeight = 16.sp)
            g.optJSONArray("steps").objects().forEach { StepRow(it) }
        }
    }
}

@Composable
private fun StepRow(s: JSONObject) {
    var more by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    val detail = s.optString("detail")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Spook.Crypt3.copy(alpha = 0.55f)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.optString("time"), color = Spook.Mist, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(s.optString("title"), color = Spook.Bone, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            DurChip(s.intOrNull("ms"))
        }
        if (detail.isNotBlank()) SelectionContainer {
            Text(detail, color = Spook.Bone.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 16.sp,
                maxLines = if (more) 60 else 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { more = !more })
        }
        Row {
            if (detail.length > 220 || detail.count { it == '\n' } > 3)
                Text(if (more) "less" else "more", color = Spook.Pumpkin, fontSize = 10.5.sp, modifier = Modifier.clickable { more = !more }.padding(end = 12.dp))
            Text(if (showRaw) "hide raw" else "raw", color = Spook.Mist, fontSize = 10.5.sp, modifier = Modifier.clickable { showRaw = !showRaw })
        }
        if (showRaw) SelectionContainer {
            Text(s.optJSONObject("raw")?.toString(2)?.take(8000) ?: "{}", color = Spook.Moon, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
