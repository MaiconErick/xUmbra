package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.data.Net
import com.maiconerick.xumbra.data.SessionRow
import com.maiconerick.xumbra.data.Umbra
import com.maiconerick.xumbra.data.objects
import com.maiconerick.xumbra.ui.theme.Spook
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The vault: chats of secret personas (FBI). They never show in the chat list or in
 * notifications; this screen opens with a long press on the ghost / title on the home screen.
 */
@Composable
fun VaultScreen() {
    var rows by remember { mutableStateOf<List<SessionRow>?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    var askDelete by remember { mutableStateOf<SessionRow?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(rev) {
        rows = try { Net.get("/api/v2/sessions?vault=true").optJSONArray("sessions").objects().map(SessionRow::of) }
               catch (_: Exception) { emptyList() }
    }
    askDelete?.let { r ->
        Confirm("Burn this file?", "The chat is erased from the Pi for good. What FBI has filed stays in its files.", "Burn", danger = true,
            onYes = { Umbra.delete(r.id, wipe = true); askDelete = null; rev++ }, onNo = { askDelete = null })
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF050507)).statusBarsPadding().navigationBarsPadding()) {
        ScreenHeader("Vault", { Nav.back() })
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Secret chats. Hidden from the list and from notifications; their messages vanish as you read them.",
                color = Color(0xFF8A8A96), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("🕵  New FBI chat", color = Color.Black, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFB0001E))
                    .clickable { scope.launch { Umbra.create(JSONObject().put("persona", "fbi"))?.let { Nav.go(Screen.Chat(it)) } } }
                    .padding(horizontal = 14.dp, vertical = 10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    rows == null -> item { Text("…", color = Color(0xFF8A8A96)) }
                    rows!!.isEmpty() -> item { Text("Nothing in the vault.", color = Color(0xFF8A8A96), fontFamily = FontFamily.Monospace) }
                    else -> items(rows!!, key = { it.id }) { r ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF2A0008), RoundedCornerShape(8.dp))
                            .background(Color(0xFF0D0D12))
                            .pointerInput(r.id) { detectTapGestures(onTap = { Nav.go(Screen.Chat(r.id)) }, onLongPress = { askDelete = r }) }
                            .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📁", fontSize = 18.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("FILE ${r.id}", color = Color(0xFFE8E4F0), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("${r.count} entries · ${Fmt.listTime(r.ts)}", color = Color(0xFF8A8A96), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Long press opens the vault; a normal tap does nothing special. */
fun Modifier.vaultDoor(): Modifier = pointerInput(Unit) { detectTapGestures(onLongPress = { Nav.go(Screen.Vault) }) }
