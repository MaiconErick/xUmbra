package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.data.Net
import com.maiconerick.xumbra.data.objects
import com.maiconerick.xumbra.ui.theme.Spook
import org.json.JSONObject

/**
 * Settings → How Umbra thinks: everything Umbra reads for a message, in order, when each
 * part is used, and the files behind it — tap one to open and edit it right here.
 */
@Composable
fun BrainTab() {
    var steps by remember { mutableStateOf<List<JSONObject>?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<String?>(null) }
    var newFile by remember { mutableStateOf(false) }
    LaunchedEffect(rev) { steps = try { Net.get("/api/v2/brain").optJSONArray("steps").objects() } catch (_: Exception) { emptyList() } }
    edit?.let { FileEditorPage(it) { edit = null; rev++ } }
    if (newFile) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newFile = false }, containerColor = Spook.Crypt2,
            title = { Text("New instruction file", color = Spook.Bone) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("A new .md in memory/core — Umbra reads it on every message, after the files in ORDER.txt.", color = Spook.Mist, fontSize = 12.sp)
                    Field(name, "Name (e.g. movies-rules)") { name = it }
                }
            },
            confirmButton = { TextButton(onClick = {
                val n = name.trim().lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-')
                if (n.isNotEmpty()) { edit = "memory/core/$n.md"; newFile = false }
            }) { Text("Create", color = Spook.Pumpkin) } },
            dismissButton = { TextButton(onClick = { newFile = false }) { Text("Cancel", color = Spook.Mist) } })
    }

    Text("Everything Umbra reads when you send a message, in this order. The AI only knows what's in these parts. " +
        "Tap a file to open and edit it — changes apply from the next message.", color = Spook.Mist, fontSize = 12.5.sp, lineHeight = 17.sp)
    val list = steps
    if (list == null) { Text("Loading…", color = Spook.Mist); return }
    list.forEachIndexed { i, s -> StepCard(i + 1, s, { edit = it }, onNew = if (i == 0) ({ newFile = true }) else null) }
}

@Composable
private fun StepCard(n: Int, s: JSONObject, open: (String) -> Unit, onNew: (() -> Unit)?) {
    val whenText = s.optString("when")
    val color = when {
        whenText.startsWith("Every message") -> Color(0xFF5BD44A)
        s.optString("title").startsWith("After") -> Spook.Moon
        else -> Color(0xFFFFD54A)
    }
    val files = s.optJSONArray("files").objects()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Spook.Crypt2.copy(alpha = 0.75f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Spook.Pumpkin), contentAlignment = Alignment.Center) {
                Text("$n", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(s.optString("title"), color = Spook.Bone, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Text(whenText, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        Text(s.optString("why"), color = Spook.Mist, fontSize = 12.sp, fontStyle = FontStyle.Italic, lineHeight = 16.sp)
        files.forEach { f ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Spook.Crypt3.copy(alpha = 0.6f))
                .clickable { open(f.optString("path")) }.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("📄", fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.optString("label"), color = Spook.Bone, fontSize = 13.sp)
                    Text(f.optString("path"), color = Spook.Mist, fontSize = 10.5.sp)
                }
                Text(if (f.optInt("bytes") == 0) "empty" else "${f.optInt("tokens")} tok", color = Spook.Slime, fontSize = 11.sp)
            }
        }
        onNew?.let { Text("+ New instruction file", color = Spook.Pumpkin, fontSize = 12.5.sp, modifier = Modifier.clickable(onClick = it).padding(vertical = 4.dp)) }
    }
}
