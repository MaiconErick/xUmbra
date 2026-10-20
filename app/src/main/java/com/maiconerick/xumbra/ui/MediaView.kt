@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maiconerick.xumbra.ui

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maiconerick.xumbra.App
import com.maiconerick.xumbra.data.Net
import com.maiconerick.xumbra.ui.theme.Spook
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Size / dimensions / length of chat media, fetched once per file from the Pi. */
object MediaMeta {
    val info = mutableStateMapOf<String, JSONObject>()
    private val asked = HashSet<String>()
    suspend fun ensure(names: List<String>) {
        val missing = names.filter { it !in info && asked.add(it) }
        if (missing.isEmpty()) return
        try {
            val o = Net.get("/api/v2/media/info?names=" + Net.enc(missing.joinToString(","))).optJSONObject("media") ?: return
            o.keys().forEach { k -> o.optJSONObject(k)?.let { info[k] = it } }
        } catch (_: Exception) { missing.forEach { asked.remove(it) } }
    }
}

private fun kindOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "avif" -> "image"
    "mp3", "ogg", "opus", "wav", "m4a", "aac", "flac" -> "audio"
    "mp4", "webm", "mov", "mkv", "3gp" -> "video"
    else -> "file"
}

private fun dur(s: Double): String { val t = s.toInt(); return "%d:%02d".format(t / 60, t % 60) }

/** "1440×3088 · 1.7 MB · JPEG" / "0:42 · 680 KB · MP3" */
fun mediaCaption(o: JSONObject?): String {
    o ?: return ""
    val bits = mutableListOf<String>()
    if (o.has("width")) bits += "${o.optInt("width")}×${o.optInt("height")}"
    if (o.optDouble("duration", 0.0) > 0) bits += dur(o.optDouble("duration"))
    if (o.has("size")) bits += Fmt.bytes(o.optLong("size"))
    o.optString("format").takeIf { it.isNotBlank() }?.let { bits += it }
    if (o.optInt("frames", 1) > 1) bits += "${o.optInt("frames")} frames"
    return bits.joinToString(" · ")
}

@Composable
fun MediaBlock(names: List<String>, onImage: (String) -> Unit) {
    LaunchedEffect(names) { MediaMeta.ensure(names) }
    val images = names.filter { kindOf(it) == "image" }
    val others = names.filter { kindOf(it) != "image" }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (images.size == 1) {
            val n = images[0]
            PictureWithActions(Net.mediaUrl(n), onImage) { m ->
                AsyncImage(Net.mediaUrl(n), null, contentScale = ContentScale.FillWidth, filterQuality = smallFilter(n),
                    modifier = Modifier.widthIn(max = 300.dp).heightIn(max = 360.dp).clip(RoundedCornerShape(10.dp)).then(m))
            }
            Caption(MediaMeta.info[n])
        } else if (images.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                images.forEach { n ->
                    Box {
                        PictureWithActions(Net.mediaUrl(n), onImage) { m ->
                            AsyncImage(Net.mediaUrl(n), null, contentScale = ContentScale.Crop, filterQuality = smallFilter(n),
                                modifier = Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)).then(m))
                        }
                        MediaMeta.info[n]?.let { o ->
                            Text("${o.optInt("width")}×${o.optInt("height")} · ${Fmt.bytes(o.optLong("size"))}", color = Color.White, fontSize = 9.5.sp,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    }
                }
            }
        }
        others.forEach { n ->
            when (kindOf(n)) {
                "audio" -> AudioRow(n)
                "video" -> VideoRow(n)
                else -> FileRow(n)
            }
        }
    }
}

/** Tiny pictures (real pixel art) are drawn without smoothing in the chat too. */
private fun smallFilter(n: String): androidx.compose.ui.graphics.FilterQuality {
    val o = MediaMeta.info[n] ?: return androidx.compose.ui.graphics.FilterQuality.Low
    return if (maxOf(o.optInt("width"), o.optInt("height")) in 1..256) androidx.compose.ui.graphics.FilterQuality.None
           else androidx.compose.ui.graphics.FilterQuality.Low
}

@Composable
private fun Caption(o: JSONObject?) {
    val c = mediaCaption(o)
    if (c.isNotBlank()) Text(c, color = Spook.Mist, fontSize = 10.5.sp)
}

/** Inline player for voice/TTS/music files. */
@Composable
private fun AudioRow(name: String) {
    val o = MediaMeta.info[name]
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(0) }
    var len by remember { mutableIntStateOf(((o?.optDouble("duration", 0.0) ?: 0.0) * 1000).toInt()) }
    LaunchedEffect(o) { if (len == 0) len = ((o?.optDouble("duration", 0.0) ?: 0.0) * 1000).toInt() }
    LaunchedEffect(playing) { while (playing) { player?.let { pos = it.currentPosition }; delay(200) } }
    DisposableEffect(name) { onDispose { player?.release(); player = null } }
    val ctx = LocalContext.current
    fun toggle() {
        val p = player
        if (p == null) {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            val headers = App.prefs.token.takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
            mp.setDataSource(ctx, Uri.parse(Net.mediaUrl(name)), headers)
            mp.setOnPreparedListener { it.start(); playing = true; if (len == 0) len = it.duration }
            mp.setOnCompletionListener { playing = false; pos = 0 }
            mp.prepareAsync()
            player = mp
        } else if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
    }
    Row(Modifier.widthIn(min = 220.dp, max = 300.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x44000000)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Spook.Pumpkin).clickable { toggle() }, contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.Black)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            UsageBar(pos.toDouble(), len.toDouble().coerceAtLeast(1.0), Spook.Pumpkin, 5)
            Text((if (pos > 0) dur(pos / 1000.0) + " / " else "") + mediaCaption(o).ifBlank { "audio" }, color = Spook.Mist, fontSize = 10.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun VideoRow(name: String) {
    val ctx = LocalContext.current
    Row(Modifier.widthIn(min = 220.dp, max = 300.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x44000000))
        .clickable {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(Net.mediaUrl(name)), "video/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { openUrl(ctx, Net.mediaUrl(name)) }
        }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🎬", fontSize = 22.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Video — tap to play", color = Spook.Bone, fontSize = 13.sp)
            Text(mediaCaption(MediaMeta.info[name]), color = Spook.Mist, fontSize = 10.5.sp)
        }
    }
}

@Composable
private fun FileRow(name: String) {
    val ctx = LocalContext.current
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x44000000)).clickable { openUrl(ctx, Net.mediaUrl(name)) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("📄", fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name.takeLast(28), color = Spook.Bone, fontSize = 12.sp)
            Text(mediaCaption(MediaMeta.info[name]), color = Spook.Mist, fontSize = 10.5.sp)
        }
    }
}
