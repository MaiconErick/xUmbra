package com.maiconerick.xumbra.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.maiconerick.xumbra.App
import com.maiconerick.xumbra.MainActivity
import com.maiconerick.xumbra.R
import com.maiconerick.xumbra.data.Conn
import com.maiconerick.xumbra.data.Hub
import com.maiconerick.xumbra.data.Notifier
import com.maiconerick.xumbra.data.Umbra
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the hub connection open while the app is closed so replies and
 * Umbra's reminders arrive as notifications right away (no polling).
 */
class HubService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = note("Connecting…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        Hub.start()
        scope.launch {
            combine(Hub.state, Umbra.sessions) { st, rows -> st to rows.count { it.busy } }.collect { (st, busy) ->
                val text = when (st) {
                    Conn.Online -> if (busy > 0) "Umbra is answering in $busy chat" + (if (busy > 1) "s" else "") else "Listening for replies and reminders"
                    Conn.Connecting -> "Connecting…"
                    Conn.Offline -> "Can't reach the Pi — retrying"
                    Conn.Idle -> "Paused"
                }
                getSystemService(android.app.NotificationManager::class.java).notify(1, note(text))
            }
        }
    }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("xUmbra")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        if (!Umbra.foreground) Hub.stop()
        super.onDestroy()
    }

    companion object {
        /** Background connection on → service runs; off → only while the app is open. */
        fun sync(ctx: Context) {
            val i = Intent(ctx, HubService::class.java)
            if (App.prefs.background) {
                try { ctx.startForegroundService(i) } catch (_: Exception) { }
            } else ctx.stopService(i)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) HubService.sync(context)
    }
}
