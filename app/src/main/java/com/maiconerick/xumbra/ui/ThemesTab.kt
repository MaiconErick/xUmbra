package com.maiconerick.xumbra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.App
import com.maiconerick.xumbra.ui.theme.Palettes
import com.maiconerick.xumbra.ui.theme.Spook
import com.maiconerick.xumbra.ui.theme.SpookPalette
import org.json.JSONObject

// Themes, background animations, bat pet and the custom colour editor — the same as xWapp's 🎃 Themes tab.

@Composable
fun ThemesTab() {
    Section("Theme", "Every Halloween flavour recolors the whole app — chats, bubbles, backgrounds.") {
        val mine = SpookPalette.custom(App.prefs.customTheme)
        (Palettes + listOfNotNull(mine)).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p -> ThemeCard(p, Modifier.weight(1f)) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
    ThemeEditor()
    Section("🦇 Pet bat", "A little bat that lives in the app: it flits around every screen, rests on the edges and flees when you poke it.") {
        Toggle("Let the bat out", Spook.batPet) { Spook.batPet = it; App.prefs.batPet = it }
        if (Spook.batPet) DramaSlider("Size", Spook.batPetSize, 0.6f, 3f, "×") { Spook.batPetSize = it; App.prefs.batPetSize = it }
    }
    Section("Animations") {
        Toggle("Background animations", Spook.animations, "Live backgrounds behind the chat list and inside chats") {
            Spook.animations = it; App.prefs.animations = it
        }
        Toggle("Matrix stream", App.prefs.matrixStream, "Decode assistant replies with a green scrambling typewriter effect (ported from xNotificationSystem)") {
            App.prefs.matrixStream = it
        }
        if (App.prefs.matrixStream) {
            var speed by remember { mutableIntStateOf(App.prefs.matrixStreamSpeed) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reveal speed", color = Spook.Bone, fontSize = 14.sp, modifier = Modifier.width(110.dp))
                androidx.compose.material3.Slider(
                    value = speed.toFloat(),
                    onValueChange = { speed = it.toInt() },
                    onValueChangeFinished = { App.prefs.matrixStreamSpeed = speed },
                    valueRange = 5f..200f,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Spook.Pumpkin, activeTrackColor = Spook.Pumpkin, inactiveTrackColor = Spook.Crypt3)
                )
                Text("${speed} ms", color = Spook.Pumpkin, fontSize = 13.sp, modifier = Modifier.width(48.dp))
            }
            Text("Higher = slower reveal, so you can enjoy the animation even when tokens arrive fast.", color = Spook.Mist, fontSize = 11.sp)
        }
    }
    if (Spook.animations) {
        var where by remember { mutableStateOf("home") }
        val tune = if (where == "home") Spook.homeTune else Spook.chatTune
        fun setTune(t: AmbTune) { if (where == "home") Spook.homeTune = t else Spook.chatTune = t; App.prefs.setTune(where, t) }
        Section("Background", "Each screen has its own animation and drama. Tap a preview to use it.") {
            Choice(listOf("home" to "🏠 Chat list", "chat" to "💬 Inside chats"), where) { where = it }
            DramaSlider("Amount", tune.n, 0.5f, 10f, "×") { setTune(tune.copy(n = it)) }
            DramaSlider("Size", tune.sc, 0.7f, 2.5f, "×") { setTune(tune.copy(sc = it)) }
            DramaSlider("Reach down the screen", tune.reach, 0.25f, 1f, "%") { setTune(tune.copy(reach = it)) }
            val selected = if (where == "home") Spook.homeAmbient else Spook.chatAmbient
            Ambients.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, label) ->
                        AmbientTile(id, label, id == selected, where, tune, Modifier.weight(1f)) {
                            if (where == "home") { Spook.homeAmbient = id; App.prefs.homeAmbient = id }
                            else { Spook.chatAmbient = id; App.prefs.chatAmbient = id }
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Live mini preview of one background animation. */

@Composable
private fun AmbientTile(id: String, label: String, on: Boolean, screen: String, tune: AmbTune, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Spook.Crypt)
        .border(if (on) 2.dp else 1.dp, if (on) Spook.Pumpkin else Spook.Crypt3, RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        // a tiny window: the animation behind a mock chat list / conversation, at the chosen drama
        Box(Modifier.fillMaxWidth().height(150.dp).background(Spook.skyBrush)) {
            if (id != "off") Ambient(id, density = 0.45f, tune = tune.copy(sc = tune.sc * 0.55f))
            MiniWindow(screen)
        }
        Text(label, color = if (on) Spook.Pumpkin else Spook.Bone, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp))
    }
}

@Composable
private fun ThemeCard(p: SpookPalette, modifier: Modifier) {
    val on = Spook.palette.id == p.id
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Color(p.skyTop), Color(p.night))))
            .border(2.dp, if (on) Color(p.accent) else Color(p.crypt3), RoundedCornerShape(12.dp))
            .clickable { Spook.palette = p; App.prefs.theme = p.id }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.emoji, fontSize = 22.sp)
            Text(p.name, color = Color(p.text), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
        }
        // mini chat preview: an incoming and an outgoing bubble
        Box(Modifier.width(70.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(p.inBubble)))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.width(80.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Brush.verticalGradient(listOf(Color(p.outTop), Color(p.outBottom)))))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(p.accent, p.secondary, p.highlight, p.moon).forEach { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(it))) }
            if (on) Text("✓", color = Color(p.accent), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
fun DramaSlider(label: String, value: Float, min: Float, max: Float, unit: String, set: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Spook.Bone, fontSize = 14.sp, modifier = Modifier.width(110.dp))
        androidx.compose.material3.Slider(value, { set((it * 10).toInt() / 10f) }, valueRange = min..max, modifier = Modifier.weight(1f),
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Spook.Pumpkin, activeTrackColor = Spook.Pumpkin, inactiveTrackColor = Spook.Crypt3))
        Text(when (unit) { "%" -> "${(value * 100).toInt()}%"; "s" -> "%.1f s".format(value); else -> "%.1f×".format(value) }, color = Spook.Pumpkin, fontSize = 13.sp, modifier = Modifier.width(48.dp))
    }
}

/** Mock screen drawn over a background preview: a chat list or a conversation, in miniature. */

@Composable
private fun MiniWindow(screen: String) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(14.dp).background(Spook.Crypt.copy(alpha = 0.85f)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Spook.Pumpkin))
            Spacer(Modifier.width(3.dp))
            Box(Modifier.width(26.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Spook.Bone.copy(alpha = 0.7f)))
        }
        if (screen == "home") {
            repeat(5) { i ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(listOf(Spook.Pumpkin, Spook.Witch, Spook.Slime, Spook.Moon, Spook.Mist)[i].copy(alpha = 0.8f)))
                    Spacer(Modifier.width(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(Modifier.width((22 + i * 5 % 13).dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Spook.Bone.copy(alpha = 0.75f)))
                        Box(Modifier.width((34 - i * 3).dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Spook.Mist.copy(alpha = 0.5f)))
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
                listOf(false to 30, true to 24, false to 18, true to 34).forEach { (me, w) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (me) Arrangement.End else Arrangement.Start) {
                        Box(Modifier.width(w.dp).height(9.dp).clip(RoundedCornerShape(4.dp)).background(if (me) Color(0xFF5A2A0C) else Color(0xFF22143A)))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(9.dp).clip(RoundedCornerShape(5.dp)).background(Spook.Crypt2))
                Spacer(Modifier.width(3.dp))
                Box(Modifier.size(9.dp).clip(CircleShape).background(Spook.Pumpkin))
            }
        }
    }
}

@Composable
private fun ThemeEditor() {
    var json by remember { mutableStateOf(App.prefs.customTheme) }
    val o = remember(json) { try { JSONObject(json.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() } }
    val baseId = o.optString("base", Spook.palette.id.takeIf { it != "custom" } ?: "blood")
    val pal = SpookPalette.custom(o.put("base", baseId).toString()) ?: Palettes[0]
    var picking by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun save(n: JSONObject) {
        json = n.toString(); App.prefs.customTheme = json
        if (Spook.palette.id == "custom") SpookPalette.custom(json)?.let { Spook.palette = it }
    }
    picking?.let { (key, label) ->
        ColorPickerDialog(label, pal.get(key), onPick = { v -> save(JSONObject(json.ifBlank { "{}" }).put("base", baseId).put(key, v)); picking = null }) { picking = null }
    }
    Section("🎨 My theme", "Start from any theme and recolour what you like — bubbles, their borders, backgrounds. Saved on this phone.") {
        Text("Start from", color = Spook.Mist, fontSize = 12.sp)
        Choice(Palettes.map { it.id to it.emoji + " " + it.name }, baseId) { save(JSONObject(json.ifBlank { "{}" }).put("base", it)) }
        // live mini preview
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(pal.skyTop), Color(pal.night))))
            .padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(pal.inBubble))
                .then(if (pal.inBorder != 0L) Modifier.border(1.dp, Color(pal.inBorder), RoundedCornerShape(10.dp)) else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp)) { Text("Their message 👻", color = Color(pal.text), fontSize = 13.sp) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Brush.verticalGradient(listOf(Color(pal.outTop), Color(pal.outBottom))))
                    .then(if (pal.outBorder != 0L) Modifier.border(1.dp, Color(pal.outBorder), RoundedCornerShape(10.dp)) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 6.dp)) { Text("My message 🎃", color = Color(pal.text), fontSize = 13.sp) }
            }
            Text("Accent", color = Color(pal.accent), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        SpookPalette.editable.forEach { (key, label) ->
            val v = pal.get(key)
            val changed = o.has(key)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { picking = key to label }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(if (v == 0L) Spook.Crypt3 else Color(v)).border(1.dp, Spook.Fog, RoundedCornerShape(7.dp))) {
                    if (v == 0L) Text("∅", color = Spook.Mist, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
                }
                Text(label, color = if (changed) Spook.Pumpkin else Spook.Bone, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                Text(if (v == 0L) "none" else "#%08X".format(v), color = Spook.Mist, fontSize = 12.sp)
                if (changed) Text("  ↺", color = Spook.Mist, fontSize = 15.sp, modifier = Modifier.clickable {
                    save(JSONObject(json).also { it.remove(key) })
                })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = { save(JSONObject(json.ifBlank { "{}" }).put("base", baseId)); SpookPalette.custom(json)?.let { Spook.palette = it; App.prefs.theme = "custom" } },
                colors = ButtonDefaults.buttonColors(containerColor = Spook.Pumpkin, contentColor = Color.Black)) { Text("Use my theme") }
            Button(onClick = { save(JSONObject().put("base", baseId)) }, colors = ButtonDefaults.buttonColors(containerColor = Spook.Crypt3, contentColor = Spook.Bone)) {
                Text("Reset colours")
            }
        }
    }
}

/** HSV picker with alpha, a hex box and the colours of every theme as presets. */

@Composable
fun ColorPickerDialog(label: String, start: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val init = if (start == 0L) 0xFFFF2E4D else start
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(init.toInt(), it) } }
    var h by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }
    var a by remember { mutableStateOf(((init shr 24) and 0xFF) / 255f) }
    var none by remember { mutableStateOf(start == 0L) }
    val argb = (android.graphics.Color.HSVToColor((a * 255).toInt(), floatArrayOf(h, sat, v)).toLong() and 0xFFFFFFFFL)
    var hex by remember(argb) { mutableStateOf("%08X".format(argb)) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = Spook.Crypt,
        title = { Text(label, color = Spook.Bone) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp)).background(if (none) Spook.Crypt3 else Color(argb))
                    .border(1.dp, Spook.Fog, RoundedCornerShape(10.dp))) {
                    if (none) Text("none", color = Spook.Mist, modifier = Modifier.align(Alignment.Center))
                }
                @Composable fun slider(name: String, value: Float, max: Float, track: Brush, set: (Float) -> Unit) {
                    Text(name, color = Spook.Mist, fontSize = 11.5.sp)
                    Box(Modifier.fillMaxWidth().height(28.dp)) {
                        Box(Modifier.fillMaxWidth().height(10.dp).align(Alignment.Center).clip(RoundedCornerShape(5.dp)).background(track))
                        androidx.compose.material3.Slider(value, { none = false; set(it) }, valueRange = 0f..max, modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
                    }
                }
                slider("Hue", h, 360f, Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })) { h = it }
                slider("Saturation", sat, 1f, Brush.horizontalGradient(listOf(Color.hsv(h, 0f, v), Color.hsv(h, 1f, v)))) { sat = it }
                slider("Brightness", v, 1f, Brush.horizontalGradient(listOf(Color.Black, Color.hsv(h, sat, 1f)))) { v = it }
                slider("Opacity", a, 1f, Brush.horizontalGradient(listOf(Color.hsv(h, sat, v).copy(alpha = 0f), Color.hsv(h, sat, v)))) { a = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#", color = Spook.Mist)
                    SmallField(hex, 110) { t ->
                        val clean = t.trim().removePrefix("#")
                        val n = clean.toLongOrNull(16) ?: return@SmallField
                        val full = if (clean.length <= 6) n or 0xFF000000L else n
                        val f = FloatArray(3); android.graphics.Color.colorToHSV(full.toInt(), f)
                        h = f[0]; sat = f[1]; v = f[2]; a = ((full shr 24) and 0xFF) / 255f; none = false
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (none) "✓ none" else "none", color = if (none) Spook.Pumpkin else Spook.Mist, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { none = !none }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Text("From the themes", color = Spook.Mist, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
                val presets = remember { Palettes.flatMap { p -> listOf(p.accent, p.accent2, p.outTop, p.outBottom, p.inBubble, p.night, p.crypt2, p.text, p.highlight, p.danger) }.distinct() }
                presets.chunked(10).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { c ->
                            Box(Modifier.size(22.dp).clip(CircleShape).background(Color(c)).border(1.dp, Spook.Fog, CircleShape).clickable {
                                val f = FloatArray(3); android.graphics.Color.colorToHSV(c.toInt(), f)
                                h = f[0]; sat = f[1]; v = f[2]; a = ((c shr 24) and 0xFF) / 255f; none = false
                            })
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onPick(if (none) 0L else argb) }) { Text("Use", color = Spook.Pumpkin) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = Spook.Mist) } },
    )
}
