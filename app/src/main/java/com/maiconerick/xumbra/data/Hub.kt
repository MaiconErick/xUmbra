package com.maiconerick.xumbra.data

import com.maiconerick.xumbra.App
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

enum class Conn { Idle, Connecting, Online, Offline }

/** One server event: {"seq","sid","type","data"}. */
data class HubEvent(val seq: Long, val sid: String?, val type: String, val data: JSONObject)

/**
 * The single live connection to the Pi (API v2 hub). Reconnects forever with
 * backoff, re-probing routes (Wi-Fi → Tailscale → custom) after failures.
 * Events go into an unlimited channel so a slow consumer never blocks the socket.
 */
object Hub {
    val state = MutableStateFlow(Conn.Idle)
    val pingMs = MutableStateFlow(-1L)
    val error = MutableStateFlow("")
    /** Changes every time the server restarts: everything must be reloaded. */
    val boot = MutableStateFlow("")

    private val channel = Channel<HubEvent>(Channel.UNLIMITED)
    val events = channel.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var wantRunning = false
    private var lastPingSent = 0L
    private val closed = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        wantRunning = true
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    fun stop() {
        wantRunning = false
        loop?.cancel(); loop = null
        ws?.close(1000, "bye"); ws = null
        state.value = Conn.Idle
    }

    /** Drop the socket and reconnect now (settings changed, user long-pressed the bubble). */
    fun restart() {
        ws?.cancel()
        loop?.cancel(); loop = null
        wantRunning = true
        loop = scope.launch { run() }
    }

    fun send(op: JSONObject): Boolean = ws?.send(op.toString()) ?: false

    fun watch(sids: Collection<String>, after: Map<String, Long> = emptyMap()) {
        if (sids.isEmpty()) return
        val a = JSONObject(); after.forEach { (k, v) -> a.put(k, v) }
        send(JSONObject().put("op", "watch").put("sids", org.json.JSONArray(sids.toList())).put("after", a))
    }

    fun unwatch(sids: Collection<String>) {
        if (sids.isEmpty()) return
        send(JSONObject().put("op", "unwatch").put("sids", org.json.JSONArray(sids.toList())))
    }

    fun ping() {
        lastPingSent = System.currentTimeMillis()
        send(JSONObject().put("op", "ping").put("t", lastPingSent))
    }

    private suspend fun run() {
        var backoff = 1000L
        var failures = 0
        while (wantRunning) {
            state.value = Conn.Connecting
            val route = if (failures == 0 && Net.route.value != null) Net.route.value else Net.resolve(App.prefs)
            if (route == null) {
                error.value = "No route answered (Wi-Fi, Tailscale, custom)"
                state.value = Conn.Offline
                failures++
                delay(backoff); backoff = (backoff * 2).coerceAtMost(15_000)
                continue
            }
            while (closed.tryReceive().isSuccess) { /* drain */ }
            val url = route.base.replaceFirst("http", "ws") + "/api/v2/ws?client=" +
                Net.enc(Build.MODEL ?: "phone") + (App.prefs.token.takeIf { it.isNotEmpty() }?.let { "&token=" + Net.enc(it) } ?: "")
            val opened = MutableStateFlow(false)
            ws = Net.client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.value = true
                    state.value = Conn.Online
                    error.value = ""
                    ping()
                    scope.launch { Net.learnRemote(App.prefs) }   // at home: remember how to reach the Pi when away
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val o = try { JSONObject(text) } catch (_: Exception) { return }
                    val ev = HubEvent(o.optLong("seq"), o.str("sid"), o.optString("type"), o.optJSONObject("data") ?: JSONObject())
                    when (ev.type) {
                        "pong" -> { pingMs.value = System.currentTimeMillis() - ev.data.optLong("t", lastPingSent); return }
                        "hello" -> boot.value = ev.data.optString("boot")
                    }
                    channel.trySend(ev)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (code == 4401) error.value = "The server refused the token"
                    closed.trySend(Unit)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    error.value = t.message ?: t.javaClass.simpleName
                    closed.trySend(Unit)
                }
            })
            // keepalive ping every 25 s (also refreshes the latency bars)
            val pinger = scope.launch { while (true) { delay(25_000); ping() } }
            closed.receive()
            pinger.cancel()
            ws = null
            if (opened.value) { failures = 0; backoff = 1000L } else { failures++; Net.route.value = null }
            if (!wantRunning) break
            state.value = Conn.Offline
            channel.trySend(HubEvent(0, null, "disconnected", JSONObject()))
            delay(backoff); backoff = (backoff * 2).coerceAtMost(15_000)
        }
    }
}
