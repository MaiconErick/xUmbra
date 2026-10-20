package com.maiconerick.xumbra.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.json.JSONObject
import kotlin.math.roundToInt

/*
 * The rooms are drawn with sprites cut from the user's tileset image (assets/rooms/atlas.png,
 * made by make_atlas.py from tileset_user.png). One room tile = SRC_TILE tileset pixels, so
 * every sprite keeps the size it has in the tileset.
 */

const val SRC_TILE = 64f

object RoomSprites {
    var image: ImageBitmap? = null
    private val rects = HashMap<String, IntArray>()
    fun load(ctx: Context) {
        if (image != null) return
        try {
            val o = JSONObject(ctx.assets.open("rooms/atlas.json").bufferedReader().readText())
            o.keys().forEach { k -> val a = o.getJSONArray(k); rects[k] = IntArray(4) { a.getInt(it) } }
            image = BitmapFactory.decodeStream(ctx.assets.open("rooms/atlas.png"), null,
                BitmapFactory.Options().apply { inScaled = false })?.asImageBitmap()
        } catch (_: Exception) { }
    }
    operator fun get(name: String) = rects[name]
}

/** Draws sprite `name` into dst. `part` = how much of it (from the top) to draw, 0..1. */
fun DrawScope.sprite(name: String, dst: Rect, alpha: Float = 1f, part: Float = 1f) {
    val img = RoomSprites.image ?: return
    val r = RoomSprites[name] ?: return
    val sh = (r[3] * part).roundToInt().coerceAtLeast(1)
    drawImage(img, IntOffset(r[0], r[1]), IntSize(r[2], sh),
        IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
        IntSize(dst.width.roundToInt().coerceAtLeast(1), (dst.height * part).roundToInt().coerceAtLeast(1)),
        alpha = alpha, filterQuality = FilterQuality.Low)
}

/** Size of a sprite on screen at tile size t. */
fun spriteSize(name: String, t: Float, scale: Float = 1f): Size {
    val r = RoomSprites[name] ?: return Size(t, t)
    return Size(r[2] * t / SRC_TILE * scale, r[3] * t / SRC_TILE * scale)
}

object Pal {
    val ink = Color(0xFF0B0A14)
    val ink2 = Color(0xFF16142A)
    val white = Color(0xFFE8E4F4)
    val screen = Color(0xFF3A6BFF)
    val tv = Color(0xFF9A4BFF)
    val lava = Color(0xFFC040FF)
    val moon = Color(0xFF7A8CFF)
}

/** Paints art pixels: (x, y, w, h) in art px from an origin, u = screen px per art px. */
class Painter(val ds: DrawScope, var ox: Float, var oy: Float, val u: Float) {
    fun r(x: Number, y: Number, w: Number, h: Number, c: Color) =
        ds.drawRect(c, Offset(ox + x.toFloat() * u, oy + y.toFloat() * u), Size(w.toFloat() * u + 0.5f, h.toFloat() * u + 0.5f))
}

/** How each item type is drawn: its sprites (one per design), scale, and whether it stretches over its footprint (rugs). */
class Art(val sprites: List<String>, val scale: Float = 1f, val fit: Boolean = false)

val ART = mapOf(
    "desk" to Art(listOf("desk")),
    "bed" to Art(listOf("bed")),
    "bookshelf" to Art(listOf("bookshelf")),
    "skullshelf" to Art(listOf("skullshelf")),
    "dresser" to Art(listOf("dresser")),
    "wardrobe" to Art(listOf("wardrobe")),
    "drawers" to Art(listOf("drawers")),
    "nightstand" to Art(listOf("nightstand")),
    "table" to Art(listOf("table")),
    "box" to Art(listOf("box")),
    "tv" to Art(listOf("tv")),
    "pc" to Art(listOf("pc")),
    "headphones" to Art(listOf("headphones")),
    "gamechair" to Art(listOf("gamechair")),
    "beanbag" to Art(listOf("seat0", "seat1", "seat2", "seat3")),
    "plant" to Art(listOf("plant0", "plant1", "plant2", "plant3", "plant4")),
    "lavalamp" to Art(listOf("lava0", "lava1", "lava2")),
    "cat" to Art(listOf("cat0", "cat1")),
    "rug" to Art(listOf("rug"), fit = true),
    "smallrug" to Art(listOf("smallrug"), fit = true),
    "roundrug" to Art(listOf("roundrug"), fit = true),
    "runner" to Art(listOf("runner0", "runner1"), fit = true),
    "mat" to Art(listOf("mat0", "mat1"), fit = true),
    "smallmat" to Art(listOf("mat2"), fit = true),
    "window" to Art(listOf("window"), scale = 0.86f),
    "poster" to Art(listOf("poster0", "poster1", "poster2", "poster3", "poster4", "poster5"), scale = 0.72f),
    "pentarug" to Art(listOf("pentarug"), fit = true),
    "gameshelf" to Art(listOf("gameshelf")),
    "gamepad" to Art(listOf("gamepad")),
    "sword" to Art(listOf("sword")),
    "spellbook" to Art(listOf("spellbook")),
    "cabinet" to Art(listOf("cabinet")),
    "lavastand" to Art(listOf("lavastand")),
    "door" to Art(listOf("door"), scale = 0.82f),
)

/** How many designs an item has. */
val VARIANTS = ART.filterValues { it.sprites.size > 1 }.mapValues { it.value.sprites.size }

/** Everything one frame of a room needs. */
class Scene(
    val now: Long, val actor: Actor, val worker: Worker?, val dark: Float, val sick: Boolean, val countdown: String,
) {
    val working get() = actor.mode == Mode.WORK
    fun using(i: Int) = actor.mode == Mode.ACT && actor.actItem == i
}

// ───────────────────────── floor & walls ─────────────────────────

fun DrawScope.drawFloorAndWalls(l: RoomLayout) {
    val t = size.width / ROOM_N
    drawRect(Color(0xFF07060E), Offset.Zero, size)
    for (ty in ROOM_WALL until ROOM_N) for (tx in 0 until ROOM_N) {
        val k = ((tx * 73856093) xor (ty * 19349663)).let { if (it < 0) -it else it } % 7
        sprite("floor$k", Rect(tx * t, ty * t, (tx + 1) * t + 0.6f, (ty + 1) * t + 0.6f))
    }
    for (tx in 0 until ROOM_N) sprite("wall", Rect(tx * t, 0f, (tx + 1) * t + 0.6f, ROOM_WALL * t))
    // the walls' edges, like the reference room
    val edge = Color(0xFF2B2858); val edge2 = Color(0xFF15132C)
    val e = t * 0.14f
    drawRect(edge2, Offset(0f, 0f), Size(size.width, e))
    drawRect(edge, Offset(0f, e), Size(size.width, e * 0.35f))
    drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, ROOM_WALL * t), Size(size.width, t * 0.08f))
    drawRect(edge2, Offset(0f, 0f), Size(e, size.height)); drawRect(edge, Offset(e, 0f), Size(e * 0.35f, size.height))
    drawRect(edge2, Offset(size.width - e, 0f), Size(e, size.height)); drawRect(edge, Offset(size.width - e * 1.35f, 0f), Size(e * 0.35f, size.height))
    drawRect(edge2, Offset(0f, size.height - e), Size(size.width, e)); drawRect(edge, Offset(0f, size.height - e * 1.35f), Size(size.width, e * 0.35f))
}

// ───────────────────────── furniture ─────────────────────────

/** Where an item's sprite lands: centred on its footprint, standing on its bottom edge (wall things centred). */
fun itemRect(it: RoomItem, ox: Float, oy: Float, t: Float): Rect? {
    val d = ITEM_DEFS[it.type] ?: return null
    val art = ART[it.type] ?: return null
    val name = art.sprites[it.variant.mod(art.sprites.size)]
    val w = d.w * t; val h = d.h * t
    if (art.fit) return Rect(ox, oy, ox + w, oy + h)
    val s = spriteSize(name, t, art.scale)
    val x = ox + (w - s.width) / 2
    val y = if (d.wall) oy + (h - s.height) / 2 else oy + h - s.height
    return Rect(x, y, x + s.width, y + s.height)
}

/** Draws one item with its top-left footprint corner at (ox, oy). Returns where the sprite went. */
fun DrawScope.drawItemArt(it: RoomItem, idx: Int, ox: Float, oy: Float, t: Float, s: Scene): Rect? {
    if (it.type == "clock") { clock(ox, oy, t, s.countdown); return Rect(ox, oy, ox + 2 * t, oy + t) }
    val art = ART[it.type] ?: return null
    val r = itemRect(it, ox, oy, t) ?: return null
    val name = art.sprites[it.variant.mod(art.sprites.size)]
    sprite(name, r)
    if (it.type == "tv" && !(s.using(idx) && s.actor.actType in setOf("tv", "console")))   // screen off when nobody's watching
        drawRect(Color(0xFF05040C).copy(alpha = 0.7f), Offset(r.left + r.width * 0.06f, r.top + r.height * 0.04f), Size(r.width * 0.88f, r.height * 0.5f))
    return r
}

private val DIG = mapOf(
    '0' to "111101101101111", '1' to "010110010010111", '2' to "111001111100111", '3' to "111001111001111",
    '4' to "101101111001001", '5' to "111100111001111", '6' to "111100111101111", '7' to "111001010010010",
    '8' to "111101111101111", '9' to "111101111001111", '-' to "000000111000000", 'm' to "000000111111101",
    'h' to "100100111101101", '>' to "100110111110100", ':' to "000010000010000", 'd' to "001001111101111",
)

/** The countdown: a little LED panel (2 tiles wide) in the tileset's colours. */
private fun DrawScope.clock(ox: Float, oy: Float, t: Float, text: String) {
    val p = Painter(this, ox, oy, t / 16f)
    p.r(2, 2, 28, 12, Pal.ink); p.r(3, 3, 26, 10, Color(0xFF1C1A3A)); p.r(4, 4, 24, 8, Color(0xFF0A0916))
    p.r(3, 3, 26, 1, Color(0xFF3A3570))
    val s = text.take(5)
    fun wOf(ch: Char) = if (ch == ':') 2.4f else 4.4f
    var cx = 16f - s.sumOf { wOf(it).toDouble() }.toFloat() / 2 + 0.6f
    s.forEach { ch ->
        val m = DIG[ch]
        if (m != null) {
            val x0 = if (ch == ':') cx - 1.2f else cx
            for (i in 0 until 15) if (m[i] == '1') p.r(x0 + i % 3 * 1.2f, 5f + i / 3 * 1.2f, 1.2f, 1.2f, Color(0xFFFF4A9A))
        }
        cx += wOf(ch)
    }
}

// ───────────────────────── the character ─────────────────────────

data class Look(val hair: Color, val skin: Color, val hoodie: Color, val logo: Color, val pants: Color)

/** The guy's sprite for where he's facing (0 up, 1 right, 2 down, 3 left) and step. Side frames face left. */
/** Characters in the atlas (made with tools/room_atlas/make_atlas.py): id → name shown in the room editor. */
val CHARACTERS = listOf("guy" to "Guy (hoodie)", "muse" to "Muse (fashion)")

private fun charId(c: String) = if (RoomSprites["${c}_d0"] != null) c else "guy"
private fun guyFrame(char: String, face: Int, step: Int) = charId(char) + when (face) { 0 -> "_u$step"; 2 -> "_d$step"; else -> "_s$step" }

/**
 * Draws the guy standing on the tile whose top-left is (ox, oy). `lift` raises him (tiles),
 * `part` cuts his legs off (sitting in something).
 */
fun DrawScope.drawGuyArt(a: Actor, ox: Float, oy: Float, t: Float, now: Long, face: Int = a.face, lift: Float = 0f, part: Float = 1f) {
    val walking = a.mode == Mode.WALK && now >= a.walkStart
    // step cycle: left foot up, stand, right foot up, stand
    val step = if (walking) intArrayOf(1, 0, 2, 0)[((now / 140) % 4).toInt()] else 0
    val name = guyFrame(a.char, face, step)
    val s = spriteSize(name, t)
    val bob = if (step != 0) -t * 0.025f else 0f
    val x = ox + (t - s.width) / 2
    val y = oy + t * 0.92f - s.height - lift * t + bob
    withTransform({ if (face == 1) scale(-1f, 1f, Offset(ox + t / 2, 0f)) }) {
        sprite(name, Rect(x, y, x + s.width, y + s.height), part = part)
    }
}

/** Him lying in bed: head on the pillow, cut where the blanket starts (a third of the way down the bed sprite). */
fun DrawScope.drawSleepingHead(bed: Rect, t: Float, char: String = "guy") {
    val front = charId(char) + "_d0"
    val s = spriteSize(front, t)
    val x = bed.center.x - s.width / 2
    val y = bed.top + bed.height * 0.105f
    val blanket = bed.top + bed.height * 0.335f
    sprite(front, Rect(x, y, x + s.width, y + s.height), part = ((blanket - y) / s.height).coerceIn(0.1f, 1f))
}

/** The desk chair, with its seat over the spot (x, y) in tiles. */
fun DrawScope.deskChair(spotX: Float, spotY: Float, t: Float) {
    val s = spriteSize("deskchair", t)
    val x = (spotX + 0.5f) * t - s.width / 2
    val bottom = (spotY + 0.45f) * t
    sprite("deskchair", Rect(x, bottom - s.height, x + s.width, bottom))
}

/** Which of the tileset's 20 thought bubbles shows each thought. */
private val BUBBLE = mapOf(
    "💭" to 1, "💻" to 1, "🤓" to 1, "👕" to 1, "🧥" to 1, "🤔" to 2, "⏰" to 3, "👀" to 3, "❤️" to 4, "🐱" to 4,
    "✅" to 5, "❌" to 6, "⚠" to 7, "💤" to 8, "😄" to 9, "😁" to 9, "😎" to 9, "🤙" to 9, "😺" to 9, "😂" to 9, "🍿" to 9,
    "😵" to 10, "😤" to 11, "😌" to 12, "✨" to 13, "📺" to 13, "🌙" to 13, "🎵" to 14, "🎧" to 14, "🕺" to 14,
    "🔥" to 15, "🎮" to 15, "🌱" to 16, "💧" to 16, "💀" to 17, "💡" to 18, "📖" to 18, "🌀" to 19, "🌃" to 19,
    "🏆" to 20, "🥳" to 20, "🙌" to 20,
)

/**
 * A thought bubble to the right of his head: (x, y) is where its tail starts (by his head).
 * The tileset's bubble sprite, or an emoji in a drawn one. Near the room's right edge it goes to his left.
 */
fun DrawScope.drawBubble(x: Float, y: Float, t: Float, emoji: String, alpha: Float) {
    if (alpha <= 0.02f) return
    val b = BUBBLE[emoji]
    if (b != null && RoomSprites["bubble$b"] != null) {
        val s = spriteSize("bubble$b", t, 0.875f * SRC_TILE / (RoomSprites["bubble$b"]!![2]))
        val left = if (x + s.width > size.width) x - t * 0.45f - s.width else x
        val top = y - s.height
        sprite("bubble$b", Rect(left, top, left + s.width, top + s.height), alpha)
        return
    }
    val w = t * 1.15f; val h = t * 0.95f
    val left = x - w / 2; val top = y - h
    val ink = Pal.ink.copy(alpha = alpha); val fill = Color(0xFFF4F0FF).copy(alpha = alpha)
    drawRoundRect(ink, Offset(left - 2, top - 2), Size(w + 4, h + 4), androidx.compose.ui.geometry.CornerRadius(t * 0.3f))
    drawRoundRect(fill, Offset(left, top), Size(w, h), androidx.compose.ui.geometry.CornerRadius(t * 0.28f))
    drawCircle(ink, t * 0.13f, Offset(x - t * 0.15f, y + t * 0.12f)); drawCircle(fill, t * 0.09f, Offset(x - t * 0.15f, y + t * 0.12f))
    drawCircle(ink, t * 0.08f, Offset(x - t * 0.28f, y + t * 0.32f)); drawCircle(fill, t * 0.05f, Offset(x - t * 0.28f, y + t * 0.32f))
    val paint = android.graphics.Paint().apply { textSize = t * 0.62f; textAlign = android.graphics.Paint.Align.CENTER; this.alpha = (alpha * 255).toInt() }
    drawContext.canvas.nativeCanvas.drawText(emoji, x, top + h * 0.72f, paint)
}

/** Soft light on top of the dark overlay. */
fun DrawScope.glow(cx: Float, cy: Float, r: Float, col: Color, a: Float) {
    drawCircle(Brush.radialGradient(listOf(col.copy(alpha = a), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
}
