@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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

private data class MemFile(val path: String, val size: Long, val modified: String, val category: String)

/** One page of the memory notebook (memory/files/<id>.md). */
private data class MSection(val name: String, val aliases: List<String>, val lines: Int)
private data class MPage(val id: String, val title: String, val words: List<String>, val bytes: Long,
                         val always: Boolean, val sections: List<MSection>) {
    companion object {
        fun of(o: JSONObject) = MPage(
            o.optString("id"), o.optString("title"), o.optJSONArray("words").strings(),
            o.optLong("bytes"), o.optBoolean("always"),
            o.optJSONArray("sections").objects().map {
                MSection(it.optString("name"), it.optJSONArray("aliases").strings(), it.optInt("lines"))
            },
        )
    }
}

@Composable
fun MemoryScreen() {
    var tab by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<MemFile>>(emptyList()) }
    var pages by remember { mutableStateOf<List<MPage>>(emptyList()) }
    var suggested by remember { mutableStateOf<List<String>>(emptyList()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(rev) {
        try {
            files = Net.get("/api/memory/files").optJSONArray("files").objects().map {
                MemFile(it.optString("path"), it.optLong("size"), it.optString("modified"), it.optString("category"))
            }
        } catch (_: Exception) { }
        try {
            val o = Net.get("/api/v2/memory/pages")
            pages = o.optJSONArray("pages").objects().map(MPage::of)
            suggested = o.optJSONArray("suggested").strings()
        } catch (e: Exception) { err = "Couldn't load memory pages: ${e.message}" }
    }
    editing?.let { FileEditorPage(it) { editing = null; rev++ } }
    detail?.let { pid ->
        val pg = pages.firstOrNull { it.id == pid }
        if (pg != null) MemoryPageDetail(pg, { detail = null; rev++ }, open = { editing = it })
    }
    Column(Modifier.fillMaxSize().background(Spook.skyBrush)) {
        ScreenHeader("Memory", { Nav.back() }) { IconButton(onClick = { rev++ }) { Icon(Icons.Default.Refresh, null, tint = Spook.Mist) } }
        Tabs(listOf("🧠 Memory", "🧭 Core", "🗃 Files"), tab) { tab = it }
        Box(Modifier.fillMaxSize().navigationBarsPadding()) {
            when (tab) {
                0 -> MemoryTab(pages, suggested, err, allFiles = { tab = 2 }, openPage = { detail = it }, open = { editing = it })
                1 -> CoreTab(rev, open = { editing = it }) { rev++ }
                2 -> FileList(files, "Every file Umbra keeps: memory, chat logs, summaries, prompt snapshots. Edits are backed up on the Pi.", grouped = true) { editing = it }
            }
        }
    }
}

/** How the notebook works + every page, with the words that route a message to it. */
@Composable
private fun MemoryTab(pages: List<MPage>, suggested: List<String>, err: String?, allFiles: () -> Unit,
                      openPage: (String) -> Unit, open: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = pages.filter { q.isBlank() || it.id.contains(q, true) || it.title.contains(q, true) || it.words.any { w -> w.contains(q, true) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Section("How this works", "Your memory is a set of markdown pages, one per subject.") {
                HowLine("📝", "A note-taker reads every exchange after the reply and writes what matters into the right page and section — you never have to save anything by hand.")
                HowLine("🧩", "Each page has sections, one per thing: a game, a show, a device, an app. Tastes live in GENERAL.")
                HowLine("🔑", "The words under a page (the keywords) decide when it is loaded: mention one and this page comes into the prompt.")
                HowLine("🍰", "Big pages split into sub-pages (games/jrpg, media/music…) so nothing gets huge.")
                HowLine("⚠️", "If a message names something that is not on the page, Umbra has to open it with memory(read) — keywords are what make that unnecessary.")
                Gap(4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("🗃 All files") { allFiles() }
                    GhostButton("🕘 Recent changes") { open("memory/files/.history.jsonl") }
                }
            }
            Gap(8)
            Field(q, "Filter pages or keywords") { q = it }
            Gap(4)
            Text("Pages (${pages.size})", color = Spook.Pumpkin, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            err?.let { Banner(it) {} }
        }
        items(shown, key = { it.id }) { p -> PageRow(p) { openPage(p.id) } }
        if (suggested.isNotEmpty()) {
            item {
                Gap(8)
                Text("Not created yet", color = Spook.Mist, fontSize = 12.sp)
                Text(suggested.joinToString("  ") { it }, color = Spook.Fog, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun HowLine(icon: String, text: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(icon, fontSize = 12.sp, modifier = Modifier.width(20.dp))
        Text(text, color = Spook.Bone, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PageRow(p: MPage, open: () -> Unit) {
    val empty = p.sections.isEmpty()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Spook.Crypt2.copy(alpha = 0.8f))
        .clickable { open() }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.title, color = Spook.Bone, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (p.always) Text("always", color = Spook.Witch, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
            Text(Fmt.bytes(p.bytes), color = Spook.Mist, fontSize = 10.5.sp)
        }
        Text(
            if (empty) "no sections yet — talk about it and the note-taker fills it"
            else "${p.sections.size} sections · " + p.sections.take(4).joinToString(", ") { it.name.lowercase() } +
                    (if (p.sections.size > 4) " …" else ""),
            color = if (empty) Spook.Mist else Spook.Bone.copy(alpha = 0.8f), fontSize = 11.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (p.always) Text("always loaded into the prompt — keywords don't matter here", color = Spook.Witch, fontSize = 11.sp)
        else KeywordChips(p.words, Spook.Moon, max = 5)
    }
}

/** The routing words of a page: small pills, tap-free in lists (edited in the page itself). */
@Composable
private fun KeywordChips(words: List<String>, tint: Color, max: Int = 99, onRemove: ((String) -> Unit)? = null) {
    if (words.isEmpty()) {
        Text("no keywords — this page is never loaded automatically", color = Spook.Blood, fontSize = 11.sp)
        return
    }
    FlowRowCompat {
        words.take(max).forEach { w ->
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).background(tint.copy(alpha = 0.14f))
                    .then(if (onRemove != null) Modifier.clickable { onRemove(w) } else Modifier)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(w, color = tint, fontSize = 10.5.sp)
                if (onRemove != null) {
                    Spacer(Modifier.width(3.dp))
                    Icon(Icons.Filled.Close, null, tint = tint.copy(alpha = 0.8f), modifier = Modifier.size(10.dp))
                }
            }
        }
        if (words.size > max) {
            Text("+${words.size - max}", color = Spook.Mist, fontSize = 10.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** One page: its keywords (editable), its sections, and the file behind it. */
@Composable
private fun MemoryPageDetail(p: MPage, onClose: () -> Unit, open: (String) -> Unit) {
    var words by remember(p.id) { mutableStateOf(p.words) }
    var add by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dirty = words != p.words
    Page(p.title, onClose, actions = {
        if (dirty) TextButton(enabled = !saving, onClick = {
            scope.launch {
                saving = true
                try {
                    Net.put("/api/v2/memory/pages/${p.id.split('/').joinToString("/") { Net.enc(it) }}/words",
                        JSONObject().put("words", JSONArray(words)))
                    msg = "💾 Keywords saved"
                } catch (e: Exception) { msg = "Couldn't save: ${e.message}" } finally { saving = false }
            }
        }) { Text(if (saving) "Saving…" else "Save", color = Spook.Pumpkin, fontWeight = FontWeight.Bold) }
    }) {
        Banner(msg) { msg = null }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("memory/files/${p.id}.md · ${Fmt.bytes(p.bytes)}" +
                    (if (p.always) " · always loaded" else ""), color = Spook.Fog, fontSize = 11.sp)

            Section("Keywords", "A message containing one of these loads this page into the prompt. Phrases are fine (\"light novel\").") {
                if (words.isEmpty()) Text("No keywords: this page never loads on its own.", color = Spook.Blood, fontSize = 12.sp)
                KeywordChips(words, Spook.Moon, onRemove = { w -> words = words - w })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Field(add, "Add a word or phrase") { add = it } }
                    TextButton(enabled = add.isNotBlank(), onClick = {
                        val parts = add.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                        words = (words + parts).distinct()
                        add = ""
                    }) { Text("Add", color = Spook.Pumpkin) }
                }
                GhostButton("↺ Reset to what is saved") { words = p.words }
            }

            Section("Sections (${p.sections.size})", "One per thing. Tap to open the file and edit the notes.") {
                if (p.sections.isEmpty()) Text("Nothing noted here yet.", color = Spook.Mist, fontSize = 12.sp)
                p.sections.forEach { s ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Spook.Crypt3.copy(alpha = 0.5f))
                        .clickable { open("memory/files/${p.id}.md") }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name.lowercase(), color = Spook.Bone, fontSize = 12.5.sp, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.aliases.isNotEmpty()) Text(s.aliases.joinToString(", "), color = Spook.Fog, fontSize = 10.5.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("${s.lines}", color = Spook.Mist, fontSize = 10.5.sp)
                    }
                }
                Gap(4)
                GhostButton("📝 Open the whole file") { open("memory/files/${p.id}.md") }
            }
        }
    }
}

@Composable
private fun CoreTab(rev: Int, open: (String) -> Unit, changed: () -> Unit) {
    var core by remember { mutableStateOf<JSONObject?>(null) }
    var rule by remember { mutableStateOf("") }
    var fact by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(rev) { try { core = Net.get("/api/memory/core") } catch (e: Exception) { msg = e.message } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Banner(msg) { msg = null }
        val c = core
        Section("Who Umbra is", "memory/core/system_prompt.md — loaded in every chat (personas add to it).") {
            Text(c?.optString("system_prompt")?.take(500) ?: "…", color = Spook.Bone, fontSize = 12.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
            GhostButton("✏️ Edit") { open("memory/core/system_prompt.md") }
        }
        Section("Rules", "Things Umbra must always do — \"from now on…\". Always loaded.") {
            Text(c?.optString("rules")?.ifBlank { "(none yet)" } ?: "…", color = Spook.Bone, fontSize = 12.5.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(rule, "New rule") { rule = it } }
                TextButton(onClick = { scope.launch {
                    try { Net.post("/api/rules", JSONObject().put("rule", rule.trim())); rule = ""; changed() } catch (e: Exception) { msg = e.message }
                } }, enabled = rule.isNotBlank()) { Text("Add", color = Spook.Pumpkin) }
            }
            GhostButton("✏️ Edit rules file") { open("memory/core/rules.txt") }
        }
        Section("System facts", "IPs, paths, hardware, accounts — always loaded, so keep it short.") {
            Text(c?.optString("facts")?.ifBlank { "(none yet)" } ?: "…", color = Spook.Bone, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(fact, "New fact") { fact = it } }
                TextButton(onClick = { scope.launch {
                    try { Net.post("/api/memory/facts", JSONObject().put("fact", fact.trim()).put("category", "general")); fact = ""; changed() } catch (e: Exception) { msg = e.message }
                } }, enabled = fact.isNotBlank()) { Text("Add", color = Spook.Pumpkin) }
            }
            GhostButton("✏️ Edit facts file") { open("memory/core/system.txt") }
        }
    }
}

@Composable
private fun FileList(files: List<MemFile>, intro: String, grouped: Boolean = false, open: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = files.filter { q.isBlank() || it.path.contains(q, true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            Text(intro, color = Spook.Mist, fontSize = 12.sp)
            Gap(6)
            Field(q, "Filter") { q = it }
            Gap(6)
            if (files.isEmpty()) Text("Nothing here yet.", color = Spook.Mist)
        }
        val groups = if (grouped) shown.groupBy { it.category } else mapOf("" to shown)
        groups.forEach { (cat, list) ->
            if (cat.isNotEmpty()) item { Text(cat.replace('_', ' '), color = Spook.Pumpkin, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
            items(list, key = { it.path }) { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(Spook.Crypt2.copy(alpha = 0.75f))
                    .clickable { open(f.path) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.path.substringAfterLast('/'), color = Spook.Bone, fontSize = 13.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${Fmt.bytes(f.size)} · ${f.modified.take(10)}", color = Spook.Mist, fontSize = 10.5.sp)
                }
            }
        }
    }
}

/** Any memory/session file on the Pi, editable (the server backs up the old version first). */
@Composable
fun FileEditorPage(path: String, onClose: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    var orig by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path) {
        try { val o = Net.get("/api/memory/files/" + path.split('/').joinToString("/") { Net.enc(it) }); text = o.optString("content"); orig = text!! }
        catch (e: Exception) { msg = "Couldn't open: ${e.message}" }
    }
    val dirty = text != null && text != orig
    Page(path.substringAfterLast('/'), onClose, actions = {
        if (dirty) TextButton(onClick = { scope.launch {
            try { Net.put("/api/memory/files/" + path.split('/').joinToString("/") { Net.enc(it) }, JSONObject().put("content", text)); orig = text!!; msg = "💾 Saved (old version backed up)" }
            catch (e: Exception) { msg = "Couldn't save: ${e.message}" }
        } }) { Text("Save", color = Spook.Pumpkin, fontWeight = FontWeight.Bold) }
    }) {
        Banner(msg) { msg = null }
        Text(path, color = Spook.Fog, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp))
        if (path.endsWith(".env")) Text("⚠ This file holds API keys.", color = Spook.Blood, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp))
        val t = text
        if (t == null) Text("Loading…", color = Spook.Mist, modifier = Modifier.padding(16.dp))
        else BasicTextField(t, { text = it }, textStyle = TextStyle(color = Spook.Bone, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
            cursorBrush = SolidColor(Spook.Pumpkin), modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp))
    }
}
