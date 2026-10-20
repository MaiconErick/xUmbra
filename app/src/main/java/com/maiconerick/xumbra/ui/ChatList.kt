@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maiconerick.xumbra.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maiconerick.xumbra.App
import com.maiconerick.xumbra.data.*
import com.maiconerick.xumbra.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen() {
    val rows by Umbra.sessions.collectAsState()
    val loaded by Umbra.sessionsLoaded.collectAsState()
    val banner by Umbra.banner.collectAsState()
    val personas by Umbra.personas.collectAsState()
    val conn by Hub.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var newChat by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf<SessionRow?>(null) }
    LaunchedEffect(Unit) { Umbra.loadPersonas(); Umbra.loadTools() }

    val groups = remember(rows) { rows.mapNotNull { it.group }.distinct().sorted() }
    val tags = remember(rows) { rows.flatMap { it.tags }.distinct().sorted() }
    val q = query.trim()
    val shown = rows.filter { r ->
        !r.secret && (q.isEmpty() || r.title.contains(q, true) || r.id.contains(q) || (r.last?.text?.contains(q, true) == true) ||
            (Umbra.persona(r.persona)?.displayName?.contains(q, true) == true)) &&
            when {
                filter == "all" -> true
                filter == "starred" -> r.starred
                filter == "busy" -> r.busy
                filter.startsWith("g:") -> r.group == filter.drop(2)
                filter.startsWith("t:") -> filter.drop(2) in r.tags
                filter.startsWith("p:") -> r.persona == filter.drop(2)
                else -> true
            }
    }

    if (newChat) NewChatPage { newChat = false }
    quick?.let { r -> ChatQuickSheet(r) { quick = null } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Spook.Crypt.copy(alpha = 0.55f)).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(60.dp)) {
                Cobweb(56.dp, right = false)
                Row(Modifier.align(Alignment.CenterStart).padding(start = 12.dp).vaultDoor(), verticalAlignment = Alignment.CenterVertically) {
                    FloatingGhost(30.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("xUmbra", fontFamily = Spook.Creepy, fontSize = 30.sp, color = Spook.Pumpkin)
                    Spacer(Modifier.width(8.dp))
                    ConnBubble()
                }
                BatFlyBy(Modifier.fillMaxSize(), durationMs = 12000, size = 20.dp)
                Row(Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = { Nav.go(Screen.Search) }) { Icon(Icons.Default.ManageSearch, "Search all chats", tint = Spook.Mist) }
                    IconButton(onClick = { Nav.go(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings", tint = Spook.Mist) }
                }
            }
            Banner(banner) { Umbra.banner.value = null }
            // shortcuts to the rest of Umbra
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Shortcut(Sprites.cauldron, "Agents") { Nav.go(Screen.Agents) }
                Shortcut(Sprites.skull, "Memory") { Nav.go(Screen.Memory) }
                Shortcut(Sprites.hat, "Personas") { Nav.go(Screen.Personas) }
                Shortcut(Sprites.wrench, "Tools") { Nav.go(Screen.Tools) }
                Shortcut(Sprites.cauldron, "Model lab") { Nav.go(Screen.Lab) }
                Shortcut(Sprites.tomb, "Pi & usage") { Nav.go(Screen.Server) }
                Shortcut(Sprites.folder, "Terminal") { Nav.go(Screen.Terminal) }
            }
            WalletStrip()
            TextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search the crypt…", color = Spook.Fog) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Spook.Mist) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null, tint = Spook.Mist) } }) else null,
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Spook.Crypt2, unfocusedContainerColor = Spook.Crypt2,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Spook.Bone, unfocusedTextColor = Spook.Bone, cursorColor = Spook.Pumpkin,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            val chips = buildList {
                add("all" to "All")
                if (rows.any { it.starred }) add("starred" to "⭐ Starred")
                if (rows.any { it.busy }) add("busy" to "✍ Answering")
                rows.mapNotNull { it.persona }.distinct().forEach { p -> personas.firstOrNull { it.name == p }?.let { add("p:$p" to "🎭 " + it.displayName) } }
                groups.forEach { add("g:$it" to "📁 $it") }
                tags.forEach { add("t:$it" to "# $it") }
            }
            if (chips.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { (k, l) -> Pill(l, filter == k, small = true) { filter = if (filter == k) "all" else k } }
            }
            if (rows.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PixelSprite(Sprites.tomb, 64.dp)
                    Text(
                        when {
                            !loaded && conn != Conn.Online -> "Reaching the Pi…"
                            !loaded -> "Loading chats…"
                            else -> "No chats yet. Tap ＋ to wake Umbra."
                        }, color = Spook.Mist,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(shown, key = { it.id }) { r ->
                    ChatRow(r, personas.firstOrNull { it.name == r.persona }, onOpen = { Nav.openChat(r.id) }, onLong = { quick = r })
                }
            }
        }
        // new chat
        Row(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(18.dp).clip(RoundedCornerShape(50)).background(Spook.pumpkinBrush)
            .clickable { newChat = true }.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, null, tint = Color.Black)
            Spacer(Modifier.width(6.dp))
            Text("New chat", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Shortcut(sprite: Sprite, label: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Spook.Crypt2.copy(alpha = 0.9f)).border(1.dp, Spook.Crypt3, RoundedCornerShape(50))
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        PixelSprite(sprite, 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Spook.Bone, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(r: SessionRow, persona: Persona?, onOpen: () -> Unit, onLong: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLong).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ChatAvatar(r.id, r.title, r.customIcon, persona, 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.starred) Text("⭐ ", fontSize = 12.sp)
                Text(r.title, color = Spook.Bone, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Fmt.listTime(r.ts), color = if (r.unread > 0) Spook.Pumpkin else Spook.Mist, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (r.busy) {
                    Text("✍ ${persona?.displayName ?: "Umbra"} is writing…", color = Spook.Slime, fontStyle = FontStyle.Italic, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                } else {
                    val l = r.last
                    val prefix = when (l?.role) { "user" -> "You: "; "error" -> "⚠ "; else -> "" }
                    val body = l?.let { (if (it.media > 0) "🖼 " else "") + Notifier.plain(it.text).replace('\n', ' ') }.orEmpty()
                        .ifBlank { if (r.count == 0) "No messages yet" else "" }
                    Text(prefix + body, color = if (l?.role == "error") Spook.Blood else Spook.Mist, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                if (r.queued > 0) Text(" 🕓${r.queued}", color = Spook.Mist, fontSize = 11.sp)
                if (r.proactive) Text(" ⏰", fontSize = 11.sp)
                if (r.hidden) Text(" 👻", fontSize = 11.sp)
                if (r.unread > 0) {
                    Box(Modifier.padding(start = 6.dp).widthIn(min = 20.dp).height(20.dp).clip(CircleShape).background(Spook.pumpkinBrush).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center) {
                        Text(if (r.unread > 99) "99+" else "${r.unread}", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (r.tags.isNotEmpty() || r.group != null) Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                r.group?.let { Text("📁 $it", color = Spook.Witch, fontSize = 10.5.sp) }
                r.tags.take(4).forEach { Text("#$it", color = Spook.Fog, fontSize = 10.5.sp) }
            }
        }
    }
}

private val chatColors = listOf(
    Color(0xFFFF7A18), Color(0xFF7CFF4F), Color(0xFFB07BFF), Color(0xFFFFD54A), Color(0xFFFF5E7A),
    Color(0xFF4FE0E8), Color(0xFFFFA05A), Color(0xFF9BE06A), Color(0xFFE08BFF), Color(0xFFFF8FB8),
)

fun colorFor(key: String): Color {
    var h = 2166136261L
    for (ch in key) h = ((h xor ch.code.toLong()) * 16777619L) and 0xFFFFFFFFL
    return chatColors[(h % chatColors.size).toInt()]
}

/** Persona portrait if the chat has one; else the chat's emoji / colour; else a pixel sprite picked from the id. */
@Composable
fun ChatAvatar(sid: String, title: String, customIcon: String?, persona: Persona?, sizeDp: Int) {
    val icon = customIcon?.takeIf { it.isNotBlank() }
    val color = icon?.takeIf { it.startsWith("#") && it.length == 7 }?.removePrefix("#")?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: colorFor(sid)
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(color.copy(alpha = 0.35f)).border(1.5.dp, color.copy(alpha = 0.8f), CircleShape),
        contentAlignment = Alignment.Center) {
        when {
            persona?.iconUrl != null && (icon == null || icon.startsWith("#")) ->
                AsyncImage(persona.iconUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            icon != null && !icon.startsWith("#") -> Text(icon, fontSize = (sizeDp / 2).sp)
            else -> {
                val sprites = listOf(Sprites.ghost, Sprites.pumpkin, Sprites.skull, Sprites.cauldron, Sprites.moon, Sprites.candy, Sprites.hat, Sprites.tomb)
                PixelSprite(sprites[(sid.hashCode() and 0x7fffffff) % sprites.size], (sizeDp * 0.55f).dp)
            }
        }
    }
}

/**
 * Connection bubble next to the title (same as xWapp): route icon (Wi-Fi = home, lock = Tailscale,
 * globe = custom) and latency bars. Tap = details + ping, long-press = reconnect.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnBubble() {
    val state by Hub.state.collectAsState()
    val route by Net.route.collectAsState()
    val ping by Hub.pingMs.collectAsState()
    val err by Hub.error.collectAsState()
    var open by remember { mutableStateOf(false) }
    val connected = state == Conn.Online && route != null
    val bars = when { !connected || ping < 0 -> 0; ping <= 60 -> 4; ping <= 150 -> 3; ping <= 400 -> 2; else -> 1 }
    val color = when { !connected -> Color(0xFFFF5A5A); bars >= 3 -> Spook.Slime; bars == 2 -> Color(0xFFFFC94A); else -> Color(0xFFFF8A3D) }
    val icon = when {
        !connected -> Icons.Default.CloudOff
        route!!.kind == "ts" -> Icons.Default.VpnLock
        route!!.kind == "custom" -> Icons.Default.Public
        else -> Icons.Default.Wifi
    }
    Row(Modifier.clip(RoundedCornerShape(50)).background(Spook.Crypt2.copy(alpha = 0.9f))
        .combinedClickable(onClick = { if (open && connected) Hub.ping(); open = !open }, onLongClick = { Hub.restart() })
        .animateContentSize().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
            for (i in 1..4) Box(Modifier.width(3.dp).height((3 + i * 3).dp).clip(RoundedCornerShape(1.dp)).background(if (i <= bars) color else Spook.Crypt3))
        }
        if (open) {
            Spacer(Modifier.width(8.dp))
            Column {
                Text(if (connected) route!!.label else if (state == Conn.Connecting) "Connecting…" else "Offline",
                    color = Spook.Bone, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (connected) route!!.host + "  ·  " + (if (ping >= 0) "$ping ms" else "—") else err.ifBlank { "long-press to retry" },
                    color = Spook.Mist, fontSize = 10.5.sp, maxLines = 1)
            }
        }
    }
}

// ───────────── long-press on a chat ─────────────

@Composable
private fun ChatQuickSheet(r: SessionRow, onClose: () -> Unit) {
    var rename by remember { mutableStateOf<String?>(null) }
    var wipe by remember { mutableStateOf(false) }
    if (wipe) Confirm("Erase \"${r.title}\"?", "Deletes this chat's history, summary and debug log from the Pi. This can't be undone.", "Erase forever", danger = true,
        onYes = { Umbra.delete(r.id, wipe = true); wipe = false; onClose() }, onNo = { wipe = false })
    rename?.let { cur ->
        AlertDialog(onDismissRequest = { rename = null }, containerColor = Spook.Crypt2,
            title = { Text("Rename", color = Spook.Bone) },
            text = { Field(cur, "Title") { rename = it } },
            confirmButton = { TextButton(onClick = { Umbra.patch(r.id, JSONObject().put("summary", cur)); rename = null; onClose() }) { Text("Save", color = Spook.Pumpkin) } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel", color = Spook.Mist) } })
    }
    AlertDialog(onDismissRequest = onClose, containerColor = Spook.Crypt2,
        title = { Text(r.title, color = Spook.Bone, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("#${r.id} · ${r.count} messages · ${Fmt.money(r.costUsd)}", color = Spook.Mist, fontSize = 12.sp)
                Gap(6)
                @Composable fun item(label: String, color: Color = Spook.Bone, act: () -> Unit) =
                    Text(label, color = color, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = act).padding(vertical = 10.dp, horizontal = 6.dp))
                item(if (r.starred) "☆ Unstar" else "⭐ Star (keeps it on top)") { Umbra.patch(r.id, JSONObject().put("starred", !r.starred)); onClose() }
                item("✏️ Rename") { rename = r.title }
                item(if (r.unread > 0) "✓ Mark as read" else "💬 Open") { if (r.unread > 0) Umbra.markRead(r.id) else Nav.openChat(r.id); onClose() }
                item(if (r.hidden) "👁 Unhide" else "👻 Hide from the list") { Umbra.patch(r.id, JSONObject().put("hidden", !r.hidden)); onClose() }
                item("🗑 Erase from the Pi…", Spook.Blood) { wipe = true }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = Spook.Mist) } })
}

// ───────────── new chat: pick a persona ─────────────

@Composable
fun NewChatPage(onClose: () -> Unit) {
    val personas by Umbra.personas.collectAsState()
    val tools by Umbra.tools.collectAsState()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // per-chat options (apply to whichever persona is created)
    var title by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var model by remember { mutableStateOf<ModelRef?>(null) }
    var reasoning by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var chosenTools by remember { mutableStateOf<Set<String>?>(null) }
    var picking by remember { mutableStateOf(false) }
    var showClaudeSetup by remember { mutableStateOf(false) }
    if (picking) ModelPickerPage(model, onPick = { model = it; picking = false }) { picking = false }
    if (showClaudeSetup) ClaudeSetupSheet { showClaudeSetup = false }

    fun create(persona: Persona?) {
        if (busy) return
        busy = true
        scope.launch {
            val body = JSONObject().put("summary", title).put("persona", persona?.name ?: "")
            if (first.isNotBlank()) body.put("first_message", first)
            model?.let { body.put("model", it.json()) }
            if (reasoning.isNotEmpty()) body.put("reasoning", reasoning)
            if (extra.isNotBlank()) body.put("sys_secondary", extra)
            chosenTools?.let { body.put("tools", JSONArray(it.toList())) }
            val sid = Umbra.create(body)
            busy = false
            if (sid != null) { onClose(); Nav.openChat(sid) }
        }
    }

    Page("New chat", onClose) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pick who answers. Tap to start; ▾ to tweak first.", color = Spook.Mist, fontSize = 13.sp)
            val all: List<Persona?> = listOf<Persona?>(null) + personas
            all.forEach { p ->
                val key = p?.name ?: ""
                val open = expanded == key
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Spook.cardBrush)
                    .border(1.dp, if (open) Spook.Pumpkin else Spook.Crypt3, RoundedCornerShape(14.dp))) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { if (p?.name == "claude") showClaudeSetup = true else create(p) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (p == null) Box(Modifier.size(52.dp).clip(CircleShape).background(Spook.Crypt3), contentAlignment = Alignment.Center) { PixelSprite(Sprites.ghost, 30.dp) }
                        else ChatAvatar(p.name, p.displayName, null, p, 52)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p?.displayName ?: "Plain Umbra", color = Spook.Bone, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(p?.tagline?.ifBlank { null } ?: if (p == null) "Your usual Umbra with its default prompt." else "", color = Spook.Mist, fontSize = 12.5.sp, maxLines = 3)
                            if (p != null && p.model.isNotBlank()) Text("🧠 ${p.model}" + (if (p.needsProactive) "  ·  ⏰ reminders" else ""), color = Spook.Fog, fontSize = 11.sp)
                        }
                        Text(if (open) "▴" else "▾", color = Spook.Pumpkin, fontSize = 22.sp,
                            modifier = Modifier.clip(CircleShape).clickable { expanded = if (open) null else key }.padding(10.dp))
                    }
                    if (open) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        p?.traits?.takeIf { it.isNotEmpty() }?.let { tr -> Text(tr.joinToString("\n") { "• $it" }, color = Spook.Mist, fontSize = 12.sp) }
                        Field(title, "Chat name (optional — Umbra names it otherwise)") { title = it }
                        Field(first, "First message (optional)", singleLine = false) { first = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Model", color = Spook.Mist, fontSize = 13.sp, modifier = Modifier.width(80.dp))
                            Pill(model?.let { "${it.provider} · ${it.label}" } ?: (p?.model?.ifBlank { null } ?: "default"), model != null) { picking = true }
                            if (model != null) Text("  ✕", color = Spook.Mist, modifier = Modifier.clickable { model = null })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Thinking", color = Spook.Mist, fontSize = 13.sp, modifier = Modifier.width(80.dp))
                            Choice(listOf("" to "default", "minimal" to "minimal", "low" to "low", "medium" to "medium", "high" to "high"), reasoning) { reasoning = it }
                        }
                        Text("Tools", color = Spook.Mist, fontSize = 13.sp)
                        val base = chosenTools ?: p?.tools?.toSet() ?: emptySet()
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            tools.forEach { t ->
                                Pill(t.name, t.name in base, small = true) { chosenTools = if (t.name in base) base - t.name else base + t.name }
                            }
                        }
                        Field(extra, "Extra instructions for this chat (added to the persona)", singleLine = false, minLines = 2) { extra = it }
                        PrimaryButton(if (busy) "Creating…" else "Create chat", enabled = !busy) { create(p) }
                    }
                }
            }
        }
    }
}
