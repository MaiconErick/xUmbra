package com.maiconerick.xumbra.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maiconerick.xumbra.data.Money
import com.maiconerick.xumbra.ui.theme.Cobweb
import com.maiconerick.xumbra.ui.theme.Spook
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ───────────── layout pieces ─────────────

@Composable
fun Section(title: String, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Spook.cardBrush).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Spook.Pumpkin, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        sub?.let { Text(it, color = Spook.Mist, fontSize = 12.sp) }
        content()
    }
}

@Composable
fun Toggle(label: String, on: Boolean, sub: String? = null, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { set(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Spook.Bone, fontSize = 15.sp)
            sub?.let { Text(it, color = Spook.Mist, fontSize = 12.sp) }
        }
        Switch(on, set, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Spook.Pumpkin, uncheckedTrackColor = Spook.Crypt3))
    }
}

@Composable
fun Choice(options: List<Pair<String, String>>, value: String, set: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        options.forEach { (k, label) -> Pill(label, k == value) { set(k) } }
    }
}

@Composable
fun Pill(label: String, on: Boolean, small: Boolean = false, color: Color = Spook.Pumpkin, onClick: () -> Unit) {
    Text(label, color = if (on) Color.Black else Spook.Bone, fontSize = if (small) 12.sp else 13.sp, maxLines = 1,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) color else Spook.Crypt3).clickable(onClick = onClick)
            .padding(horizontal = if (small) 10.dp else 12.dp, vertical = if (small) 4.dp else 6.dp))
}

@Composable
fun SmallField(value: String, width: Int, numeric: Boolean = false, set: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    BasicTextField(text, { text = it; set(it) }, singleLine = true,
        textStyle = TextStyle(color = Spook.Bone, fontSize = 14.sp),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        cursorBrush = SolidColor(Spook.Pumpkin),
        modifier = Modifier.width(width.dp).clip(RoundedCornerShape(8.dp)).background(Spook.Crypt3).padding(horizontal = 8.dp, vertical = 6.dp))
}

/** Outlined text field in the theme colours. */
@Composable
fun Field(value: String, label: String, modifier: Modifier = Modifier.fillMaxWidth(), singleLine: Boolean = true, minLines: Int = 1,
          mono: Boolean = false, numeric: Boolean = false, placeholder: String? = null, set: (String) -> Unit) {
    OutlinedTextField(value, set, label = { Text(label) }, singleLine = singleLine, minLines = minLines, modifier = modifier,
        placeholder = placeholder?.let { { Text(it, color = Spook.Fog) } },
        textStyle = TextStyle(fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, fontSize = if (mono) 13.sp else 15.sp),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        colors = fieldColors())
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Spook.Pumpkin, unfocusedBorderColor = Spook.Fog, cursorColor = Spook.Pumpkin,
    focusedTextColor = Spook.Bone, unfocusedTextColor = Spook.Bone, focusedLabelColor = Spook.Pumpkin, unfocusedLabelColor = Spook.Mist,
)

@Composable
fun UsageBar(used: Double, total: Double, color: Color = Spook.Pumpkin, height: Int = 8) {
    val f = if (total <= 0) 0f else (used / total).toFloat().coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(4.dp)).background(Spook.Crypt3)) {
        Box(Modifier.fillMaxWidth(f).height(height.dp).clip(RoundedCornerShape(4.dp)).background(if (f > 0.9f) Spook.Blood else color))
    }
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = Spook.Pumpkin, contentColor = Color.Black)) { Text(text) }
}

@Composable
fun GhostButton(text: String, color: Color = Spook.Bone, onClick: () -> Unit) {
    Button(onClick, colors = ButtonDefaults.buttonColors(containerColor = Spook.Crypt3, contentColor = color)) { Text(text) }
}

/** Screen title bar: back arrow, Creepster title, optional actions; a cobweb in the corner. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().background(Spook.Crypt).statusBarsPadding()) {
        Cobweb(48.dp, right = true, modifier = Modifier.align(Alignment.TopEnd))
        Row(Modifier.fillMaxWidth().height(58.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Spook.Bone) }
            else Spacer(Modifier.width(14.dp))
            Text(title, fontFamily = Spook.Creepy, fontSize = 28.sp, color = Spook.Pumpkin, maxLines = 1, modifier = Modifier.weight(1f))
            actions()
        }
    }
}

@Composable
fun Tabs(tabs: List<String>, tab: Int, set: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { i, t ->
            val on = i == tab
            Text(t, color = if (on) Color.Black else Spook.Bone, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Spook.pumpkinBrush else Brush.linearGradient(listOf(Spook.Crypt2, Spook.Crypt2)))
                    .clickable { set(i) }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@Composable
fun Banner(text: String?, onDismiss: () -> Unit) {
    AnimatedVisibility(text != null) {
        Text(text ?: "", color = Spook.Bone, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().background(Color(0x40FF2E4D)).clickable { onDismiss() }.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

/** Full-screen page that slides over the current one (sheets for chat settings, pickers, editors). */
@Composable
fun Page(title: String, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { onClose() }
        Column(Modifier.fillMaxSize().background(Spook.skyBrush).imePadding()) {
            ScreenHeader(title, onClose, actions)
            Column(Modifier.fillMaxSize().navigationBarsPadding(), content = content)
        }
    }
}

@Composable
fun Confirm(title: String, text: String, yes: String, danger: Boolean = false, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(onDismissRequest = onNo, containerColor = Spook.Crypt2,
        title = { Text(title, color = Spook.Bone) }, text = { Text(text, color = Spook.Mist) },
        confirmButton = { TextButton(onClick = onYes) { Text(yes, color = if (danger) Spook.Blood else Spook.Pumpkin) } },
        dismissButton = { TextButton(onClick = onNo) { Text("Cancel", color = Spook.Mist) } })
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun Dot(color: Color, size: Int = 8) = Box(Modifier.size(size.dp).clip(CircleShape).background(color))

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Spook.Bone) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Spook.Crypt3.copy(alpha = 0.6f)).border(1.dp, Spook.Crypt3, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
        Text(label, color = Spook.Mist, fontSize = 11.sp, maxLines = 1)
    }
}

// ───────────── helpers ─────────────

fun copyText(ctx: Context, text: String, label: String = "xUmbra") {
    try { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text)) } catch (_: Exception) { }
}

fun shareText(ctx: Context, text: String) {
    try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
}

fun openUrl(ctx: Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
}

object Fmt {
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dm = SimpleDateFormat("d MMM", Locale.getDefault())
    private val dmy = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    private val full = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss", Locale.getDefault())

    fun time(ts: Double) = hm.format(Date((ts * 1000).toLong()))
    fun stamp(ts: Double) = full.format(Date((ts * 1000).toLong()))

    /** Chat list: today → 14:05, this week → Mon, older → 3 Oct. */
    fun listTime(ts: Double): String {
        if (ts <= 0) return ""
        val d = Date((ts * 1000).toLong())
        val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { time = d }
        return when {
            sameDay(now, c) -> hm.format(d)
            now.timeInMillis - c.timeInMillis < 6L * 86_400_000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(d)
            now.get(Calendar.YEAR) == c.get(Calendar.YEAR) -> dm.format(d)
            else -> dmy.format(d)
        }
    }

    /** Day separator inside a chat. */
    fun day(ts: Double): String {
        val d = Date((ts * 1000).toLong())
        val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { time = d }
        val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            sameDay(now, c) -> "Today"
            sameDay(y, c) -> "Yesterday"
            now.get(Calendar.YEAR) == c.get(Calendar.YEAR) -> SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(d)
            else -> dmy.format(d)
        }
    }

    fun dayKey(ts: Double): Int = Calendar.getInstance().apply { time = Date((ts * 1000).toLong()) }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

    private fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    fun money(v: Double): String = when {
        v <= 0 -> "$0"
        v < 0.01 -> "$" + String.format(Locale.US, "%.4f", v) + brl(v)
        v < 1 -> "$" + String.format(Locale.US, "%.3f", v) + brl(v)
        else -> "$" + String.format(Locale.US, "%.2f", v) + brl(v)
    }

    /** A plain two-decimal dollar amount (wallet, credits) with its R$ value. */
    fun usd(v: Double): String =
        "$" + String.format(Locale.US, "%.2f", v) + brl(v)

    /**
     * " (R$ 1.35)" — the R$ equivalent of a dollar amount, empty while the
     * rate is unknown or the amount is zero. Always two decimals: reais are
     * read as money, not as an estimate of a cent.
     */
    fun brl(v: Double): String {
        val rate = Money.brlRate
        if (rate <= 0 || v <= 0) return ""
        return " (R$ " + String.format(Locale.US, "%.2f", v * rate) + ")"
    }

    fun tokens(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
        n >= 10_000 -> "${n / 1000}k"
        n >= 1000 -> String.format(Locale.US, "%.1fk", n / 1e3)
        else -> n.toString()
    }

    fun bytes(n: Long): String = when {
        n >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", n / 1073741824.0)
        n >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", n / 1048576.0)
        n >= 1024 -> String.format(Locale.US, "%.0f KB", n / 1024.0)
        else -> "$n B"
    }

    fun secs(s: Double): String = when {
        s < 1 -> "${(s * 1000).toInt()} ms"
        s < 60 -> String.format(Locale.US, "%.1f s", s)
        s < 3600 -> "${(s / 60).toInt()} min ${(s % 60).toInt()} s"
        s < 86400 -> "${(s / 3600).toInt()} h ${((s % 3600) / 60).toInt()} min"
        else -> "${(s / 86400).toInt()} d ${((s % 86400) / 3600).toInt()} h"
    }

    fun ago(ts: Double): String {
        val s = System.currentTimeMillis() / 1000.0 - ts
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${(s / 60).toInt()} min ago"
            s < 86400 -> "${(s / 3600).toInt()} h ago"
            else -> "${(s / 86400).toInt()} d ago"
        }
    }
}
