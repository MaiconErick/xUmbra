@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.maiconerick.xumbra.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.data.ToolCall
import com.maiconerick.xumbra.ui.theme.Spook

/**
 * Tool call rendering for the chat transcript.
 *
 * Layout rules:
 * - One row per call: status dot, the plain-English sentence of what ran, the elapsed time.
 * - The command/arguments are always visible but clamped to two lines.
 * - The output only exists after the call finishes, and it opens with a single tap.
 * - Height changes are animated so a card never snaps open or shut.
 *
 * The collapsed/expanded state is keyed on (callId, result) so a card that receives its
 * output mid-stream does not collapse itself under the user's finger.
 */
@Composable
fun ToolCallCards(tools: List<ToolCall>, running: String? = null) {
    if (tools.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tools.forEach { c -> ToolRow(c, running != null) }
    }
}

@Composable
private fun ToolRow(c: ToolCall, turnLive: Boolean) {
    val pending = c.result == null && turnLive
    val failed = c.result?.let { r ->
        r.startsWith("ERROR") || r.startsWith("BLOCKED") || r.startsWith("[exit 1") || r.contains("\"error\"")
    } == true

    var open by remember(c.callId, c.result) { mutableStateOf(false) }
    val hasOutput = !c.result.isNullOrBlank()

    val dotColor = when {
        pending -> Spook.Moon
        failed -> Color(0xFFE06C6C)
        else -> Color(0xFF6CC08B)
    }
    val pulse = rememberInfiniteTransition(label = "tool_dot")
    val dotAlpha by pulse.animateFloat(
        initialValue = if (pending) 0.35f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "tool_dot_alpha",
    )
    val chevron by animateFloatAsState(if (open) 90f else 0f, tween(180), label = "tool_chevron")

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Spook.Crypt3.copy(alpha = 0.55f))
            .then(if (hasOutput) Modifier.clickable { open = !open } else Modifier)
            .animateContentSize()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // The sentence the model wrote right before this step ("I'll flip the
        // boolean now") belongs to the process, not to the final answer.
        c.text?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                color = Spook.Bone.copy(alpha = 0.85f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolChip(c.name, pending = pending, failed = failed)
            Spacer(Modifier.width(6.dp))
            if (c.note.isNullOrBlank()) {
                Spacer(
                    Modifier
                        .size(6.dp)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                c.note ?: c.name,
                color = if (pending) Spook.Moon else Spook.Bone,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (c.ms != null) {
                Spacer(Modifier.width(6.dp))
                Text(Fmt.secs(c.ms / 1000.0), color = Spook.Mist, fontSize = 10.sp)
            }
            if (hasOutput) {
                Spacer(Modifier.width(4.dp))
                Text(
                    "\u203A",
                    color = Spook.Mist,
                    fontSize = 12.sp,
                    modifier = Modifier.alpha(if (open) 1f else 0.7f),
                )
            }
        }

        if (c.args.isNotBlank()) {
            Text(
                c.args,
                color = Spook.Moon,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = if (open) 40 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (open && hasOutput) {
            SelectionContainer {
                Text(
                    c.result.orEmpty(),
                    color = Spook.Mist,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** Which tool ran: a small labeled chip so the row is readable without opening anything. */
@Composable
private fun ToolChip(name: String, pending: Boolean, failed: Boolean) {
    val (label, icon) = toolLook(name)
    val base = when {
        failed -> Color(0xFFE06C6C)
        pending -> Spook.Moon
        else -> toolColor(name)
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(base.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = base, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            label,
            color = base,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 92.dp),
        )
    }
}

private fun toolLook(n: String): Pair<String, androidx.compose.ui.graphics.vector.ImageVector> = when {
    n == "shell" || n == "bash" -> "SHELL" to Icons.Filled.Terminal
    n == "python" -> "PYTHON" to Icons.Filled.Code
    n == "read_file" || n == "list_dir" -> "FILE READ" to Icons.Filled.Description
    n == "write_file" -> "FILE WRITE" to Icons.Filled.Save
    n == "edit_file" -> "EDIT" to Icons.Filled.Edit
    n == "grep" -> "SEARCH" to Icons.Filled.FindInPage
    n == "glob" -> "FIND" to Icons.Filled.FolderOpen
    n == "repo_map" -> "CODE MAP" to Icons.Filled.AccountTree
    n == "run" -> "BUILD" to Icons.Filled.PlayArrow
    n == "todo" -> "TODO" to Icons.Filled.Checklist
    n == "jobs" -> "JOB" to Icons.Filled.Schedule
    n == "task" -> "SUBAGENT" to Icons.Filled.SmartToy
    n.startsWith("memory") -> "MEMORY" to Icons.Filled.Psychology
    n == "web_search" -> "WEB" to Icons.Filled.Search
    n == "find_images" -> "IMAGES" to Icons.Filled.Image
    n == "generate_image" -> "IMG GEN" to Icons.Filled.Brush
    n == "load_tools" -> "TOOLS" to Icons.Filled.Build
    n == "browser" -> "BROWSER" to Icons.Filled.Language
    n == "tts" -> "SPEECH" to Icons.Filled.VolumeUp
    n == "agents" -> "AGENT" to Icons.Filled.Schedule
    n == "network" -> "NETWORK" to Icons.Filled.Wifi
    n == "system_snapshot" -> "SYSTEM" to Icons.Filled.MonitorHeart
    else -> n.uppercase().take(12) to Icons.Filled.Bolt
}

private fun toolColor(n: String): Color = when {
    n == "shell" || n == "bash" || n == "python" || n == "run" -> Spook.Slime
    n == "read_file" || n == "list_dir" || n == "write_file" || n == "edit_file" -> Spook.Witch
    n == "grep" || n == "glob" || n == "repo_map" -> Spook.Moon
    n == "todo" -> Color(0xFF6CC08B)
    n == "jobs" || n == "task" -> Spook.Pumpkin
    n.startsWith("memory") -> Spook.Moon
    n == "web_search" || n == "browser" -> Color(0xFF6FB6FF)
    n == "find_images" || n == "generate_image" -> Spook.Pumpkin
    n == "load_tools" || n == "agents" -> Spook.Moon
    else -> Spook.Pumpkin
}
