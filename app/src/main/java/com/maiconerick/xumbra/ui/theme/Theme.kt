package com.maiconerick.xumbra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.maiconerick.xumbra.R

/** A Halloween palette. Every screen reads colors through [Spook], so switching themes recolors the app live. */
data class SpookPalette(
    val id: String,
    val name: String,
    val emoji: String,
    val night: Long, val crypt: Long, val crypt2: Long, val crypt3: Long,
    val accent: Long, val accent2: Long, val secondary: Long, val highlight: Long, val danger: Long,
    val text: Long, val moon: Long, val mist: Long, val fog: Long,
    val outTop: Long, val outBottom: Long, val inBubble: Long, val skyTop: Long,
    /** Bubble outlines (0 = none): mine and theirs. */
    val outBorder: Long = 0, val inBorder: Long = 0,
) {
    /** The colours a custom theme may change, with labels for the editor. */
    fun get(key: String): Long = when (key) {
        "accent" -> accent; "accent2" -> accent2; "night" -> night; "skyTop" -> skyTop; "crypt" -> crypt; "crypt2" -> crypt2
        "crypt3" -> crypt3; "outTop" -> outTop; "outBottom" -> outBottom; "inBubble" -> inBubble; "outBorder" -> outBorder
        "inBorder" -> inBorder; "text" -> text; "mist" -> mist; "secondary" -> secondary; "highlight" -> highlight; "danger" -> danger
        else -> 0
    }

    fun with(key: String, v: Long): SpookPalette = when (key) {
        "accent" -> copy(accent = v); "accent2" -> copy(accent2 = v); "night" -> copy(night = v); "skyTop" -> copy(skyTop = v)
        "crypt" -> copy(crypt = v); "crypt2" -> copy(crypt2 = v); "crypt3" -> copy(crypt3 = v); "outTop" -> copy(outTop = v)
        "outBottom" -> copy(outBottom = v); "inBubble" -> copy(inBubble = v); "outBorder" -> copy(outBorder = v)
        "inBorder" -> copy(inBorder = v); "text" -> copy(text = v); "mist" -> copy(mist = v); "secondary" -> copy(secondary = v)
        "highlight" -> copy(highlight = v); "danger" -> copy(danger = v)
        else -> this
    }

    companion object {
        val editable = listOf(
            "accent" to "Accent (buttons, titles)", "accent2" to "Accent, 2nd shade", "skyTop" to "Background, top", "night" to "Background",
            "crypt" to "Panels & headers", "crypt2" to "Cards & inputs", "crypt3" to "Lines & chips",
            "outTop" to "My bubble, top", "outBottom" to "My bubble, bottom", "outBorder" to "My bubble border",
            "inBubble" to "Their bubble", "inBorder" to "Their bubble border",
            "text" to "Text", "mist" to "Secondary text", "secondary" to "Second accent", "highlight" to "Highlight (online, read)", "danger" to "Danger",
        )

        /** A custom theme: a base palette plus the colours the user changed (stored as JSON in Prefs). */
        fun custom(json: String?): SpookPalette? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = org.json.JSONObject(json)
                var p = (Palettes.firstOrNull { it.id == o.optString("base") } ?: Palettes[0]).copy(id = "custom", name = "My theme", emoji = "🎨")
                for ((k, _) in editable) if (o.has(k)) p = p.with(k, o.getLong(k))
                p
            } catch (_: Exception) { null }
        }
    }
}

val Palettes = listOf(
    SpookPalette("pumpkin", "Pumpkin Patch", "🎃", 0xFF0B0612, 0xFF150C21, 0xFF1F1230, 0xFF2B1A40, 0xFFFF7A18, 0xFFFF4D1A, 0xFF8B3DFF,
        0xFF7CFF4F, 0xFFFF2E4D, 0xFFF2E8D5, 0xFFFFD54A, 0xFF9A8BB0, 0xFF5E4F75, 0xFF5A2A0C, 0xFF43200B, 0xFF22143A, 0xFF1A0B2E),
    SpookPalette("witch", "Witch's Brew", "🧪", 0xFF07100A, 0xFF0E1A12, 0xFF16261B, 0xFF213626, 0xFF7CFF4F, 0xFF3EDB8A, 0xFFA05CFF,
        0xFFFFD54A, 0xFFFF4D6D, 0xFFE8F5E1, 0xFFD7FF8A, 0xFF8FB39A, 0xFF4F6B57, 0xFF1F4A22, 0xFF163818, 0xFF1A2620, 0xFF0F2416),
    SpookPalette("blood", "Blood Moon", "🩸", 0xFF0D0306, 0xFF1A060B, 0xFF260A11, 0xFF36101A, 0xFFFF2E4D, 0xFFFF6A3D, 0xFFC2185B,
        0xFFFFB300, 0xFFFF5252, 0xFFF5E6E8, 0xFFFF6E6E, 0xFFB08A90, 0xFF6E4A50, 0xFF5C0E1A, 0xFF420A13, 0xFF2A0E15, 0xFF2A0610),
    SpookPalette("ghost", "Ghostly", "👻", 0xFF070A10, 0xFF0F141E, 0xFF18202D, 0xFF222C3D, 0xFFB8E1FF, 0xFF7FB8FF, 0xFF9AA7FF,
        0xFFE0F7FF, 0xFFFF6B8B, 0xFFEEF4FF, 0xFFF0F6FF, 0xFF93A3BB, 0xFF56657D, 0xFF2B3B55, 0xFF1F2B40, 0xFF1A2232, 0xFF141C2C),
    SpookPalette("grave", "Graveyard", "🪦", 0xFF0A0B0A, 0xFF141614, 0xFF1E211D, 0xFF2A2E28, 0xFF9CCB5A, 0xFFC8B560, 0xFF8C8F86,
        0xFFB9F27A, 0xFFD9534F, 0xFFE6E8DF, 0xFFE8E3C8, 0xFF9EA394, 0xFF5E6358, 0xFF3A4429, 0xFF2B321F, 0xFF23271F, 0xFF1A1E18),
    // Blood Moon's darker, crueller sibling: near-black reds, glowing crimson, burning bubbles.
    SpookPalette("demonic", "Demonic", "😈", 0xFF040001, 0xFF0C0103, 0xFF160306, 0xFF26060B, 0xFFE0001B, 0xFF7A0000, 0xFF8E0E2A,
        0xFFFF4500, 0xFFFF1744, 0xFFF3DADA, 0xFFC4001C, 0xFF9C6A70, 0xFF5C2A30, 0xFF5A000C, 0xFF2A0005, 0xFF1A0306, 0xFF3D0007,
        outBorder = 0xFFB3001B, inBorder = 0xFF5A0A14),
    SpookPalette("candy", "Candy Corn", "🍬", 0xFF120A04, 0xFF1E1008, 0xFF2A170B, 0xFF3A2010, 0xFFFFB300, 0xFFFF6F00, 0xFFFFF3E0,
        0xFFFFF176, 0xFFFF5252, 0xFFFFF8E7, 0xFFFFE082, 0xFFC4A98A, 0xFF7D6248, 0xFF6B3A06, 0xFF4E2A05, 0xFF2E1C10, 0xFF2A1606),
)

object Spook {
    var palette by mutableStateOf(Palettes[0])
    var animations by mutableStateOf(true)
    var homeAmbient by mutableStateOf("fireflies")
    var chatAmbient by mutableStateOf("embers")
    /** Drama dials per screen: amount ×, size ×, reach (0.25–1). */
    var homeTune by mutableStateOf(com.maiconerick.xumbra.ui.AmbTune())
    var chatTune by mutableStateOf(com.maiconerick.xumbra.ui.AmbTune())
    var stickerShuffle by mutableStateOf(true)
    var batPet by mutableStateOf(false)
    var batPetSize by mutableStateOf(1f)
    var shuffleMs by mutableStateOf(300)
    var shuffleFadeMs by mutableStateOf(700)

    val Night get() = Color(palette.night)
    val Crypt get() = Color(palette.crypt)
    val Crypt2 get() = Color(palette.crypt2)
    val Crypt3 get() = Color(palette.crypt3)
    val Pumpkin get() = Color(palette.accent)
    val PumpkinDeep get() = Color(palette.accent2)
    val Witch get() = Color(palette.secondary)
    val WitchDeep get() = Color(palette.secondary).copy(alpha = 0.6f)
    val Slime get() = Color(palette.highlight)
    val Blood get() = Color(palette.danger)
    val Bone get() = Color(palette.text)
    val Moon get() = Color(palette.moon)
    val Mist get() = Color(palette.mist)
    val Fog get() = Color(palette.fog)
    val InBubble get() = Color(palette.inBubble)
    val OutBorder get() = Color(palette.outBorder)
    val InBorder get() = Color(palette.inBorder)

    val Creepy = FontFamily(Font(R.font.creepster))

    val cardBrush get() = Brush.verticalGradient(listOf(Crypt2, Crypt))
    val pumpkinBrush get() = Brush.horizontalGradient(listOf(Pumpkin, PumpkinDeep))
    val witchBrush get() = Brush.horizontalGradient(listOf(Witch, Witch.copy(alpha = 0.7f)))
    val skyBrush get() = Brush.verticalGradient(listOf(Color(palette.skyTop), Night, Night))
    val outBubble get() = Brush.verticalGradient(listOf(Color(palette.outTop), Color(palette.outBottom)))
}

@Composable
fun SpookTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Spook.Pumpkin,
            onPrimary = Color.Black,
            secondary = Spook.Witch,
            onSecondary = Color.White,
            tertiary = Spook.Slime,
            background = Spook.Night,
            onBackground = Spook.Bone,
            surface = Spook.Crypt,
            onSurface = Spook.Bone,
            surfaceVariant = Spook.Crypt2,
            onSurfaceVariant = Spook.Mist,
            error = Spook.Blood,
            outline = Spook.Fog,
        ),
        content = content,
    )
}
