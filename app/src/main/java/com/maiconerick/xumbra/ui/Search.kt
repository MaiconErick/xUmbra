package com.maiconerick.xumbra.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.data.*
import com.maiconerick.xumbra.ui.theme.Spook
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Full-text search over every chat on the Pi. */
@Composable
fun SearchScreen() {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val rows by Umbra.sessions.collectAsState()
    LaunchedEffect(q) {
        if (q.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(350); busy = true
        try { hits = Net.get("/api/v2/search?q=" + Net.enc(q.trim()) + "&limit=80").optJSONArray("hits").objects() } catch (_: Exception) { }
        busy = false
    }
    Column(Modifier.fillMaxSize().background(Spook.skyBrush)) {
        ScreenHeader("Search", { Nav.back() })
        Column(Modifier.padding(12.dp)) {
            Field(q, "Words in any chat (titles and messages)") { q = it }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp), color = Spook.Pumpkin, trackColor = Spook.Crypt3)
            if (q.length >= 2 && !busy && hits.isEmpty()) Text("Nothing found.", color = Spook.Mist, modifier = Modifier.padding(top = 12.dp))
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(hits) { h ->
                val sid = h.optString("sid")
                val row = rows.firstOrNull { it.id == sid }
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(Spook.Crypt2.copy(alpha = 0.8f))
                    .clickable { Nav.openChat(sid) }.padding(10.dp)) {
                    Row {
                        Text(row?.title ?: "Chat $sid", color = Spook.Pumpkin, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(Fmt.listTime(h.optDouble("ts", 0.0)), color = Spook.Mist, fontSize = 11.sp)
                    }
                    val role = when (h.optString("role")) { "user" -> "You: "; "assistant" -> "Umbra: "; "title" -> "Title: "; else -> "" }
                    Text(highlight(role + h.optString("snippet"), q.trim()), color = Spook.Bone, fontSize = 13.sp, maxLines = 3)
                }
            }
        }
    }
}

private fun highlight(text: String, q: String) = buildAnnotatedString {
    var i = 0
    val lo = text.lowercase(); val ql = q.lowercase()
    while (q.isNotEmpty()) {
        val j = lo.indexOf(ql, i)
        if (j < 0) break
        append(text.substring(i, j))
        withStyle(SpanStyle(color = Spook.Slime, fontWeight = FontWeight.Bold)) { append(text.substring(j, j + q.length)) }
        i = j + q.length
    }
    append(text.substring(i))
}

/** "Share → xUmbra": pick a chat (or a new one) and the text/image lands in its composer. */
@Composable
fun SharePicker(share: Pair<String, List<Uri>>, onClose: () -> Unit) {
    val rows by Umbra.sessions.collectAsState()
    var newChat by remember { mutableStateOf(false) }
    if (newChat) NewChatPage { newChat = false; onClose() }
    LaunchedEffect(Unit) {
        // the next chat that opens (picked here or created) gets the draft
    }
    fun pick(sid: String) {
        Nav.pendingDraft.value = Triple(sid, share.first, share.second)
        onClose(); Nav.openChat(sid)
    }
    Page("Send to Umbra", onClose) {
        Column(Modifier.padding(12.dp)) {
            if (share.first.isNotBlank()) Text("“${share.first.take(200)}”", color = Spook.Mist, fontSize = 13.sp)
            if (share.second.isNotEmpty()) Text("🖼 ${share.second.size} image(s)", color = Spook.Mist, fontSize = 13.sp)
            Gap(8)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Spook.pumpkinBrush).clickable {
                // new chat: create it, then hand over the draft
                Umbra.launch {
                    val sid = Umbra.create(JSONObject())
                    if (sid != null) pick(sid)
                }
            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("＋ New chat with this", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Bold)
            }
            Gap(8)
            LazyColumn {
                items(rows, key = { it.id }) { r ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { pick(r.id) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChatAvatar(r.id, r.title, r.customIcon, Umbra.persona(r.persona), 36)
                        Spacer(Modifier.width(10.dp))
                        Text(r.title, color = Spook.Bone, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
            }
        }
    }
}
