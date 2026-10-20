package com.maiconerick.xumbra.data

import com.maiconerick.xumbra.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiError(val code: Int, message: String) : IOException(message)

/** A way to reach the Pi: home Wi-Fi, Tailscale, or a custom address. */
data class Route(val kind: String, val label: String, val host: String) {
    val base get() = if (host.startsWith("http")) host.trimEnd('/') else "http://${host.trimEnd('/')}"
}

/**
 * REST access to the Umbra API. The base URL is whichever route answered
 * /api/health first; [resolve] re-probes when a call fails.
 */
object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val probe: OkHttpClient = client.newBuilder()
        .connectTimeout(1800, TimeUnit.MILLISECONDS).readTimeout(2500, TimeUnit.MILLISECONDS).build()

    val route = MutableStateFlow<Route?>(null)
    val trying = MutableStateFlow("")
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun routes(p: Prefs): List<Route> {
        val all = listOfNotNull(
            p.lanHost.takeIf { it.isNotBlank() }?.let { Route("lan", "Home Wi-Fi", it) },
            p.tailscaleHost.takeIf { it.isNotBlank() }?.let { Route("ts", "Tailscale", it) },
            p.customHost.takeIf { it.isNotBlank() }?.let { Route("custom", "Custom", it) },
            p.tunnelUrl.takeIf { it.startsWith("https://") && p.token.isNotBlank() }?.let { Route("tunnel", "Tunnel", it) },
        )
        // the route that worked last time goes first
        return all.sortedBy { if (it.kind == p.lastRoute) 0 else 1 }
    }

    /** Probe every route at once; the first healthy one in priority order wins. */
    suspend fun resolve(p: Prefs): Route? = coroutineScope {
        val rs = routes(p)
        trying.value = rs.joinToString(" · ") { it.label }
        val checks = rs.map { r -> r to async(Dispatchers.IO) { healthy(r) } }
        var win: Route? = null
        for ((r, d) in checks) if (d.await()) { win = r; break }
        // away from home: the tunnel's address changes when the Pi restarts it — ask ntfy for the current one
        if (win == null && p.token.isNotBlank()) {
            trying.value = "Tunnel"
            val fresh = withContext(Dispatchers.IO) { lookupTunnel(p.ntfyTopic) }
            if (fresh != null && fresh != p.tunnelUrl) {
                val r = Route("tunnel", "Tunnel", fresh)
                if (withContext(Dispatchers.IO) { healthy(r) }) { p.tunnelUrl = fresh; win = r }
            }
        }
        trying.value = ""
        if (win != null) p.lastRoute = win.kind
        route.value = win
        win
    }

    /** Latest tunnel URL the Pi announced on its ntfy topic, or null. */
    private fun lookupTunnel(topic: String): String? {
        if (topic.isBlank()) return null
        return try {
            val text = probe.newCall(Request.Builder().url("https://ntfy.sh/$topic/json?poll=1&since=24h").build()).execute()
                .use { it.body?.string().orEmpty() }
            text.lines().filter { it.isNotBlank() }.map { JSONObject(it) }.lastOrNull { it.optString("event") == "message" }
                ?.optString("message")?.trim()?.takeIf { it.startsWith("https://") }
        } catch (_: Exception) { null }
    }

    /** At home (LAN / Tailscale): learn the remote key, tunnel URL and ntfy topic for when we're away. */
    suspend fun learnRemote(p: Prefs) {
        val r = route.value ?: return
        if (r.kind == "tunnel") return
        try {
            val o = get("/api/v2/remote")
            // "key" here is the tunnel key. The API token now comes from
            // XUMBRA_API_TOKEN (provisioning/Settings), so only fill it in on
            // a fresh install — overwriting it locked the app out of the API.
            if (p.token.isBlank()) o.optString("key").takeIf { it.isNotBlank() }?.let { p.token = it }
            o.optString("ntfy_topic").takeIf { it.isNotBlank() }?.let { p.ntfyTopic = it }
            o.optString("tunnel").takeIf { it.startsWith("https://") }?.let { p.tunnelUrl = it }
        } catch (_: Exception) { }
    }

    private fun healthy(r: Route): Boolean = try {
        probe.newCall(Request.Builder().url(r.base + "/api/health").build()).execute().use { it.isSuccessful }
    } catch (_: Exception) { false }

    fun base(): String = route.value?.base ?: Route("lan", "", App.prefs.lanHost).base

    fun mediaUrl(name: String): String = base() + "/api/media/" + name
    fun url(path: String): String = base() + path

    private fun req(path: String): Request.Builder {
        val b = Request.Builder().url(base() + path)
        App.prefs.token.takeIf { it.isNotEmpty() }?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    suspend fun raw(method: String, path: String, body: Any? = null): String = withContext(Dispatchers.IO) {
        val rb = when (body) {
            null -> if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(JSON)
            is JSONObject, is JSONArray -> body.toString().toRequestBody(JSON)
            is String -> body.toRequestBody(JSON)
            else -> body.toString().toRequestBody(JSON)
        }
        val r = req(path).method(method, rb).build()
        client.newCall(r).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = try { JSONObject(text).optString("detail", text) } catch (_: Exception) { text }
                throw ApiError(resp.code, detail.ifBlank { "HTTP ${resp.code}" }.take(300))
            }
            text
        }
    }

    suspend fun get(path: String) = JSONObject(raw("GET", path))
    suspend fun post(path: String, body: Any? = null) = JSONObject(raw("POST", path, body).ifBlank { "{}" })
    suspend fun put(path: String, body: Any? = null) = JSONObject(raw("PUT", path, body).ifBlank { "{}" })
    suspend fun patch(path: String, body: Any? = null) = JSONObject(raw("PATCH", path, body).ifBlank { "{}" })
    suspend fun delete(path: String) = JSONObject(raw("DELETE", path).ifBlank { "{}" })
    suspend fun text(path: String) = raw("GET", path)

    /** Multipart upload of a file; returns the server JSON. */
    suspend fun upload(path: String, fileName: String, mime: String, bytes: ByteArray,
                       extra: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaType()))
        extra.forEach { (k, v) -> mb.addFormDataPart(k, v) }
        val r = req(path).post(mb.build()).build()
        client.newCall(r).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiError(resp.code, text.take(300))
            JSONObject(text)
        }
    }

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) ?: JSONObject() }
fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else optString(k).takeIf { it.isNotEmpty() }
