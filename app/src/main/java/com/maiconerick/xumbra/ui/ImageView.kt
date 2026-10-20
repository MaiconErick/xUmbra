package com.maiconerick.xumbra.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.maiconerick.xumbra.App
import com.maiconerick.xumbra.data.Net
import com.maiconerick.xumbra.data.Umbra
import com.maiconerick.xumbra.ui.theme.Spook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** The chat the picture tools post into (set by ChatScreen); null = no chat actions (e.g. sample pictures). */
val LocalImageChat = compositionLocalOf<String?> { null }

// ───────────────────────── the tools ─────────────────────────

object ImageTools {
    /** /api/media/<name> → name (pictures stored on the Pi), else null (a web picture). */
    fun mediaName(url: String): String? = url.substringAfter("/api/media/", "").substringBefore('?').ifBlank { null }

    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { App.prefs.token.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") } }.build()
        Net.client.newCall(req).execute().use { it.body!!.bytes() }
    }

    /** A copy in cache/share, handed to other apps through the FileProvider. */
    suspend fun shareUri(ctx: Context, url: String, data: ByteArray? = null): Uri = withContext(Dispatchers.IO) {
        val b = data ?: bytes(url)
        val ext = if (b.size > 3 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte()) "png" else if (b.size > 2 && b[0] == 0xFF.toByte()) "jpg" else "png"
        val f = File(ctx.cacheDir, "share").apply { mkdirs() }.resolve("xumbra_${System.currentTimeMillis()}.$ext")
        f.writeBytes(b)
        FileProvider.getUriForFile(ctx, "com.maiconerick.xumbra.files", f)
    }

    fun share(ctx: Context, url: String, data: ByteArray? = null) = Umbra.launch {
        try {
            val uri = shareUri(ctx, url, data)
            val i = Intent(Intent.ACTION_SEND).setType(ctx.contentResolver.getType(uri) ?: "image/png")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, "Share picture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Umbra.banner.value = "Couldn't share: ${e.message}" }
    }

    fun openEditor(ctx: Context, url: String) = Umbra.launch {
        try {
            val uri = shareUri(ctx, url)
            val i = Intent(Intent.ACTION_EDIT).setDataAndType(uri, ctx.contentResolver.getType(uri) ?: "image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, "Edit with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Umbra.banner.value = "No editor app: ${e.message}" }
    }

    /** Ask Umbra (the chat's model) to change the picture: sends it with the request, like attaching it yourself. */
    fun askUmbra(ctx: Context, sid: String, url: String, request: String) = Umbra.launch {
        try {
            val uri = shareUri(ctx, url)
            Umbra.send(ctx, sid, request, listOf(uri), keep = true)
        } catch (e: Exception) { Umbra.banner.value = "Couldn't send: ${e.message}" }
    }

    /** Retro Diffusion's pixel-art fixer on the Pi: the result is posted in the chat (native PNG + a big copy). */
    fun pixelFix(sid: String, url: String, busy: MutableState<Boolean>? = null, onDone: () -> Unit = {}) = Umbra.launch {
        busy?.value = true
        Umbra.banner.value = "🧩 Fixing the pixel grid… (about 10 s)"
        try {
            val body = JSONObject().put("mode", "full")
            mediaName(url)?.let { body.put("media", it) } ?: body.put("url", url)
            val o = Net.post("/api/v2/sessions/$sid/pixelfix", body)
            Umbra.banner.value = "🧩 ${o.optInt("cols")}×${o.optInt("rows")} real pixels — posted in the chat"
            onDone()
        } catch (e: Exception) {
            Umbra.banner.value = "Pixel fix failed: " + (e.message?.substringAfter("\"detail\":\"")?.substringBefore('"') ?: "")
        } finally { busy?.value = false }
    }

    /** Upload a picture made on the phone (a crop) and put it in the chat — no AI reply. */
    fun postToChat(sid: String, png: ByteArray, text: String, onDone: () -> Unit = {}) = Umbra.launch {
        try {
            val name = Net.upload("/api/sessions/$sid/media", "crop.png", "image/png", png).optString("filename")
            Net.post("/api/v2/sessions/$sid/post-image", JSONObject().put("media", name).put("text", text))
            Umbra.banner.value = "Posted in the chat"
            onDone()
        } catch (e: Exception) { Umbra.banner.value = "Couldn't post: ${e.message}" }
    }
}

// ───────────────────────── actions on one picture (viewer bar + long-press menu) ─────────────────────────

class ImageActions(val url: String) {
    var menu by mutableStateOf(false)
    var crop by mutableStateOf(false)
    var edit by mutableStateOf(false)
    val busy = mutableStateOf(false)
}

@Composable
fun rememberImageActions(url: String) = remember(url) { ImageActions(url) }

/** The pop-ups an [ImageActions] can open; [onDone] closes the viewer after something was posted. */
@Composable
fun ImageActionsHost(a: ImageActions, onDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    val sid = LocalImageChat.current
    DropdownMenu(a.menu, { a.menu = false }, containerColor = Spook.Crypt2) {
        @Composable fun item(icon: ImageVector, label: String, enabled: Boolean = true, go: () -> Unit) =
            DropdownMenuItem(text = { Text(label, color = if (enabled) Spook.Bone else Spook.Fog) }, enabled = enabled,
                leadingIcon = { Icon(icon, null, tint = Spook.Mist) }, onClick = { a.menu = false; go() })
        item(Icons.Outlined.Share, "Share") { ImageTools.share(ctx, a.url) }
        item(Icons.Outlined.Edit, "Edit…") { a.edit = true }
        item(Icons.Outlined.Crop, "Crop", sid != null) { a.crop = true }
        item(Icons.Outlined.GridOn, "Pixel-art fix", sid != null && !a.busy.value) { sid?.let { ImageTools.pixelFix(it, a.url, a.busy, onDone) } }
        item(Icons.Outlined.Download, "Save to phone") { MediaSave.save(ctx, a.url) }
    }
    if (a.edit) EditDialog(a, sid, onDone)
    if (a.crop && sid != null) CropPage(a.url, sid, onClose = { a.crop = false }, onPosted = onDone)
}

@Composable
private fun EditDialog(a: ImageActions, sid: String?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var ask by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { a.edit = false }, containerColor = Spook.Crypt2,
        title = { Text("Edit picture", color = Spook.Bone) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sid != null) {
                    Text("Ask Umbra to change it (uses this chat's image model):", color = Spook.Mist, fontSize = 13.sp)
                    Field(ask, "e.g. make the hair red, add a hat") { ask = it }
                }
                Text("Open in another app", color = Spook.Pumpkin, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { a.edit = false; ImageTools.openEditor(ctx, a.url) }.padding(vertical = 6.dp))
            }
        },
        confirmButton = {
            if (sid != null) TextButton(enabled = ask.isNotBlank(), onClick = {
                a.edit = false; ImageTools.askUmbra(ctx, sid, a.url, "Edit this picture: ${ask.trim()}"); onDone()
            }) { Text("Send to Umbra", color = Spook.Pumpkin) }
        },
        dismissButton = { TextButton(onClick = { a.edit = false }) { Text("Cancel", color = Spook.Mist) } })
}

/** A picture in the chat: tap opens the viewer, long press opens Share / Edit / Crop / Pixel-art fix / Save. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PictureWithActions(url: String, onOpen: ((String) -> Unit)?, content: @Composable (Modifier) -> Unit) {
    val a = rememberImageActions(url)
    Box {
        content(Modifier.combinedClickable(onClick = { onOpen?.invoke(url) }, onLongClick = { a.menu = true }))
        ImageActionsHost(a)
    }
}

// ───────────────────────── the viewer ─────────────────────────

/**
 * Full-screen picture, pixel-perfect: decoded at full size (no resampling) and drawn with
 * nearest-neighbour (no blending). Small pictures are zoomed by whole numbers so every pixel is
 * an even square. Tap = show/hide controls, double-tap = zoom, pinch/drag = zoom/move.
 */
@Composable
fun ImageViewer(url: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val sid = LocalImageChat.current
    val a = rememberImageActions(url)
    var controls by remember { mutableStateOf(true) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(ctx).data(url).size(coil.size.Size.ORIGINAL).allowHardware(false).apply {
            App.prefs.token.takeIf { it.isNotEmpty() }?.let { addHeader("Authorization", "Bearer $it") }
        }.build(),
        filterQuality = FilterQuality.None)
    val isz = painter.intrinsicSize
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
            // the painter's size is "unspecified" until the picture has loaded — reading it then throws
            val known = isz != Size.Unspecified && isz.width > 0f && isz.height > 0f && isz.width.isFinite()
            val fit = if (known) min(w / isz.width, h / isz.height) else 1f
            // whole-number scale whenever the picture is shown at 1× or more: pixels stay square and equal
            val base = if (fit >= 1f) floor(fit) else fit
            val total = (base * zoom).let { if (it >= 1f) it.roundToInt().toFloat() else it }
            Image(painter, null, contentScale = androidx.compose.ui.layout.FixedScale(total),
                modifier = Modifier.fillMaxSize().graphicsLayer { translationX = tx; translationY = ty }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { controls = !controls },
                            onDoubleTap = { if (zoom > 1.01f) { zoom = 1f; tx = 0f; ty = 0f } else zoom = 2f })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, z, _ -> zoom = (zoom * z).coerceIn(1f, 48f); tx += pan.x; ty += pan.y }
                    })
            AnimatedVisibility(controls, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.fillMaxWidth().background(Color(0x99000000)).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close", tint = Color.White) }
                    if (known) Text("${isz.width.toInt()}×${isz.height.toInt()}  ·  ${if (total >= 1f) "${total.toInt()}×" else "${(total * 100).toInt()}%"}",
                        color = Spook.Mist, fontSize = 13.sp)
                }
            }
            AnimatedVisibility(controls, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding().padding(top = 8.dp, bottom = 22.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    BarButton(Icons.Outlined.Share, "Share") { ImageTools.share(ctx, url) }
                    BarButton(Icons.Outlined.Edit, "Edit") { a.edit = true }
                    if (sid != null) BarButton(Icons.Outlined.Crop, "Crop") { a.crop = true }
                    if (sid != null) BarButton(Icons.Outlined.GridOn, if (a.busy.value) "Fixing…" else "Pixel fix", enabled = !a.busy.value) {
                        ImageTools.pixelFix(sid, url, a.busy, onClose)
                    }
                    BarButton(Icons.Outlined.Download, "Save") { MediaSave.save(ctx, url) }
                }
            }
            ImageActionsHost(a, onDone = onClose)
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = if (enabled) Color.White else Spook.Fog)
        Text(label, color = if (enabled) Spook.Bone else Spook.Fog, fontSize = 11.sp)
    }
}

// ───────────────────────── crop ─────────────────────────

/** Drag the corners/edges (or the middle to move); it snaps to whole pixels. Send to the chat or share. */
@Composable
private fun CropPage(url: String, sid: String, onClose: () -> Unit, onPosted: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        try { val b = ImageTools.bytes(url); bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(b, 0, b.size) } }
        catch (e: Exception) { Umbra.banner.value = "Couldn't load: ${e.message}"; onClose() }
    }
    var l by remember { mutableFloatStateOf(0f) }; var t by remember { mutableFloatStateOf(0f) }
    var r by remember { mutableFloatStateOf(1f) }; var b by remember { mutableFloatStateOf(1f) }   // fractions of the picture
    val handle = with(LocalDensity.current) { 28.dp.toPx() }
    fun cropped(): ByteArray? {
        val src = bmp ?: return null
        val x0 = (l * src.width).roundToInt().coerceIn(0, src.width - 1); val y0 = (t * src.height).roundToInt().coerceIn(0, src.height - 1)
        val x1 = (r * src.width).roundToInt().coerceIn(x0 + 1, src.width); val y1 = (b * src.height).roundToInt().coerceIn(y0 + 1, src.height)
        val out = Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0)
        return ByteArrayOutputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Cancel", tint = Color.White) }
                Text("Crop", color = Spook.Bone, fontSize = 18.sp, modifier = Modifier.weight(1f))
                bmp?.let { s ->
                    Text("${((r - l) * s.width).roundToInt()}×${((b - t) * s.height).roundToInt()}", color = Spook.Mist, fontSize = 13.sp)
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                val src = bmp
                if (src == null) { CircularProgressIndicator(color = Spook.Pumpkin); return@BoxWithConstraints }
                val img = remember(src) { src.asImageBitmap() }
                val fit = min(constraints.maxWidth.toFloat() / src.width, constraints.maxHeight.toFloat() / src.height)
                val sc = if (fit >= 1f) floor(fit) else fit
                val dw = src.width * sc; val dh = src.height * sc
                val snapX = 1f / src.width; val snapY = 1f / src.height
                var mode by remember { mutableStateOf("") }
                with(LocalDensity.current) {
                    Canvas(Modifier.size(dw.toDp(), dh.toDp()).pointerInput(src) {
                        detectDragGestures(onDragStart = { p ->
                            val px = p.x / dw; val py = p.y / dh; val hx = handle / dw; val hy = handle / dh
                            val nl = abs(px - l) < hx; val nr = abs(px - r) < hx; val nt = abs(py - t) < hy; val nb = abs(py - b) < hy
                            mode = buildString { if (nl) append('l'); if (nr) append('r'); if (nt) append('t'); if (nb) append('b') }
                                .ifEmpty { if (px in l..r && py in t..b) "move" else "" }
                        }) { change, d ->
                            change.consume()
                            val dx = d.x / dw; val dy = d.y / dh
                            if (mode == "move") {
                                val wv = r - l; val hv = b - t
                                l = (l + dx).coerceIn(0f, 1f - wv); t = (t + dy).coerceIn(0f, 1f - hv); r = l + wv; b = t + hv
                            } else {
                                if ('l' in mode) l = (l + dx).coerceIn(0f, r - snapX)
                                if ('r' in mode) r = (r + dx).coerceIn(l + snapX, 1f)
                                if ('t' in mode) t = (t + dy).coerceIn(0f, b - snapY)
                                if ('b' in mode) b = (b + dy).coerceIn(t + snapY, 1f)
                            }
                        }
                    }) {
                        drawImage(img, dstSize = IntSize(dw.roundToInt(), dh.roundToInt()), filterQuality = FilterQuality.None)
                        // snap to whole pixels for the frame
                        fun sx(f: Float) = (f * src.width).roundToInt() * sc
                        fun sy(f: Float) = (f * src.height).roundToInt() * sc
                        val dim = Color(0xAA000000)
                        drawRect(dim, Offset.Zero, Size(dw, sy(t)))
                        drawRect(dim, Offset(0f, sy(b)), Size(dw, dh - sy(b)))
                        drawRect(dim, Offset(0f, sy(t)), Size(sx(l), sy(b) - sy(t)))
                        drawRect(dim, Offset(sx(r), sy(t)), Size(dw - sx(r), sy(b) - sy(t)))
                        drawRect(Spook.Pumpkin, Offset(sx(l), sy(t)), Size(sx(r) - sx(l), sy(b) - sy(t)), style = Stroke(3f))
                        listOf(sx(l) to sy(t), sx(r) to sy(t), sx(l) to sy(b), sx(r) to sy(b)).forEach { (x, y) ->
                            drawCircle(Spook.Pumpkin, 12f, Offset(x, y))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 26.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                Pill("Share", false) { cropped()?.let { ImageTools.share(ctx, url, it) } }
                Pill("Send to chat", true) {
                    cropped()?.let { png -> ImageTools.postToChat(sid, png, "✂️ Cropped picture") { onClose(); onPosted() } }
                }
            }
        }
    }
}
