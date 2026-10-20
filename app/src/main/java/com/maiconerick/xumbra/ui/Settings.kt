package com.maiconerick.xumbra.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.App
import com.maiconerick.xumbra.BuildConfig
import com.maiconerick.xumbra.data.*
import com.maiconerick.xumbra.service.HubService
import com.maiconerick.xumbra.ui.theme.Spook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

@Composable
fun SettingsScreen() {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Spook.skyBrush)) {
        ScreenHeader("Settings", { Nav.back() })
        Tabs(listOf("🎃 Themes", "🧠 How Umbra thinks", "🔌 Connection", "💬 Chats", "🔔 Notifications", "ℹ About"), tab) { tab = it }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                0 -> ThemesTab()
                1 -> BrainTab()
                2 -> ConnectionTab()
                3 -> ChatsTab()
                4 -> NotificationsTab()
                5 -> AboutTab()
            }
        }
    }
}

@Composable
private fun ConnectionTab() {
    val p = App.prefs
    val route by Net.route.collectAsState()
    val ping by Hub.pingMs.collectAsState()
    val state by Hub.state.collectAsState()
    val err by Hub.error.collectAsState()
    var lan by remember { mutableStateOf(p.lanHost) }
    var ts by remember { mutableStateOf(p.tailscaleHost) }
    var custom by remember { mutableStateOf(p.customHost) }
    var token by remember { mutableStateOf(p.token) }
    val tests = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    fun test(host: String) {
        if (host.isBlank()) return
        tests[host] = "testing…"
        scope.launch {
            val r = Route("", "", host)
            val t0 = System.currentTimeMillis()
            tests[host] = try {
                withContext(Dispatchers.IO) {
                    Net.client.newCall(Request.Builder().url(r.base + "/api/health").build()).execute().use { resp ->
                        if (resp.isSuccessful) "✓ ${System.currentTimeMillis() - t0} ms" + (if (JSONObject(resp.body!!.string()).optBoolean("auth_required")) " · needs token" else "")
                        else "✗ HTTP ${resp.code}"
                    }
                }
            } catch (e: Exception) { "✗ " + (e.message ?: "unreachable").take(60) }
        }
    }
    Section("Now", "Umbra is reached through whichever address answers first.") {
        Text(when {
            state == Conn.Online && route != null -> "🟢 ${route!!.label} — ${route!!.host} · ${if (ping >= 0) "$ping ms" else "…"}"
            state == Conn.Connecting -> "🟡 Connecting…"
            else -> "🔴 Offline" + if (err.isNotBlank()) " — $err" else ""
        }, color = Spook.Bone)
    }
    Section("Addresses", "host:port of the Umbra API on the Pi") {
        @Composable fun row(label: String, v: String, set: (String) -> Unit) {
            Field(v, label) { set(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { test(v) }) { Text("Test", color = Spook.Pumpkin) }
                Text(tests[v] ?: "", color = if (tests[v]?.startsWith("✓") == true) Spook.Slime else Spook.Mist, fontSize = 12.sp)
            }
        }
        row("🏠 Home Wi-Fi", lan) { lan = it }
        row("🔒 Tailscale", ts) { ts = it }
        row("🌐 Custom (optional)", custom) { custom = it }
        Field(token, "API token (empty if the server has none)") { token = it }
        PrimaryButton("Save & reconnect") {
            p.lanHost = lan; p.tailscaleHost = ts; p.customHost = custom; p.token = token
            Net.route.value = null
            Hub.restart()
        }
    }
}

@Composable
private fun ChatsTab() {
    val p = App.prefs
    var rev by remember { mutableIntStateOf(0) }
    fun set(block: () -> Unit) { block(); rev++ }
    rev.let { }
    Section("Sending") {
        Toggle("Remember new messages", p.keepDefault, "Off = 👻 ghost mode by default: Umbra answers but doesn't save it to memory") { set { p.keepDefault = it } }
        Toggle("Enter key sends", p.enterSends, "Otherwise Enter adds a new line") { set { p.enterSends = it } }
        Text("Voice-to-text language", color = Spook.Mist, fontSize = 12.sp)
        Choice(listOf("" to "auto", "pt" to "Português", "en" to "English", "ja" to "日本語"), p.transcribeLang) { v -> set { p.transcribeLang = v } }
    }
    var app by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { try { app = Net.get("/api/v2/app-settings") } catch (_: Exception) { } }
    fun setApp(k: String, v: Boolean) = scope.launch { try { app = Net.patch("/api/v2/app-settings", JSONObject().put(k, v)) } catch (_: Exception) { } }
    Section("On the Pi (all chats)") {
        Toggle("Show search answers directly", app?.optBoolean("search_direct") == true,
            "Faster (no second model call), but the reply is the search engine's text instead of Umbra's. Chats can override it.") { setApp("search_direct", it) }
        Text("Reddit in web searches", color = Spook.Bone, fontSize = 15.sp)
        Text("Umbra decides = only for recommendations, opinions and \"is it worth it\" questions. Chats can override it.", color = Spook.Mist, fontSize = 12.sp)
        Choice(listOf("auto" to "Umbra decides", "always" to "Always", "never" to "Never"), app?.optString("reddit_mode", "auto") ?: "auto") { v ->
            scope.launch { try { app = Net.patch("/api/v2/app-settings", JSONObject().put("reddit_mode", v)) } catch (_: Exception) { } }
        }
        Toggle("Smart memory routing", app?.optBoolean("smart_routing") == true,
            "Also ask a small model which memory files to load on every message (~1 s slower). Off: Umbra opens files itself when needed.") { setApp("smart_routing", it) }
    }
    Section("Replies") {
        Toggle("Action icons", p.showToolChips, "Small icons under each reply for what Umbra did (search, files, commands, memory) — tap the reply for details") { set { p.showToolChips = it } }
        Toggle("Live shell output", p.showLiveShell, "Last lines of a running command while Umbra works") { set { p.showLiveShell = it } }
        Toggle("Reply footer", p.showFooters, "Time · model · cost · duration — tap a reply to see everything that happened") { set { p.showFooters = it } }
        Toggle("Show cost", p.showCost) { set { p.showCost = it } }
        DramaSlider("Text size", p.textScale, 0.8f, 1.5f, "×") { v -> set { p.textScale = v } }
        DramaSlider("Vanishing chats: each word lasts", p.vanishSeconds, 1f, 8f, " s") { v -> set { p.vanishSeconds = v } }
    }
    JapaneseSettings()
    Section("Look") {
        Toggle("Show hidden chats", p.showHidden) { set { p.showHidden = it }; Umbra.refreshSessions() }
    }
}

@Composable
private fun NotificationsTab() {
    val p = App.prefs
    val ctx = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    fun set(block: () -> Unit) { block(); rev++ }
    rev.let { }
    Section("Background connection", "Keeps one quiet connection to the Pi so replies and reminders arrive instantly, even with the app closed.") {
        Toggle("Stay connected in the background", p.background) { set { p.background = it }; HubService.sync(ctx); if (it) Hub.start() }
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (p.background && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            Text("Samsung may still put xUmbra to sleep. Allow it to run unrestricted:", color = Spook.Mist, fontSize = 12.sp)
            GhostButton("Battery: unrestricted") {
                try { ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) { }
            }
        }
    }
    Section("Notify me about") {
        Toggle("Replies", p.notifyReplies, "When Umbra finishes answering and you're not looking at that chat") { set { p.notifyReplies = it } }
        Toggle("Reminders", p.notifyProactive, "Messages Umbra scheduled for itself (\"remind me tomorrow…\")") { set { p.notifyProactive = it } }
        Toggle("Agent answers", p.notifyCron, "Every reply written by a scheduled agent, even without a reminder") { set { p.notifyCron = it } }
        GhostButton("Android notification settings") {
            try { ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)) } catch (_: Exception) { }
        }
    }
}

@Composable
private fun AboutTab() {
    var info by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) { try { info = Net.get("/api/v2/info") } catch (_: Exception) { } }
    Section("xUmbra ${BuildConfig.VERSION_NAME}", "Android client for Umbra on the Rock Pi.") {
        Text("Server API: " + (info?.let { "v" + it.optString("version") + " · ${it.optJSONObject("busy")?.length() ?: 0} chats answering" } ?: "unreachable"), color = Spook.Bone, fontSize = 13.sp)
        Text("Replies keep going on the Pi when the phone sleeps or loses Wi-Fi; the app catches up when it reconnects.", color = Spook.Mist, fontSize = 12.sp)
    }
}
