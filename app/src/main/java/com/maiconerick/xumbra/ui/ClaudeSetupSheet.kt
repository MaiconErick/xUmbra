@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.maiconerick.xumbra.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.data.*
import com.maiconerick.xumbra.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Bottom sheet shown when the user selects the Claude persona.
 *  Lets them pick provider (Subscription / OpenRouter / GhostCLI),
 *  execution host (Pi / PC with live ping), and optional model override. */
@Composable
fun ClaudeSetupSheet(onDismiss: () -> Unit) {
    var host by remember { mutableStateOf("pi") }
    var provider by remember { mutableStateOf("subscription") }
    var model by remember { mutableStateOf("") }
    var ping by remember { mutableStateOf(ClaudePing()) }
    var creating by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Poll ping every second while the sheet is open
    LaunchedEffect(Unit) {
        while (true) {
            try { ping = ClaudePing.of(Net.get("/api/v2/claude/ping")) } catch (_: Exception) {}
            delay(1000)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Spook.Crypt2) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Text("Configure Claude", color = Spook.Bone, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Choose how Claude Code runs this session.", color = Spook.Mist, fontSize = 13.sp)

            // ── Provider selector ──────────────────────────────────────
            SectionLabel("Provider")
            ProviderCard("Claude Subscription", "Uses your OAuth login on the selected host",
                provider == "subscription") { provider = "subscription" }
            ProviderCard("OpenRouter", "Routes through openrouter.ai/api — needs OPENROUTER_API_KEY on the host",
                provider == "openrouter") { provider = "openrouter" }
            ProviderCard("GhostCLI", "Third-party proxy at ghostcli.cloud — same key as your Pi/PC CLI",
                provider == "ghostcli") { provider = "ghostcli" }

            // ── Host selector with live ping ───────────────────────────
            SectionLabel("Run on")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HostCard("🖥 Pi", if (ping.piMs >= 0) "${ping.piMs}ms" else "—",
                    host == "pi", Modifier.weight(1f)) { host = "pi" }
                HostCard("💻 PC", if (ping.pcMs >= 0) "${ping.pcMs}ms" else "offline",
                    host == "pc", Modifier.weight(1f), offline = ping.pcMs < 0) { host = "pc" }
            }

            // ── Optional model override ────────────────────────────────
            SectionLabel("Model (optional)")
            OutlinedTextField(
                value = model, onValueChange = { model = it },
                label = { Text("e.g. claude-sonnet-5-5, leave blank for default") },
                textStyle = TextStyle(color = Spook.Bone, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Spook.Pumpkin, unfocusedBorderColor = Spook.Fog,
                    focusedLabelColor = Spook.Pumpkin, unfocusedLabelColor = Spook.Mist,
                    cursorColor = Spook.Pumpkin),
                singleLine = true, modifier = Modifier.fillMaxWidth())

            err?.let { Text(it, color = Spook.Blood, fontSize = 12.sp) }

            // ── Confirm button ─────────────────────────────────────────
            Spacer(Modifier.height(6.dp))
            Button(onClick = {
                creating = true; err = null
                scope.launch {
                    try {
                        val cfg = ClaudeSessionConfig(host, provider, model.ifBlank { null })
                        val sid = Umbra.create(cfg.toJSONObject())
                        if (sid != null) { onDismiss(); Nav.openChat(sid) }
                        else { err = "Couldn't create session"; creating = false }
                    } catch (e: Exception) { err = e.message; creating = false }
                }
            }, enabled = !creating && (host != "pc" || ping.pcMs >= 0),
                colors = ButtonDefaults.buttonColors(containerColor = Spook.Pumpkin),
                modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (creating) "Creating…" else "Start Claude session",
                    color = Spook.Crypt, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Spook.Slime, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ProviderCard(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) Spook.Pumpkin else Spook.Fog.copy(alpha = 0.3f)
    val bg = if (selected) Spook.Pumpkin.copy(alpha = 0.12f) else Color.Transparent
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg)
        .border(1.5.dp, border, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(12.dp)) {
        Text(title, color = if (selected) Spook.Pumpkin else Spook.Bone,
            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(desc, color = Spook.Mist, fontSize = 11.5.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun HostCard(label: String, pingText: String, selected: Boolean, mod: Modifier = Modifier,
                     offline: Boolean = false, onClick: () -> Unit) {
    val border = when {
        offline -> Spook.Blood.copy(alpha = 0.4f)
        selected -> Spook.Pumpkin
        else -> Spook.Fog.copy(alpha = 0.3f)
    }
    val bg = if (selected && !offline) Spook.Pumpkin.copy(alpha = 0.12f) else Color.Transparent
    Column(mod.clip(RoundedCornerShape(10.dp)).background(bg)
        .border(1.5.dp, border, RoundedCornerShape(10.dp))
        .clickable(enabled = !offline, onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if (offline) Spook.Mist else if (selected) Spook.Pumpkin else Spook.Bone,
            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(pingText, color = if (offline) Spook.Blood else Spook.Slime,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}