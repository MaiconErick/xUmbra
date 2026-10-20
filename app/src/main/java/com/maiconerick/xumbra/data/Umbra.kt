package com.maiconerick.xumbra.data

import com.maiconerick.xumbra.App
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Everything one open chat shows. */
data class ChatState(
    val sid: String,
    val messages: List<Msg> = emptyList(),
    val hasMore: Boolean = false,
    val turn: LiveTurn? = null,
    val queued: List<Msg> = emptyList(),
    val settings: ChatSettings? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val suggestion: Pair<String, String>? = null, // (suggested title, reason)
    val summaryText: String = "",
)

/**
 * The app's single source of truth: chat list, open chats, live turns.
 * Fed by REST snapshots plus the hub's event stream; survives activity restarts
 * (it lives in the process) so the foreground service and the UI share it.
 */
object Umbra {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var cacheDir: File

    val sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    val sessionsLoaded = MutableStateFlow(false)
    val banner = MutableStateFlow<String?>(null)
    val personas = MutableStateFlow<List<Persona>>(emptyList())
    val tools = MutableStateFlow<List<ToolInfo>>(emptyList())
    /** Cron/agent events for the Agents screen. */
    val cronEvents = MutableSharedFlow<HubEvent>(extraBufferCapacity = 64)
    /** New assistant messages + proactive notifications for the notifier. */
    val alerts = MutableSharedFlow<HubEvent>(extraBufferCapacity = 64)

    private val chats = ConcurrentHashMap<String, MutableStateFlow<ChatState>>()
    private val lastSeq = ConcurrentHashMap<String, Long>()
    private val watched = mutableSetOf<String>()
    /** sid of the chat on screen while the app is in the foreground (no alert for it). */
    @Volatile var visibleSid: String? = null
    @Volatile var foreground = false
    private var knownBoot = ""
    private var started = false

    fun init(ctx: Context) {
        if (started) return
        started = true
        cacheDir = File(ctx.filesDir, "cache").apply { mkdirs() }
        readCachedSessions()
        scope.launch { Hub.events.collect { handle(it) } }
    }

    fun chat(sid: String): MutableStateFlow<ChatState> =
        chats.getOrPut(sid) { MutableStateFlow(readCachedChat(sid) ?: ChatState(sid)) }

    fun row(sid: String) = sessions.value.firstOrNull { it.id == sid }

    // ──────────────────────────────────────────────────────────────
    //  Loading
    // ──────────────────────────────────────────────────────────────
    fun refreshSessions() = scope.launch {
        try {
            val o = Net.get("/api/v2/sessions" + if (App.prefs.showHidden) "?include_hidden=true" else "")
            val rows = o.optJSONArray("sessions").objects().map(SessionRow::of)
            sessions.value = rows
            sessionsLoaded.value = true
            writeCachedSessions(rows)
        } catch (e: Exception) {
            if (sessions.value.isEmpty()) banner.value = "Couldn't load chats: ${e.message}"
        }
    }

    /** Load (or reload) a chat from the Pi and start receiving its live events. */
    fun open(sid: String) {
        synchronized(watched) { watched += sid }
        val f = chat(sid)
        if (!f.value.loading) loadState(sid)
    }

    fun close(sid: String) {
        synchronized(watched) { watched -= sid }
        Hub.unwatch(listOf(sid))
    }

    private fun loadState(sid: String): Job = scope.launch {
        val f = chat(sid)
        f.update { it.copy(loading = true, error = null) }
        try {
            val o = Net.get("/api/v2/sessions/$sid/state?limit=80")
            val seq = o.optLong("seq")
            val msgs = o.optJSONArray("messages").objects().map(Msg::of)
            val turn = o.optJSONObject("turn")?.let(LiveTurn::of)
            val queued = o.optJSONArray("queued").objects().map(::queuedMsg)
            val pendingLocal = f.value.messages.filter { m -> m.pending && msgs.none { it.clientId == m.clientId } && queued.none { it.clientId == m.clientId } }
            f.update {
                it.copy(messages = msgs + pendingLocal, hasMore = o.optBoolean("has_more"), turn = turn, queued = queued,
                    settings = ChatSettings.of(o.optJSONObject("settings")), loaded = true, loading = false,
                    summaryText = o.optString("summary_text"))
            }
            o.optJSONObject("session")?.let { upsertRow(SessionRow.of(it)) }
            lastSeq[sid] = seq
            Hub.watch(listOf(sid), mapOf(sid to seq))
            writeCachedChat(sid)
            // Opened in the middle of a turn without a socket: keep refreshing
            // so the tool calls are visible instead of a frozen bubble.
            if (turn != null && Hub.state.value != Conn.Online) follow(sid)
        } catch (e: Exception) {
            f.update { it.copy(loading = false, error = e.message ?: "load failed") }
        }
    }

    fun loadOlder(sid: String) = scope.launch {
        val f = chat(sid)
        val first = f.value.messages.firstOrNull { !it.pending } ?: return@launch
        if (!f.value.hasMore) return@launch
        try {
            val o = Net.get("/api/v2/sessions/$sid/messages?limit=80&before=${first.ts}")
            val older = o.optJSONArray("messages").objects().map(Msg::of)
            f.update { st -> st.copy(messages = older.filter { o2 -> st.messages.none { it.id == o2.id } } + st.messages, hasMore = o.optBoolean("has_more")) }
        } catch (_: Exception) { }
    }

    fun loadPersonas() = scope.launch {
        try { personas.value = Net.get("/api/personas").optJSONArray("personas").objects().map(Persona::of) } catch (_: Exception) { }
    }

    fun loadTools() = scope.launch {
        if (tools.value.isNotEmpty()) return@launch
        try { tools.value = Net.get("/api/tools/catalog").optJSONArray("tools").objects().map { ToolInfo(it.optString("name"), it.optString("description")) } } catch (_: Exception) { }
    }

    fun persona(name: String?) = name?.let { n -> personas.value.firstOrNull { it.name == n } }

    // ──────────────────────────────────────────────────────────────
    //  Events
    // ──────────────────────────────────────────────────────────────
    private fun handle(ev: HubEvent) {
        val sid = ev.sid
        if (sid != null && ev.seq > 0) lastSeq[sid] = maxOf(lastSeq[sid] ?: 0, ev.seq)
        val d = ev.data
        when (ev.type) {
            "hello" -> onHello(d)
            "session" -> if (sid != null) upsertRow(SessionRow.of(d))
            "session_deleted" -> if (sid != null) sessions.update { l -> l.filterNot { it.id == sid } }
            "turn_state" -> if (sid != null) {
                val busy = d.optBoolean("busy")
                sessions.update { l -> l.map { if (it.id == sid) it.copy(busy = busy) else it } }
                chats[sid]?.update { st ->
                    if (busy) st.copy(turn = d.optJSONObject("turn")?.let(LiveTurn::of) ?: st.turn)
                    else st.copy(turn = null)
                }
                if (!busy) chats[sid]?.let { scope.launch { writeCachedChat(sid) } }
            }
            "message" -> if (sid != null) onMessage(sid, Msg.of(d.optJSONObject("message") ?: JSONObject()), ev)
            "message_deleted" -> if (sid != null) chats[sid]?.update { st -> st.copy(messages = st.messages.filterNot { it.id == d.optString("id") }) }
            "queue" -> if (sid != null) {
                val q = d.optJSONArray("queued").objects().map(::queuedMsg)
                chats[sid]?.update { st -> st.copy(queued = q, messages = st.messages.filterNot { m -> m.pending && q.any { it.clientId == m.clientId } }) }
                sessions.update { l -> l.map { if (it.id == sid) it.copy(queued = q.size) else it } }
            }
            "notification" -> scope.launch { alerts.emit(ev) }
            "cron" -> scope.launch { cronEvents.emit(ev) }
            "worker" -> com.maiconerick.xumbra.ui.Workers.onEvent(ev.data)
            "resync" -> if (sid != null) loadState(sid)
            "disconnected" -> {}
            else -> if (sid != null) onTurnEvent(sid, ev)
        }
    }

    private fun onHello(d: JSONObject) {
        val boot = d.optString("boot")
        val restarted = knownBoot.isNotEmpty() && boot != knownBoot
        knownBoot = boot
        refreshSessions()
        val open = synchronized(watched) { watched.toList() }
        for (sid in open) {
            val seq = lastSeq[sid]
            if (restarted || seq == null || chats[sid]?.value?.loaded != true) loadState(sid)
            else Hub.watch(listOf(sid), mapOf(sid to seq))
        }
        scope.launch { alerts.emit(HubEvent(0, null, "catchup", JSONObject())) }
        // anything we sent while offline goes out now
        for ((sid, f) in chats) f.value.messages.filter { it.pending && it.failed == null }.forEach { resend(sid, it) }
    }

    private fun onMessage(sid: String, m: Msg, ev: HubEvent) {
        chats[sid]?.update { st ->
            var msgs = st.messages.filterNot { (m.clientId != null && it.clientId == m.clientId && it.pending) || it.id == m.id }
            msgs = msgs + m
            val turn = if (m.role != "user" && st.turn != null) st.turn.copy(partial = "") else st.turn
            st.copy(messages = msgs.sortedBy { if (it.pending) Double.MAX_VALUE else it.ts }, turn = turn,
                queued = st.queued.filterNot { it.clientId != null && it.clientId == m.clientId })
        }
        sessions.update { l ->
            l.map {
                if (it.id != sid) it else it.copy(
                    ts = m.ts, count = it.count + 1, costUsd = it.costUsd + (m.turn?.costUsd ?: 0.0),
                    tokensIn = it.tokensIn + (m.turn?.inTok ?: 0), tokensOut = it.tokensOut + (m.turn?.outTok ?: 0),
                    last = LastMsg(m.role, m.text.take(160), m.media.size, m.ts),
                    unread = if (m.role == "assistant" && !(foreground && visibleSid == sid)) it.unread + 1 else it.unread,
                )
            }.sortedWith(compareBy<SessionRow> { if (it.starred) 0 else 1 }.thenByDescending { it.ts })
        }
        if (m.role == "assistant") Money.soon()
        if (m.role == "assistant" || m.role == "error") {
            if (foreground && visibleSid == sid) markRead(sid)
            else scope.launch { alerts.emit(ev) }
        }
    }

    private fun onTurnEvent(sid: String, ev: HubEvent) {
        val f = chats[sid] ?: return
        val d = ev.data
        f.update { st ->
            var t = st.turn ?: if (ev.type in setOf("turn.start", "status", "assistant_delta", "tool_start")) LiveTurn("", System.currentTimeMillis() / 1000.0, "", "Thinking", null, emptyList(), emptyList(), "app") else null
            if (t != null && d.has("max_loops")) t = t.copy(maxLoops = d.optInt("max_loops", t.maxLoops))
            if (t == null) return@update when (ev.type) {
                "topic_switch_suggested" -> st.copy(suggestion = d.optString("suggested_name") to d.optString("reason"))
                else -> st
            }
            t = when (ev.type) {
                "assistant_delta" -> t.copy(partial = t.partial + d.optString("text"))
                // Never blank a bubble the user is reading: the server persists
                // narration before tool calls, and a request arriving mid-stream
                // used to erase the line that was already on screen.
                "llm_request" -> t.copy(status = "Thinking")
                "status" -> t.copy(status = d.optString("text").lineSequence().firstOrNull().orEmpty().take(120))
                "tool_start" -> t.copy(tool = d.optString("tool"), status = "Running ${d.optString("tool")}", shell = emptyList(),
                    note = d.optString("note").ifBlank { null },
                    search = if (d.optString("tool") == "web_search") SearchLive(d.optJSONObject("args")?.optString("query").orEmpty()) else t.search,
                    tools = t.tools + ToolCall(name = d.optString("tool"), args = ToolCall.argsText(d.opt("args")),
                        result = null, ms = null, callId = d.str("call_id"), raw = d.optJSONObject("args"),
                        note = d.optString("note").ifBlank { null }, text = d.optString("text").ifBlank { null }))
                "tool_end" -> t.copy(tool = null, note = null, status = "Thinking", search = if (d.optString("tool") == "web_search") null else t.search, tools = t.tools.map {
                    if (it.callId != null && it.callId == d.str("call_id")) it.copy(result = d.optString("result").take(400)) else it })
                "tool_progress" -> t.copy(shell = (t.shell + d.optString("line")).takeLast(4))
                "search_progress" -> {
                    val cur = t.search ?: SearchLive(d.optString("query"))
                    t.copy(search = cur.copy(
                        query = d.optString("query").ifBlank { cur.query },
                        engine = d.optString("engine").ifBlank { cur.engine },
                        reddit = if (d.has("reddit")) d.optBoolean("reddit") else cur.reddit,
                        stage = d.optString("stage").ifBlank { cur.stage },
                        sites = d.optJSONArray("sites").strings().ifEmpty { cur.sites },
                        preview = d.optString("preview").ifBlank { cur.preview },
                    ))
                }
                "image_progress" -> {
                    val cur = t.image
                    t.copy(image = when (d.optString("stage")) {
                        "start" -> ImageLive(d.optString("name"), d.optString("provider"), d.optDouble("expected", 15.0),
                            note = cur?.note, refs = d.optInt("refs"))
                        "partial" -> cur?.copy(partial = d.optString("partial"), stage = "partial")
                        // the refused model's half-drawn preview goes away; the next "start" brings the new model
                        "fallback" -> cur?.copy(partial = null, stage = "fallback",
                            note = "${d.optString("from_name")} refused it (${d.optString("reason")}) → trying ${d.optString("to_name")}")
                        "writing", "running", "fixing" -> cur?.copy(stage = d.optString("stage"), attempt = d.optInt("attempt", 1))
                        "done", "error" -> null
                        else -> cur
                    })
                }
                "media_added" -> t.copy(media = t.media + d.optString("name"))
                "side_message" -> t
                else -> t
            }
            val sug = if (ev.type == "topic_switch_suggested") d.optString("suggested_name") to d.optString("reason") else st.suggestion
            st.copy(turn = t, suggestion = sug)
        }
    }

    private fun upsertRow(r: SessionRow) {
        sessions.update { l ->
            val prev = l.firstOrNull { it.id == r.id }
            val nr = if (prev != null && prev.costUsd > 0 && r.costUsd == 0.0) r.copy(costUsd = prev.costUsd, turns = prev.turns) else r
            val list = if (prev == null) listOf(nr) + l else l.map { if (it.id == r.id) nr else it }
            list.filter { App.prefs.showHidden || !it.hidden }
                .sortedWith(compareBy<SessionRow> { if (it.starred) 0 else 1 }.thenByDescending { it.ts })
        }
        chats[r.id]?.let { f -> f.value.settings?.let { s -> f.update { it.copy(settings = s.copy(summary = r.summary, starred = r.starred)) } } }
    }

    private fun queuedMsg(o: JSONObject) = Msg(
        id = "q-" + o.optString("client_id"), role = "user", ts = Double.MAX_VALUE, text = o.optString("text"),
        media = o.optJSONArray("media_ids").strings(), ephemeral = !o.optBoolean("keep", true),
        clientId = o.str("client_id"), turn = null, source = "app", pending = true,
    )

    // ──────────────────────────────────────────────────────────────
    //  Actions
    // ──────────────────────────────────────────────────────────────
    /** Send a message (images are uploaded first). Shows instantly; marked failed if it can't be delivered. */
    fun send(ctx: Context, sid: String, text: String, images: List<Uri>, keep: Boolean, tools: List<String>? = null) {
        val cid = "a-" + UUID.randomUUID().toString().take(12)
        val local = Msg("local-$cid", "user", System.currentTimeMillis() / 1000.0, text, emptyList(), !keep, cid, null, "app",
            pending = true, localImages = images.map { it.toString() })
        chat(sid).update { it.copy(messages = it.messages + local) }
        scope.launch {
            try {
                val names = images.map { uploadImage(ctx, sid, it) }
                val m = local.copy(media = names)
                chat(sid).update { st -> st.copy(messages = st.messages.map { if (it.clientId == cid) m else it }) }
                deliver(sid, m, tools)
            } catch (e: Exception) {
                markFailed(sid, cid, e.message ?: "upload failed")
            }
        }
    }

    private val toolsByCid = ConcurrentHashMap<String, List<String>>()

    /**
     * Chats being followed by polling. The socket is the fast path, but it is
     * dropped while the app is in the background and takes a moment to come
     * back — a message sent in that gap would run on the server with nothing
     * on screen. While there is no socket we refresh the chat every 2 s, so
     * the turn status and its tool calls still appear.
     */
    private val following = ConcurrentHashMap.newKeySet<String>()

    private fun follow(sid: String) {
        if (!following.add(sid)) return
        scope.launch {
            try {
                followTurn(sid)
            } finally {
                following.remove(sid)
            }
        }
    }

    private suspend fun followTurn(sid: String, maxMs: Long = 180_000) {
        val t0 = System.currentTimeMillis()
        var sawTurn = false
        while (System.currentTimeMillis() - t0 < maxMs) {
            if (Hub.state.value == Conn.Online) return   // the socket took over
            val st = chats[sid]?.value
            if (st?.turn != null) sawTurn = true
            else if (sawTurn) return                     // turn finished
            loadState(sid)
            delay(2000)
        }
    }

    private suspend fun deliver(sid: String, m: Msg, tools: List<String>?) {
        val cid = m.clientId ?: return
        tools?.let { toolsByCid[cid] = it }
        val body = JSONObject().put("text", m.text).put("media_ids", JSONArray(m.media)).put("client_id", cid).put("keep", !m.ephemeral)
        (tools ?: toolsByCid[cid])?.let { body.put("tools", JSONArray(it)) }
        // REST is the reliable path (works even while the socket is reconnecting); idempotent by client_id
        try {
            val r = Net.post("/api/v2/sessions/$sid/send", body)
            if (r.optString("status") == "duplicate") {
                r.optJSONObject("message")?.let { onMessage(sid, Msg.of(it), HubEvent(0, sid, "message", JSONObject())) }
            }
            // No socket (app was in the background, or it is still connecting):
            // follow this turn by polling so the tool calls stay visible.
            if (Hub.state.value != Conn.Online) follow(sid)
        } catch (e: ApiError) {
            markFailed(sid, cid, e.message ?: "refused")
        } catch (e: Exception) {
            // offline: stays pending, re-sent on the next hello
            chat(sid).update { st -> st.copy(messages = st.messages.map { if (it.clientId == cid) it.copy(failed = null) else it }) }
        }
    }

    private fun resend(sid: String, m: Msg) = scope.launch { deliver(sid, m, null) }

    fun retryFailed(sid: String, m: Msg) {
        chat(sid).update { st -> st.copy(messages = st.messages.map { if (it.clientId == m.clientId) it.copy(failed = null) else it }) }
        resend(sid, m.copy(failed = null))
    }

    fun discard(sid: String, m: Msg) {
        chat(sid).update { st -> st.copy(messages = st.messages.filterNot { it.clientId == m.clientId && it.pending }) }
    }

    private fun markFailed(sid: String, cid: String, why: String) {
        chat(sid).update { st -> st.copy(messages = st.messages.map { if (it.clientId == cid) it.copy(failed = why) else it }) }
    }

    private suspend fun uploadImage(ctx: Context, sid: String, uri: Uri): String = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: "image/jpeg"
        val bytes = Images.shrink(ctx, uri) ?: cr.openInputStream(uri)!!.use { it.readBytes() }
        val ext = if (bytes.size > 2 && bytes[0] == 0xFF.toByte()) "jpg" else mime.substringAfter('/').substringBefore(';').ifBlank { "jpg" }
        Net.upload("/api/sessions/$sid/media", "photo.$ext", if (ext == "jpg") "image/jpeg" else mime, bytes).optString("filename")
    }

    fun cancel(sid: String) = scope.launch {
        chat(sid).update { st -> st.copy(turn = st.turn?.copy(stopping = true, status = "Stopping…")) }
        if (!Hub.send(JSONObject().put("op", "cancel").put("sid", sid))) {
            try { Net.post("/api/v2/sessions/$sid/cancel") } catch (_: Exception) { }
        }
    }

    fun unqueue(sid: String, m: Msg) = scope.launch {
        val cid = m.clientId ?: return@launch
        try { Net.delete("/api/v2/sessions/$sid/queue/$cid") } catch (_: Exception) { }
    }

    fun retry(sid: String) = scope.launch {
        try { Net.post("/api/v2/sessions/$sid/retry") } catch (e: Exception) { banner.value = "Retry failed: ${e.message}" }
    }

    fun deleteMessage(sid: String, m: Msg) = scope.launch {
        try {
            Net.delete("/api/v2/sessions/$sid/messages/${m.id}")
            chat(sid).update { st -> st.copy(messages = st.messages.filterNot { it.id == m.id }) }
        } catch (e: Exception) { banner.value = "Couldn't delete: ${e.message}" }
    }

    private val readJobs = ConcurrentHashMap<String, Job>()
    fun markRead(sid: String) {
        sessions.update { l -> l.map { if (it.id == sid) it.copy(unread = 0) else it } }
        readJobs[sid]?.cancel()
        readJobs[sid] = scope.launch {
            delay(600)
            try { Net.post("/api/v2/sessions/$sid/read") } catch (_: Exception) { }
        }
    }

    /** PATCH chat settings; [body] holds only the fields to change. */
    suspend fun patchSettings(sid: String, body: JSONObject): Boolean = try {
        val o = Net.patch("/api/v2/sessions/$sid/settings", body)
        ChatSettings.of(o.optJSONObject("settings"))?.let { s -> chat(sid).update { it.copy(settings = s) } }
        o.optJSONObject("session")?.let { upsertRow(SessionRow.of(it)) }
        true
    } catch (e: Exception) {
        banner.value = "Couldn't save: ${e.message}"; false
    }

    fun patch(sid: String, body: JSONObject) = scope.launch { patchSettings(sid, body) }

    /** Create a chat; returns its id. [settings] holds persona/model/tools/etc. */
    suspend fun create(settings: JSONObject): String? = try {
        val o = Net.post("/api/v2/sessions", settings)
        val row = SessionRow.of(o.getJSONObject("session"))
        upsertRow(row)
        chat(row.id).update { it.copy(settings = ChatSettings.of(o.optJSONObject("settings"))) }
        row.id
    } catch (e: Exception) {
        banner.value = "Couldn't create the chat: ${e.message}"; null
    }

    fun delete(sid: String, wipe: Boolean) = scope.launch {
        try {
            Net.delete("/api/v2/sessions/$sid?wipe=$wipe")
            sessions.update { l -> l.filterNot { it.id == sid } }
            chats.remove(sid)
            File(cacheDir, "chat_$sid.json").delete()
        } catch (e: Exception) { banner.value = "Couldn't delete: ${e.message}" }
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)

    // ──────────────────────────────────────────────────────────────
    //  Local cache: the chat list and recent messages show instantly, even offline
    // ──────────────────────────────────────────────────────────────
    private fun readCachedSessions() {
        try {
            val f = File(cacheDir, "sessions.json")
            if (f.exists()) sessions.value = JSONArray(f.readText()).objects().map(SessionRow::of)
        } catch (_: Exception) { }
    }

    private fun writeCachedSessions(rows: List<SessionRow>) = scope.launch(Dispatchers.IO) {
        try { File(cacheDir, "sessions.json").writeText(JSONArray(rows.map { it.json() }).toString()) } catch (_: Exception) { }
    }

    private fun readCachedChat(sid: String): ChatState? = try {
        val f = File(cacheDir, "chat_$sid.json")
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            ChatState(sid, messages = o.optJSONArray("messages").objects().map(Msg::of), hasMore = true,
                settings = ChatSettings.of(o.optJSONObject("settings")))
        }
    } catch (_: Exception) { null }

    private suspend fun writeCachedChat(sid: String) = withContext(Dispatchers.IO) {
        val st = chats[sid]?.value ?: return@withContext
        try {
            val arr = JSONArray()
            st.messages.filter { !it.pending }.takeLast(80).forEach { m ->
                arr.put(JSONObject().put("id", m.id).put("role", m.role).put("ts", m.ts).put("text", m.text)
                    .put("media", JSONArray(m.media)).put("ephemeral", m.ephemeral).put("source", m.source ?: ""))
            }
            File(cacheDir, "chat_$sid.json").writeText(JSONObject().put("messages", arr).toString())
        } catch (_: Exception) { }
    }
}
