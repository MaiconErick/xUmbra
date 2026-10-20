package com.maiconerick.xumbra.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import com.maiconerick.xumbra.ui.theme.Spook
import kotlinx.coroutines.flow.MutableStateFlow

sealed class Screen {
    object Home : Screen()
    data class Chat(val sid: String) : Screen()
    object Settings : Screen()
    object Memory : Screen()
    object Agents : Screen()
    object Server : Screen()
    object Terminal : Screen()
    object Search : Screen()
    object Personas : Screen()
    object Tools : Screen()
    object Lab : Screen()
    object Vault : Screen()
    data class PersonaEditor(val name: String) : Screen()
}

/** Screen stack; the system back gesture pops it. */
object Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    /** Text / image shared from another app, waiting for the user to pick a chat. */
    val incomingShare = MutableStateFlow<Pair<String, List<Uri>>?>(null)
    /** Draft to drop into a chat's composer when it opens (from a share). */
    val pendingDraft = MutableStateFlow<Triple<String, String, List<Uri>>?>(null)

    fun go(s: Screen) { if (stack.lastOrNull() != s) stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun openChat(sid: String) {
        // replace an open chat instead of stacking chats on chats
        while (stack.size > 1 && stack.last() !is Screen.Home) stack.removeAt(stack.lastIndex)
        stack.add(Screen.Chat(sid))
    }
}

@Composable
fun Root() {
    val top = Nav.stack.lastOrNull() ?: Screen.Home
    BackHandler(Nav.stack.size > 1) { Nav.back() }
    Box(Modifier.fillMaxSize().background(Spook.skyBrush)) {
        AnimatedContent(top, label = "nav", transitionSpec = {
            val forward = Nav.stack.size > 1 && targetState == Nav.stack.last() && initialState != targetState
            if (targetState is Screen.Home || !forward) fadeIn() togetherWith fadeOut()
            else (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
        }) { s ->
            when (s) {
                Screen.Home -> Box(Modifier.fillMaxSize()) { HomeAmbient(); ChatListScreen() }
                is Screen.Chat -> ChatScreen(s.sid)
                Screen.Settings -> SettingsScreen()
                Screen.Memory -> MemoryScreen()
                Screen.Agents -> AgentsScreen()
                Screen.Server -> ServerScreen()
                Screen.Terminal -> TerminalScreen()
                Screen.Search -> SearchScreen()
                Screen.Personas -> PersonasScreen()
                Screen.Tools -> ToolsScreen()
                Screen.Lab -> LabPage(null) { Nav.back() }
                Screen.Vault -> VaultScreen()
                is Screen.PersonaEditor -> PersonaEditorScreen(s.name)
            }
        }
        val share by Nav.incomingShare.collectAsState()
        share?.let { SharePicker(it) { Nav.incomingShare.value = null } }
        BatPet()
    }
}
