package com.maiconerick.xumbra.data

import android.content.Context
import android.content.SharedPreferences
import com.maiconerick.xumbra.ui.AmbTune

/** Everything this phone remembers. Server-side chat settings live on the Pi, not here. */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("xumbra", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = p.getString(k, d) ?: d
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()
    private fun bool(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    private fun float(k: String, d: Float) = p.getFloat(k, d)
    private fun putF(k: String, v: Float) = p.edit().putFloat(k, v).apply()

    // ── connection: tried in this order; the first that answers wins
    var lanHost: String get() = str("lanHost", "192.168.1.128:8765"); set(v) = put("lanHost", v.trim())
    var tailscaleHost: String get() = str("tsHost", "100.64.0.128:8765"); set(v) = put("tsHost", v.trim())
    var customHost: String get() = str("customHost", ""); set(v) = put("customHost", v.trim())
    var token: String get() = str("token", ""); set(v) = put("token", v.trim())
    /** The route that worked last time is tried first (faster start). */
    var lastRoute: String get() = str("lastRoute", ""); set(v) = put("lastRoute", v)
    /** Away from home: the Pi's Cloudflare tunnel (URL changes on restart) and the ntfy topic it's announced on. */
    var tunnelUrl: String get() = str("tunnelUrl", ""); set(v) = put("tunnelUrl", v.trim())
    var ntfyTopic: String get() = str("ntfyTopic", ""); set(v) = put("ntfyTopic", v.trim())
    /** Vanishing chats: how long each word stays before its letters drift away. */
    var vanishSeconds: Float get() = float("vanishSeconds", 2.5f); set(v) = putF("vanishSeconds", v)

    // ── background + notifications
    var background: Boolean get() = bool("background", true); set(v) = putB("background", v)
    var notifyReplies: Boolean get() = bool("notifyReplies", true); set(v) = putB("notifyReplies", v)
    var notifyProactive: Boolean get() = bool("notifyProactive", true); set(v) = putB("notifyProactive", v)
    var notifyCron: Boolean get() = bool("notifyCron", false); set(v) = putB("notifyCron", v)

    // ── chat behaviour
    var keepDefault: Boolean get() = bool("keepDefault", true); set(v) = putB("keepDefault", v)
    var enterSends: Boolean get() = bool("enterSends", false); set(v) = putB("enterSends", v)
    var showToolChips: Boolean get() = bool("showToolChips", true); set(v) = putB("showToolChips", v)
    var showLiveShell: Boolean get() = bool("showLiveShell", true); set(v) = putB("showLiveShell", v)
    var showFooters: Boolean get() = bool("showFooters", true); set(v) = putB("showFooters", v)
    var showCost: Boolean get() = bool("showCost", true); set(v) = putB("showCost", v)
    var personaBackgrounds: Boolean get() = bool("personaBg", true); set(v) = putB("personaBg", v)
    var personaBgAlpha: Float get() = float("personaBgAlpha", 0.28f); set(v) = putF("personaBgAlpha", v)
    var textScale: Float get() = float("textScale", 1f); set(v) = putF("textScale", v)
    /** Japanese text: kanji drawn this many times bigger than the rest, and colours per kind of character/word. */
    var kanjiScale: Float get() = float("kanjiScale", 1.3f); set(v) = putF("kanjiScale", v)
    var jpColors: Boolean get() = bool("jpColors", true); set(v) = putB("jpColors", v)
    fun jpColor(kind: String, def: Long): Long = if (p.contains("jp_$kind")) p.getLong("jp_$kind", def) else def
    fun setJpColor(kind: String, v: Long?) = if (v == null) p.edit().remove("jp_$kind").apply() else p.edit().putLong("jp_$kind", v).apply()
    var transcribeLang: String get() = str("transcribeLang", ""); set(v) = put("transcribeLang", v)
    /** gemini | openai — picked on the recording bar, remembered. */
    var transcribeProvider: String get() = str("transcribeProvider", "gemini"); set(v) = put("transcribeProvider", v)
    var showHidden: Boolean get() = bool("showHidden", false); set(v) = putB("showHidden", v)
    /** Matrix-style typewriter for assistant streaming (ported from xNotificationSystem). */
    var matrixStream: Boolean get() = bool("matrixStream", true); set(v) = putB("matrixStream", v)
    /** Milliseconds per grapheme for the Matrix stream reveal (lower = faster). */
    var matrixStreamSpeed: Int get() = p.getInt("matrixStreamSpeed", 28); set(v) = p.edit().putInt("matrixStreamSpeed", v).apply()

    // ── looks (same set as xWapp)
    var theme: String get() = str("theme", "pumpkin"); set(v) = put("theme", v)
    var customTheme: String get() = str("customTheme", ""); set(v) = put("customTheme", v)
    var animations: Boolean get() = bool("animations", true); set(v) = putB("animations", v)
    var homeAmbient: String get() = str("homeAmbient", "fireflies"); set(v) = put("homeAmbient", v)
    var chatAmbient: String get() = str("chatAmbient", "wisps"); set(v) = put("chatAmbient", v)
    var batPet: Boolean get() = bool("batPet", false); set(v) = putB("batPet", v)
    var batPetSize: Float get() = float("batPetSize", 1f); set(v) = putF("batPetSize", v)
    fun tune(screen: String) = AmbTune(float("tune_${screen}_n", 1.8f), float("tune_${screen}_sc", 1.4f), float("tune_${screen}_reach", 0.6f))
    fun setTune(screen: String, t: AmbTune) {
        p.edit().putFloat("tune_${screen}_n", t.n).putFloat("tune_${screen}_sc", t.sc).putFloat("tune_${screen}_reach", t.reach).apply()
    }

    // ── SSH terminal
    var sshHost: String get() = str("sshHost", "192.168.1.128"); set(v) = put("sshHost", v.trim())
    var sshPort: Int get() = p.getInt("sshPort", 22); set(v) = p.edit().putInt("sshPort", v).apply()
    var sshUser: String get() = str("sshUser", "pi"); set(v) = put("sshUser", v.trim())
    var sshPassword: String get() = str("sshPassword", ""); set(v) = put("sshPassword", v)
    var sshKey: String get() = str("sshKey", ""); set(v) = put("sshKey", v)
    var sshSnippets: String get() = str("sshSnippets", "sudo systemctl restart umbra\njournalctl -u umbra -n 60 --no-pager\ndf -h /\nhtop"); set(v) = put("sshSnippets", v)

    // ── drafts per chat (survive closing the app)
    fun draft(sid: String) = str("draft_$sid", "")
    fun setDraft(sid: String, text: String) = if (text.isEmpty()) p.edit().remove("draft_$sid").apply() else put("draft_$sid", text)
}
