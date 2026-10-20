package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.App
import com.maiconerick.xumbra.ui.theme.Spook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.OutputStream
import java.security.Security

/** SSH shell to the Pi with a small VT100 emulator; lives in the process so leaving the screen keeps it open. */
object Ssh {
    const val COLS = 100
    const val ROWS = 40
    val emu = AnsiEmulator(cols = COLS, rows = ROWS)
    var lines by mutableStateOf<List<AnnotatedString>>(emptyList())
    var connected by mutableStateOf(false)
    var connecting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var ssh: SSHClient? = null
    private var sess: Session? = null
    private var out: OutputStream? = null
    private var reader: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bcReady = false

    private fun bc() {
        if (bcReady) return
        // Android's stripped "BC" lacks X25519/Ed25519 that modern sshd uses: swap in the full one.
        try { Security.removeProvider("BC") } catch (_: Throwable) { }
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        bcReady = true
    }

    fun connect() {
        if (connecting || connected) return
        val p = App.prefs
        connecting = true; error = null
        scope.launch {
            try {
                bc()
                val c = SSHClient()
                c.addHostKeyVerifier(PromiscuousVerifier())
                c.connectTimeout = 6000
                c.connect(p.sshHost, p.sshPort)
                if (p.sshKey.isNotBlank()) c.authPublickey(p.sshUser, c.loadKeys(p.sshKey, null, null))
                else c.authPassword(p.sshUser, p.sshPassword)
                val s = c.startSession()
                s.allocatePTY("xterm-256color", COLS, ROWS, 0, 0, emptyMap())
                val sh = s.startShell()
                ssh = c; sess = s; out = sh.outputStream
                connected = true
                reader = scope.launch {
                    val buf = ByteArray(8192)
                    try {
                        val inp = sh.inputStream
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            emu.feed(buf, n)
                            val r = emu.render()
                            withContext(Dispatchers.Main) { lines = r }
                        }
                    } catch (_: Exception) { }
                    withContext(Dispatchers.Main) { connected = false }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally { connecting = false }
        }
    }

    fun send(s: String) = scope.launch { try { out?.write(s.toByteArray()); out?.flush() } catch (e: Exception) { error = e.message } }

    fun close() {
        reader?.cancel()
        try { sess?.close() } catch (_: Exception) { }
        try { ssh?.disconnect() } catch (_: Exception) { }
        ssh = null; sess = null; out = null; connected = false
    }
}

@Composable
fun TerminalScreen() {
    var setup by remember { mutableStateOf(App.prefs.sshPassword.isBlank() && App.prefs.sshKey.isBlank()) }
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val lines = Ssh.lines
    LaunchedEffect(Unit) { if (!setup) Ssh.connect() }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    if (setup) SshSetupPage { setup = false; Ssh.close(); Ssh.connect() }

    Column(Modifier.fillMaxSize().background(Color(0xFF07040C)).imePadding()) {
        ScreenHeader("Terminal", { Nav.back() }) {
            Text(if (Ssh.connected) "● ${App.prefs.sshUser}@${App.prefs.sshHost}" else if (Ssh.connecting) "connecting…" else "offline",
                color = if (Ssh.connected) Spook.Slime else Spook.Mist, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
            TextButton(onClick = { setup = true }) { Text("⚙", color = Spook.Mist, fontSize = 18.sp) }
        }
        Ssh.error?.let { Banner("SSH: $it — tap to retry") { Ssh.error = null; Ssh.close(); Ssh.connect() } }
        if (!Ssh.connected && !Ssh.connecting) Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Connect") { Ssh.connect() }
        }
        Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width((Ssh.COLS * 6.7).dp).fillMaxHeight().padding(horizontal = 6.dp), state = list) {
                itemsIndexed(lines) { _, l ->
                    Text(l, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp, color = Color(0xFFE0E0E0), softWrap = false)
                }
            }
        }
        // snippets
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            App.prefs.sshSnippets.lines().filter { it.isNotBlank() }.forEach { s ->
                Text(s, color = Spook.Moon, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Spook.Crypt2).clickable { Ssh.send(s + "\n") }.padding(horizontal = 8.dp, vertical = 5.dp))
            }
        }
        // special keys
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Ctrl-C" to "\u0003", "Ctrl-D" to "\u0004", "Ctrl-Z" to "\u001a", "Ctrl-L" to "\u000c", "Tab" to "\t", "Esc" to "\u001b",
                "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "q" to "q", "y" to "y", ":wq" to ":wq\r").forEach { (l, seq) ->
                Text(l, color = Spook.Bone, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Spook.Crypt3).clickable { Ssh.send(seq) }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Row(Modifier.fillMaxWidth().background(Spook.Crypt).navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$", color = Spook.Slime, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(6.dp))
            BasicTextField(input, { input = it }, singleLine = true,
                textStyle = TextStyle(color = Spook.Bone, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                cursorBrush = SolidColor(Spook.Pumpkin),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { Ssh.send(input + "\r"); input = "" }),
                modifier = Modifier.weight(1f))
            Text("⏎", color = Spook.Pumpkin, fontSize = 20.sp, modifier = Modifier.clickable { Ssh.send(input + "\r"); input = "" }.padding(horizontal = 10.dp))
        }
    }
}

@Composable
private fun SshSetupPage(onDone: () -> Unit) {
    val p = App.prefs
    var host by remember { mutableStateOf(p.sshHost) }
    var port by remember { mutableStateOf(p.sshPort.toString()) }
    var user by remember { mutableStateOf(p.sshUser) }
    var pass by remember { mutableStateOf(p.sshPassword) }
    var key by remember { mutableStateOf(p.sshKey) }
    var snippets by remember { mutableStateOf(p.sshSnippets) }
    Page("SSH login", onDone) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stored only on this phone.", color = Spook.Mist, fontSize = 12.sp)
            Choice(listOf(App.prefs.lanHost.substringBefore(':') to "Home", App.prefs.tailscaleHost.substringBefore(':') to "Tailscale"), host) { host = it }
            Field(host, "Host") { host = it }
            Field(port, "Port", numeric = true) { port = it.filter(Char::isDigit) }
            Field(user, "User") { user = it }
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            Field(key, "…or a private key (paste the whole PEM / OpenSSH key)", singleLine = false, minLines = 3, mono = true) { key = it }
            Field(snippets, "Quick commands (one per line)", singleLine = false, minLines = 3, mono = true) { snippets = it }
            PrimaryButton("Save & connect") {
                p.sshHost = host; p.sshPort = port.toIntOrNull() ?: 22; p.sshUser = user; p.sshPassword = pass; p.sshKey = key.trim(); p.sshSnippets = snippets
                onDone()
            }
        }
    }
}
