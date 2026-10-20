package com.maiconerick.xumbra.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.maiconerick.xumbra.App
import com.maiconerick.xumbra.MainActivity
import com.maiconerick.xumbra.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Turns hub events into Android notifications:
 *  - replies that finished while you were elsewhere (one conversation per chat)
 *  - proactive reminders Umbra scheduled for itself (cron agents with push)
 * Missed reminders are fetched and acknowledged when the connection comes back.
 */
object Notifier {
    const val CH_SERVICE = "service"
    const val CH_REPLIES = "replies"
    const val CH_REMINDERS = "reminders"
    const val CH_AGENTS = "agents"

    private lateinit var ctx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private data class Line(val sender: String, val text: String, val time: Long)
    private val convs = HashMap<String, MutableList<Line>>()
    private val shownReminders = HashSet<String>()

    fun init(c: Context) {
        ctx = c.applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Connection to Umbra", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_REPLIES, "Umbra's replies", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Reminders from Umbra", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_AGENTS, "Scheduled agents", NotificationManager.IMPORTANCE_DEFAULT))
        scope.launch { Umbra.alerts.collect { on(it) } }
    }

    private fun on(ev: HubEvent) {
        val p = App.prefs
        when (ev.type) {
            "message" -> {
                val m = Msg.of(ev.data.optJSONObject("message") ?: return)
                val sid = ev.sid ?: return
                val fromCron = m.source?.startsWith("cron:") == true
                if (fromCron && !p.notifyCron) return
                if (!fromCron && !p.notifyReplies) return
                if (m.text.isBlank() && m.media.isEmpty()) return
                val row = Umbra.row(sid)
                val sender = Umbra.persona(row?.persona)?.displayName ?: "Umbra"
                val body = when {
                    m.isError -> "⚠️ " + m.text
                    m.text.isBlank() -> "🖼 Image"
                    else -> plain(m.text)
                }
                post(sid, row?.title ?: "Chat $sid", sender, body, if (fromCron) CH_AGENTS else CH_REPLIES)
            }
            "notification" -> {
                if (!p.notifyProactive) return
                val id = ev.data.optString("id")
                val sid = ev.data.optString("sid", ev.sid ?: "")
                reminder(id, sid, ev.data.optString("text"))
            }
            "catchup" -> scope.launch {
                try {
                    val o = Net.get("/api/notifications/pending")
                    o.optJSONArray("notifications").objects().forEach { n ->
                        if (p.notifyProactive) reminder(n.optString("id"), n.optString("sid"), n.optString("text"))
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun reminder(id: String, sid: String, text: String) {
        if (id.isNotEmpty() && !shownReminders.add(id)) return
        val row = Umbra.row(sid)
        val sender = Umbra.persona(row?.persona)?.displayName ?: "Umbra"
        // the reply already shows up in the chat; if it's open right now there's no need to buzz
        if (!(Umbra.foreground && Umbra.visibleSid == sid)) post(sid, "⏰ " + (row?.title ?: "Umbra"), sender, plain(text), CH_REMINDERS)
        if (id.isNotEmpty()) scope.launch { try { Net.post("/api/notifications/$id/ack") } catch (_: Exception) { } }
    }

    private fun post(sid: String, title: String, sender: String, body: String, channel: String) {
        if (Umbra.sessions.value.firstOrNull { it.id == sid }?.secret == true) return   // vault chats never notify
        if (Umbra.foreground && Umbra.visibleSid == sid) return
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val key = "$channel:$sid"
        val lines = synchronized(convs) {
            convs.getOrPut(key) { mutableListOf() }.also { it += Line(sender, body.take(600), System.currentTimeMillis()); while (it.size > 12) it.removeAt(0) }.toList()
        }
        val intent = Intent(ctx, MainActivity::class.java).putExtra("chat", sid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val me = android.app.Person.Builder().setName("You").build()
        val style = Notification.MessagingStyle(me).setConversationTitle(title).setGroupConversation(true)
        val people = HashMap<String, android.app.Person>()
        lines.forEach { l -> style.addMessage(l.text, l.time, people.getOrPut(l.sender) { android.app.Person.Builder().setName(l.sender).setKey(l.sender).build() }) }
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFFFF7A18.toInt())
            .setStyle(style)
            .setNumber(lines.size)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(if (channel == CH_REMINDERS) Notification.CATEGORY_REMINDER else Notification.CATEGORY_MESSAGE)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(notifId(key), n)
    }

    /** Drops a chat's notifications (it was opened). */
    fun clear(sid: String) {
        if (!::ctx.isInitialized) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        listOf(CH_REPLIES, CH_REMINDERS, CH_AGENTS).forEach { ch ->
            val k = "$ch:$sid"; nm.cancel(notifId(k)); synchronized(convs) { convs.remove(k) }
        }
    }

    private fun notifId(key: String) = 1000 + (key.hashCode() and 0x7fffffff) % 1_000_000

    /** Markdown → readable plain text for the notification shade. */
    fun plain(s: String): String = s
        .replace(Regex("```[a-zA-Z0-9]*\\n?"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        .trim()
}
