package com.maiconerick.xumbra.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maiconerick.xumbra.data.*
import com.maiconerick.xumbra.ui.theme.Spook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun PersonasScreen() {
    val personas by Umbra.personas.collectAsState()
    val rows by Umbra.sessions.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var showClaudeSetup by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Umbra.loadPersonas() }
    if (creating) NewPersonaDialog(personas) { creating = false }
    if (showClaudeSetup) ClaudeSetupSheet { showClaudeSetup = false }
    Column(Modifier.fillMaxSize().background(Spook.skyBrush)) {
        ScreenHeader("Personas", { Nav.back() }) {
            IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "New persona", tint = Spook.Pumpkin) }
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(personas, key = { it.name }) { p ->
                val chats = rows.count { it.persona == p.name }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Spook.cardBrush).clickable { if (p.name == "claude") showClaudeSetup = true else Nav.go(Screen.PersonaEditor(p.name)) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ChatAvatar(p.name, p.displayName, null, p, 56)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName, color = Spook.Bone, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(p.tagline, color = Spook.Mist, fontSize = 12.5.sp, maxLines = 2)
                        Text(listOfNotNull(p.model.ifBlank { null }, "$chats chats", "${p.tokens} tokens", if (p.needsProactive) "⏰" else null).joinToString(" · "),
                            color = Spook.Fog, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewPersonaDialog(existing: List<Persona>, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("") }
    var copy by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose, containerColor = Spook.Crypt2,
        title = { Text("New persona", color = Spook.Bone) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(display, "Display name") { display = it; if (name.isBlank() || name == slug(display.dropLast(1))) name = slug(it) }
                Field(name, "Folder name (a-z, 0-9, -)") { name = slug(it) }
                Text("Start from", color = Spook.Mist, fontSize = 12.sp)
                Choice(listOf("" to "blank") + existing.map { it.name to it.displayName }, copy) { copy = it }
                err?.let { Text(it, color = Spook.Blood, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    try {
                        val o = Net.post("/api/v2/personas", JSONObject().put("name", name).put("display_name", display).apply { if (copy.isNotEmpty()) put("copy_from", copy) })
                        Umbra.loadPersonas(); onClose(); Nav.go(Screen.PersonaEditor(o.optString("name", name)))
                    } catch (e: Exception) { err = e.message }
                }
            }, enabled = name.isNotBlank()) { Text("Create", color = Spook.Pumpkin) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Spook.Mist) } })
}

private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')

/** README.md = how the persona is shown + its settings lines; PERSONA.md = the prompt. */
@Composable
fun PersonaEditorScreen(name: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val personas by Umbra.personas.collectAsState()
    val p = personas.firstOrNull { it.name == name }
    var readme by remember { mutableStateOf<String?>(null) }
    var persona by remember { mutableStateOf<String?>(null) }
    var readme0 by remember { mutableStateOf("") }
    var persona0 by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var pickModel by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var showClaudeSetup by remember { mutableStateOf(false) }
    var bust by remember { mutableIntStateOf(0) }
    LaunchedEffect(name) {
        try {
            readme = Net.text("/api/personas/$name/readme.md"); readme0 = readme!!
            persona = Net.text("/api/personas/$name/persona.md"); persona0 = persona!!
        } catch (e: Exception) { msg = "Couldn't load: ${e.message}" }
    }
    fun upload(kind: String, uri: Uri) = scope.launch {
        try {
            val bytes = withContext(Dispatchers.IO) { Images.shrink(ctx, uri, 1600, 90) ?: ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            Net.upload("/api/personas/$name/$kind", "$kind.jpg", "image/jpeg", bytes)
            Umbra.loadPersonas(); bust++
            msg = if (kind == "icon") "🖼 Icon updated" else "🖼 Background updated"
        } catch (e: Exception) { msg = "Upload failed: ${e.message}" }
    }
    val iconPick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> upload("icon", u) } }
    val bgPick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> upload("background", u) } }
    fun save() = scope.launch {
        try {
            if (readme != readme0) { Net.put("/api/personas/$name/readme.md", JSONObject().put("content", readme)); readme0 = readme!! }
            if (persona != persona0) { Net.put("/api/personas/$name/persona.md", JSONObject().put("content", persona)); persona0 = persona!! }
            Umbra.loadPersonas(); msg = "💾 Saved"
        } catch (e: Exception) { msg = "Couldn't save: ${e.message}" }
    }
    if (pickModel) ModelPickerPage(null, "Persona model", onPick = { m -> readme = setLine(readme ?: "", "model", "${m.provider}/${m.model}"); pickModel = false }) { pickModel = false }
    if (askDelete) Confirm("Remove ${p?.displayName ?: name}?", "The folder moves to backups/personas on the Pi. Chats using it fall back to plain Umbra.", "Remove", danger = true,
        onYes = { scope.launch { try { Net.delete("/api/v2/personas/$name"); Umbra.loadPersonas(); Nav.back() } catch (e: Exception) { msg = e.message } }; askDelete = false },
        onNo = { askDelete = false })

    val dirty = readme != readme0 || persona != persona0
    Column(Modifier.fillMaxSize().background(Spook.skyBrush).imePadding()) {
        ScreenHeader(p?.displayName ?: name, { Nav.back() }) {
            if (dirty) TextButton(onClick = { save() }) { Text("Save", color = Spook.Pumpkin, fontWeight = FontWeight.Bold) }
        }
        Banner(msg) { msg = null }
        Tabs(listOf("🎭 Look & settings", "📄 README" + if (readme != readme0) " •" else "", "🧠 PERSONA.md" + if (persona != persona0) " •" else ""), tab) { tab = it }
        when (tab) {
            0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Section("Pictures") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(84.dp).clip(RoundedCornerShape(42.dp)).background(Spook.Crypt3).clickable {
                            iconPick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, contentAlignment = Alignment.Center) {
                            p?.iconUrl?.let { AsyncImage("$it&b=$bust", null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } ?: Text("icon", color = Spook.Mist)
                        }

                    }
                    Text("Tap to replace.", color = Spook.Fog, fontSize = 11.sp)
                }
                val r = readme ?: ""
                Section("Settings", "Stored as lines in README.md.") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Model", color = Spook.Mist, modifier = Modifier.width(90.dp))
                        Pill(getLine(r, "model") ?: "global default", getLine(r, "model") != null) { pickModel = true }
                        if (getLine(r, "model") != null) Text("  ✕", color = Spook.Mist, modifier = Modifier.clickable { readme = setLine(r, "model", null) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Thinking", color = Spook.Mist, modifier = Modifier.width(90.dp))
                        Choice(listOf("" to "default", "minimal" to "minimal", "low" to "low", "medium" to "medium", "high" to "high"), getLine(r, "reasoning_effort") ?: "") {
                            readme = setLine(r, "reasoning_effort", it.ifEmpty { null })
                        }
                    }
                    Toggle("Can send reminders", getLine(r, "needs_proactive") == "true", "New chats with this persona allow Umbra to message first") {
                        readme = setLine(r, "needs_proactive", if (it) "true" else null)
                    }
                    var toolsLine by remember(r) { mutableStateOf(getLine(r, "enable_tools") ?: "") }
                    Field(toolsLine, "Tools it needs (comma separated)") { toolsLine = it; readme = setLine(r, "enable_tools", it.ifBlank { null }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("💬 Chat with ${p?.displayName ?: name}") {
                        if (name == "claude") {
                            showClaudeSetup = true
                        } else {
                            scope.launch { Umbra.create(JSONObject().put("persona", name))?.let { Nav.openChat(it) } }
                        }
                    }
                    GhostButton("Remove", Spook.Blood) { askDelete = true }
                }
            }
            1 -> Editor(readme, "# Name\n\n*tagline*\n\nmodel: openai/gpt-5-mini") { readme = it }
            2 -> Editor(persona, "You are …") { persona = it }
        }
    }
    if (showClaudeSetup) ClaudeSetupSheet { showClaudeSetup = false }
}

@Composable
private fun Editor(text: String?, hint: String, set: (String) -> Unit) {
    if (text == null) { Text("Loading…", color = Spook.Mist, modifier = Modifier.padding(16.dp)); return }
    BasicTextField(text, set, textStyle = TextStyle(color = Spook.Bone, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
        cursorBrush = SolidColor(Spook.Pumpkin),
        decorationBox = { inner -> Box { if (text.isEmpty()) Text(hint, color = Spook.Fog, fontFamily = FontFamily.Monospace, fontSize = 13.sp); inner() } },
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).navigationBarsPadding())
}

/** README one-liners like `model: openai/gpt-5-mini`. */
fun getLine(text: String, key: String): String? =
    Regex("(?im)^\\s*${key.replace("_", "[_-]?")}\\s*:\\s*(.+?)\\s*$").find(text)?.groupValues?.get(1)

fun setLine(text: String, key: String, value: String?): String {
    val re = Regex("(?im)^\\s*${key.replace("_", "[_-]?")}\\s*:.*$\\n?")
    val without = re.replace(text, "")
    if (value == null) return without
    val line = "$key: $value\n"
    // put settings right after the first blank line following the heading block
    val idx = Regex("\\n\\s*\\n").find(without)?.range?.last?.plus(1) ?: without.length
    return without.substring(0, idx) + line + "\n" + without.substring(idx).trimStart('\n')
}
