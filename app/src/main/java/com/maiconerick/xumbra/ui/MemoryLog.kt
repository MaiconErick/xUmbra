package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.data.Net
import com.maiconerick.xumbra.data.objects
import com.maiconerick.xumbra.ui.theme.Spook
import org.json.JSONObject

/**
 * Everything written to the memory files during this chat — by Umbra itself or by the
 * note-taker after a reply — newest first. Tap one to open that file.
 */
@Composable
fun MemoryLogPage(sid: String, onClose: () -> Unit) {
    var changes by remember { mutableStateOf<List<JSONObject>?>(null) }
    var edit by remember { mutableStateOf<String?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    LaunchedEffect(rev) {
        changes = try { Net.get("/api/v2/sessions/$sid/memory-log").optJSONArray("changes").objects().reversed() } catch (_: Exception) { emptyList() }
    }
    edit?.let { FileEditorPage(it) { edit = null; rev++ } }
    Page("Saved to memory", onClose, actions = {
        Text("↻", color = Spook.Mist, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { rev++ }.padding(10.dp))
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("What was written to your memory files in this chat, newest first. “Note-taker” = saved in the background after a reply; " +
                    "“Umbra” = it wrote it itself (usually because you asked). Tap one to open the file.",
                    color = Spook.Mist, fontSize = 12.sp, lineHeight = 16.sp)
            }
            val list = changes
            when {
                list == null -> item { Text("Loading…", color = Spook.Mist) }
                list.isEmpty() -> item { Text("Nothing saved from this chat yet.", color = Spook.Mist) }
                else -> items(list) { c -> ChangeRow(c) { edit = "memory/files/" + c.optString("page") + ".md" } }
            }
        }
    }
}

@Composable
private fun ChangeRow(c: JSONObject, open: () -> Unit) {
    val op = c.optString("op")
    val (icon, color) = when (op) {
        "update" -> "✎ changed" to Color(0xFFFFD54A)
        "remove" -> "✕ removed" to Color(0xFFFF4D5E)
        else -> "+ added" to Color(0xFF5BD44A)
    }
    val who = when (c.optString("who").ifBlank { c.optString("source") }) {
        "note-taker", "notes" -> "Note-taker"
        "umbra" -> "Umbra"
        else -> c.optString("who").ifBlank { c.optString("source") }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Spook.Crypt2.copy(alpha = 0.75f)).clickable(onClick = open).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.optString("ts").substringAfter('T'), color = Spook.Mist, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(icon, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("by $who", color = Spook.Mist, fontSize = 11.sp, modifier = Modifier.weight(1f))
        }
        Text(c.optString("page") + (c.optString("section").takeIf { it.isNotBlank() && it != "GENERAL" }?.let { "  [$it]" } ?: ""),
            color = Spook.Pumpkin, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        if (op == "update") Text("was: " + c.optString("old"), color = Spook.Mist, fontSize = 12.sp)
        Text(c.optString("text").ifBlank { c.optString("old") }, color = Spook.Bone, fontSize = 13.sp, lineHeight = 17.sp)
    }
}
