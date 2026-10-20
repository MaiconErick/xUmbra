package com.maiconerick.xumbra

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.maiconerick.xumbra.data.Hub
import com.maiconerick.xumbra.data.Umbra
import com.maiconerick.xumbra.service.HubService
import com.maiconerick.xumbra.ui.Nav
import com.maiconerick.xumbra.ui.Root
import com.maiconerick.xumbra.ui.Screen
import com.maiconerick.xumbra.ui.theme.SpookTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Umbra.foreground = true
                Hub.start()
                Umbra.refreshSessions()
                Umbra.visibleSid?.let { Umbra.markRead(it) }
            }
            override fun onStop(owner: LifecycleOwner) {
                Umbra.foreground = false
                // without the background connection the socket only lives while the app is open
                if (!App.prefs.background) Hub.stop()
            }
        })
        handle(intent)
        setContent { SpookTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /**
     * - notification tap: "chat" = sid
     * - adb provisioning: am start -n com.maiconerick.xumbra/.MainActivity --es lan 192.168.1.128:8765 --es token <t>
     * - share from another app: text/images become the draft of a new chat (picked in the app)
     */
    private fun handle(i: Intent?) {
        i ?: return
        var changed = false
        i.getStringExtra("lan")?.let { App.prefs.lanHost = it; changed = true }
        i.getStringExtra("tailscale")?.let { App.prefs.tailscaleHost = it; changed = true }
        i.getStringExtra("token")?.let { App.prefs.token = it; changed = true }
        if (changed) { Hub.restart(); HubService.sync(this) }
        i.getStringExtra("chat")?.let { Nav.openChat(it) }
        if (i.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            @Suppress("DEPRECATION")
            val img: Uri? = i.getParcelableExtra(Intent.EXTRA_STREAM)
            if (text.isNotBlank() || img != null) Nav.incomingShare.value = text to listOfNotNull(img)
            if (Nav.stack.isEmpty()) Nav.stack.add(Screen.Home)
        }
    }
}
