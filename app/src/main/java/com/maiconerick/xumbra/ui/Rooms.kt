package com.maiconerick.xumbra.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maiconerick.xumbra.data.Net
import com.maiconerick.xumbra.data.objects
import com.maiconerick.xumbra.ui.theme.Spook
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/*
 * Agents as pixel rooms. Every background worker lives in a little gamer room (tileset
 * in RoomArt.kt). He sleeps with the lights dimmed, wakes up to read, watch TV, play,
 * look out the window… with whatever is in his room, and always sits at the desk exactly
 * when his task starts — then a ✅ bubble when it went well.
 */

// ───────────────────────── live state ─────────────────────────

data class Worker(
    val id: String, val name: String, val desc: String, val emoji: String, val every: Double?, val nextRun: Double?,
    val running: Boolean, val lastRun: Double?, val lastMs: Int?, val avgMs: Int?, val ok: Boolean, val note: String,
) {
    companion object {
        private fun JSONObject.d(k: String) = if (isNull(k) || !has(k)) null else optDouble(k)
        fun of(o: JSONObject) = Worker(
            o.optString("id"), o.optString("name"), o.optString("desc"), o.optString("emoji", "🤖"), o.d("every"), o.d("next_run"),
            o.optBoolean("running"), o.d("last_run"), if (o.isNull("last_ms") || !o.has("last_ms")) null else o.optInt("last_ms"),
            if (o.isNull("avg_ms") || !o.has("avg_ms")) null else o.optInt("avg_ms"), o.optBoolean("ok", true), o.optString("note"),
        )
    }
}

object Workers {
    val map = mutableStateMapOf<String, Worker>()
    val order = mutableStateListOf<String>()
    /** Pi clock − phone clock, in seconds (the schedule is in the Pi's time). */
    var offset = 0.0
    fun serverNow() = System.currentTimeMillis() / 1000.0 + offset
    suspend fun load() {
        try {
            val o = Net.get("/api/v2/workers")
            offset = o.optDouble("now", serverNow()) - System.currentTimeMillis() / 1000.0
            o.optJSONArray("workers").objects().forEach { put(Worker.of(it)) }
        } catch (_: Exception) { }
    }
    fun onEvent(d: JSONObject) {
        if (d.has("now")) offset = d.optDouble("now") - System.currentTimeMillis() / 1000.0
        if (d.optString("id").isNotBlank()) put(Worker.of(d))
    }
    private fun put(w: Worker) {
        map[w.id] = w
        if (w.id !in order) order.add(w.id)
        // agents on a timer first, the after-each-reply ones next, your scheduled agents last
        val sorted = order.sortedBy { k -> val x = map[k]; when { k.startsWith("cron:") -> 2; x?.every == null -> 1; else -> 0 } }
        if (sorted != order.toList()) { order.clear(); order.addAll(sorted) }
    }
}

// ───────────────────────── layout ─────────────────────────

const val ROOM_N = 12
const val ROOM_WALL = 2

/** rot = 1 means mirrored left↔right. colors = unused (the tileset's colours are fixed). */
data class RoomItem(val type: String, val x: Int, val y: Int, val rot: Int = 0, val colors: Map<String, Long> = emptyMap(), val variant: Int = 0)

/** name = the character's name; title = the room's name (blank → the agent's own name). */
data class RoomStyle(
    val wall: Long, val floor: Long, val name: String,
    val hair: Long, val skin: Long, val hoodie: Long, val logo: Long, val pants: Long,
    val title: String = "",
    val char: String = "guy",          // which character lives here (CHARACTERS)
)

/** settings = this room's own free-time settings (null → the old shared ones, or the defaults). */
data class RoomLayout(val items: List<RoomItem>, val style: RoomStyle, val settings: RoomSettings? = null) {
    val cfg get() = settings ?: RoomStore.shared
}

fun roomTitle(l: RoomLayout, w: Worker?, id: String) = l.style.title.ifBlank { w?.name ?: id }

enum class Pose { STAND, SIT_BACK, SIT_FRONT, READ }

/** Footprint (w×h tiles), wall-hung or not, solid or not, and what he does with it. */
class ItemDef(
    val type: String, val label: String, val w: Int, val h: Int, val wall: Boolean, val blocks: Boolean,
    val use: Triple<Int, Int, Int>? = null, val pose: Pose = Pose.STAND, val act: String? = null, val anywhere: Boolean = false,
)

val ITEM_DEFS = listOf(
    ItemDef("desk", "Desk + PC", 3, 2, false, true, Triple(1, 2, 0), Pose.SIT_BACK, "work"),
    ItemDef("bed", "Bed", 2, 3, false, true, Triple(2, 1, 3), Pose.STAND, "sleep"),
    ItemDef("tv", "TV + console", 2, 1, false, true, Triple(1, 2, 0), Pose.STAND, "tv"),
    ItemDef("bookshelf", "Bookshelf", 2, 1, false, true, Triple(1, 1, 0), Pose.READ, "read"),
    ItemDef("skullshelf", "Skull shelf", 1, 1, false, true, Triple(0, 1, 0), Pose.STAND, "skull"),
    ItemDef("beanbag", "Pouf", 1, 1, false, true, Triple(0, 0, 2), Pose.SIT_FRONT, "chill"),
    ItemDef("cat", "Cat", 1, 1, false, true, Triple(0, 1, 0), Pose.STAND, "cat"),
    ItemDef("plant", "Plant", 1, 1, false, true, Triple(0, 1, 0), Pose.STAND, "plant"),
    ItemDef("lavalamp", "Lava lamp", 1, 1, false, true, Triple(0, 1, 0), Pose.STAND, "lava"),
    ItemDef("headphones", "Headphones", 1, 1, false, true, Triple(0, 1, 0), Pose.STAND, "music"),
    ItemDef("wardrobe", "Wardrobe", 1, 1, false, true, Triple(0, 1, 0), Pose.STAND, "clothes"),
    ItemDef("dresser", "Dresser", 2, 1, false, true, Triple(1, 1, 0), Pose.STAND, "clothes"),
    ItemDef("gameshelf", "Game shelf", 2, 1, false, true, Triple(1, 1, 0), Pose.STAND, "games"),
    ItemDef("gamepad", "Gamepad", 1, 1, false, true, Triple(0, 1, 0), Pose.STAND, "console"),
    ItemDef("spellbook", "Spellbook", 1, 1, false, true, Triple(0, 1, 0), Pose.READ, "magic"),
    ItemDef("lavastand", "Lava stand", 1, 1, false, true, Triple(0, 1, 0), Pose.STAND, "lava"),
    ItemDef("cabinet", "Demon cabinet", 2, 1, false, true, Triple(1, 1, 0), Pose.STAND, "clothes"),
    ItemDef("sword", "Sword", 1, 1, false, true),
    ItemDef("drawers", "Drawers", 1, 1, false, true),
    ItemDef("nightstand", "Nightstand", 1, 1, false, true),
    ItemDef("table", "Low table", 2, 1, false, true),
    ItemDef("box", "Box", 1, 1, false, true),
    ItemDef("pc", "PC tower", 1, 1, false, true),
    ItemDef("gamechair", "Gaming chair", 1, 1, false, true),
    ItemDef("rug", "Rug", 2, 3, false, false),
    ItemDef("roundrug", "Round rug", 2, 2, false, false),
    ItemDef("pentarug", "Pentagram rug", 3, 2, false, false),
    ItemDef("smallrug", "Small rug", 2, 1, false, false),
    ItemDef("runner", "Runner", 1, 3, false, false),
    ItemDef("mat", "Doormat", 2, 1, false, false),
    ItemDef("smallmat", "Mat", 1, 1, false, false),
    ItemDef("window", "Window", 3, 2, true, false, Triple(1, 2, 0), Pose.STAND, "window"),
    ItemDef("poster", "Poster", 1, 2, true, false),
    ItemDef("door", "Door", 2, 2, true, false),
    ItemDef("clock", "Countdown", 2, 1, true, false, anywhere = true),
).associateBy { it.type }

/** What he thinks about while doing each thing. */
val ACT_EMOJI = mapOf(
    "read" to listOf("📖", "🤓", "💡"), "tv" to listOf("📺", "😂", "🍿"), "console" to listOf("🎮", "🔥", "🏆", "😤"),
    "chill" to listOf("😌", "🎧", "💭"), "guitar" to listOf("🎸", "🎶", "🤘"), "cat" to listOf("🐱", "❤️", "😺"),
    "fridge" to listOf("🍕", "🥤", "😋"), "plant" to listOf("🌱", "💧"), "music" to listOf("🎵", "🎧", "🕺"),
    "lava" to listOf("✨", "🌀"), "skull" to listOf("💀", "🤔", "👀"), "magic" to listOf("✨", "🌀", "💡"), "games" to listOf("🎮", "🔥", "🏆"), "clothes" to listOf("👕", "🧥", "😎"), "window" to listOf("🌙", "✨", "🌃", "💭"),
)
private val HAPPY = listOf("😄", "😎", "🙌", "🥳", "😁", "🤙")

val NAMES = listOf(
    "Nyx", "Byte", "Kuro", "Vex", "Glitch", "Hex", "Echo", "Raven", "Neon", "Shade", "Zero", "Pixel", "Onyx", "Kai",
    "Rin", "Jinx", "Volt", "Sable", "Ash", "Nova", "Ghost", "Rook", "Dusk", "Cipher", "Lumen", "Moth", "Static", "Yuki",
)

private val LOOKS = listOf(
    RoomStyle(0xFF23224A, 0xFF4A2E2A, "", 0xFF15131F, 0xFFF2D3C2, 0xFF1A1826, 0xFFE8E4F4, 0xFF1C1B33),
    RoomStyle(0xFF1E2B3A, 0xFF3D3442, "", 0xFF3A2414, 0xFFE0A47A, 0xFF2F6BFF, 0xFFE8E4F4, 0xFF20202A),
    RoomStyle(0xFF2D1B2B, 0xFF5B3A29, "", 0xFFB44BFF, 0xFFF4D0B0, 0xFF111018, 0xFFE040FB, 0xFF1C1B33),
    RoomStyle(0xFF1B2A30, 0xFF43332E, "", 0xFF101010, 0xFFC68A5E, 0xFF19C2B8, 0xFF111018, 0xFF2A2A40),
    RoomStyle(0xFF2B2240, 0xFF4E3123, "", 0xFFE8E4F4, 0xFFF2D3C2, 0xFF8A3CFF, 0xFFE8E4F4, 0xFF15131F),
)

fun defaultName(id: String) = NAMES[abs(id.hashCode() / 3) % NAMES.size]

fun defaultLayout(id: String): RoomLayout {
    val h = abs(id.hashCode())
    val st = LOOKS[h % LOOKS.size].copy(name = defaultName(id))
    var items = listOf(
        RoomItem("poster", 1, 0, variant = h % 5), RoomItem("window", 3, 0), RoomItem("clock", 8, 1),
        RoomItem("poster", 10, 0, variant = (h / 5 + 1) % 5),
        RoomItem("desk", 0, 2), RoomItem("plant", 3, 2, variant = h % 5), RoomItem("bookshelf", 6, 2),
        RoomItem("nightstand", 8, 2), RoomItem("lavalamp", 9, 2, variant = h % 3), RoomItem("bed", 10, 2, rot = 1),
        RoomItem("rug", 5, 6), RoomItem("wardrobe", 11, 7), RoomItem("headphones", 9, 6),
        RoomItem("tv", 0, 8), RoomItem("beanbag", 1, 10, variant = h % 4), RoomItem("skullshelf", 3, 8),
        RoomItem("cat", 6, 10, variant = h / 3 % 2), RoomItem("dresser", 8, 11), RoomItem("plant", 11, 11, variant = (h / 11 + 2) % 5),
    )
    if (h / 7 % 2 == 1) items = items.map { val d = ITEM_DEFS[it.type]!!; it.copy(x = ROOM_N - it.x - d.w, rot = 1 - it.rot % 2) }
    return RoomLayout(items, st)
}

private fun legacyType(t: String) = when (t) { "shelf" -> "bookshelf"; "console" -> "tv"; else -> t }

const val ROOM_VERSION = 3   // layouts saved before the tileset sprites don't fit the new furniture

fun layoutToJson(l: RoomLayout) = JSONObject().put("v", ROOM_VERSION)
    .put("items", JSONArray(l.items.map { i ->
        JSONObject().put("type", i.type).put("x", i.x).put("y", i.y).put("rot", i.rot).put("variant", i.variant)
            .put("colors", JSONObject().also { o -> i.colors.forEach { (k, v) -> o.put(k, v) } })
    }))
    .put("style", JSONObject().put("wall", l.style.wall).put("floor", l.style.floor).put("name", l.style.name)
        .put("hair", l.style.hair).put("skin", l.style.skin).put("hoodie", l.style.hoodie).put("logo", l.style.logo).put("pants", l.style.pants)
        .put("title", l.style.title).put("char", l.style.char))
    .also { o -> l.settings?.let { o.put("settings", it.toJson()) } }

fun layoutOf(o: JSONObject?, id: String): RoomLayout {
    val def = defaultLayout(id)
    o ?: return def
    if (o.optInt("v") < ROOM_VERSION) return def.copy(style = def.style.copy(name = o.optJSONObject("style")?.optString("name")?.ifBlank { null } ?: def.style.name))
    val items = o.optJSONArray("items").objects().map { j ->
        val cols = j.optJSONObject("colors")
        RoomItem(legacyType(j.optString("type")), j.optInt("x"), j.optInt("y"), j.optInt("rot") % 2,
            cols?.keys()?.asSequence()?.associateWith { cols.optLong(it) } ?: emptyMap(), j.optInt("variant"))
    }.filter { it.type in ITEM_DEFS }
    val s = o.optJSONObject("style")
    val d = def.style
    val st = if (s == null) d else RoomStyle(s.optLong("wall", d.wall), s.optLong("floor", s.optLong("floorA", d.floor)),
        s.optString("name").ifBlank { d.name }, s.optLong("hair", d.hair), s.optLong("skin", d.skin), s.optLong("hoodie", s.optLong("shirt", d.hoodie)),
        s.optLong("logo", d.logo), s.optLong("pants", d.pants), s.optString("title"),
        s.optString("char").ifBlank { "guy" })
    val cfg = o.optJSONObject("settings")?.let { RoomSettings.of(it) }
    return if (items.none { it.type == "bed" } || items.none { it.type == "desk" }) def.copy(style = st, settings = cfg) else RoomLayout(items, st, cfg)
}

/** How a room's guy spends his free time. */
data class RoomSettings(
    val chance: Int = 30,          // % chance to do something when he wakes up / finishes a task (else he sleeps)
    val actSec: Int = 60,          // how long an activity lasts (±25 %)
    val sleepSec: Int = 120,       // how long he naps before thinking about doing something again (±30 %)
    val bubbles: Boolean = true,   // thought bubbles during activities (now and then, not all the time)
    val off: Set<String> = emptySet(),   // activities he never does
) {
    fun toJson() = JSONObject().put("chance", chance).put("actSec", actSec).put("sleepSec", sleepSec).put("bubbles", bubbles)
        .put("off", JSONArray(off.toList()))
    companion object {
        fun of(o: JSONObject?) = if (o == null) RoomSettings() else RoomSettings(
            o.optInt("chance", 30), o.optInt("actSec", 60), o.optInt("sleepSec", 120), o.optBoolean("bubbles", true),
            (0 until (o.optJSONArray("off")?.length() ?: 0)).map { o.getJSONArray("off").optString(it) }.toSet())
    }
}

/** Activities as the settings list them: act → label. */
val ACTIVITIES = listOf(
    "tv" to "📺 Watch TV", "console" to "🎮 Play videogames", "window" to "🌙 Look out the window", "read" to "📖 Read",
    "chill" to "😌 Chill on the pouf", "cat" to "🐱 Pet the cat", "plant" to "🌱 Water plants", "music" to "🎧 Listen to music",
    "lava" to "✨ Stare at the lava lamp", "clothes" to "👕 Change clothes", "skull" to "💀 Look at the skull",
    "magic" to "🌀 Read the spellbook", "games" to "🏆 Browse the game shelf",
)

private const val SETTINGS_KEY = "__settings"

object RoomStore {
    val layouts = mutableStateMapOf<String, RoomLayout>()
    /** Settings saved before rooms had their own (2.5.x) — the starting point for rooms without any. */
    var shared by mutableStateOf(RoomSettings())
    suspend fun load() {
        try {
            val r = Net.get("/api/v2/rooms").optJSONObject("rooms") ?: JSONObject()
            r.optJSONObject(SETTINGS_KEY)?.let { shared = RoomSettings.of(it) }
            r.keys().forEach { k -> if (k != SETTINGS_KEY) layouts[k] = layoutOf(r.optJSONObject(k), k) }
        } catch (_: Exception) { }
    }
    fun get(id: String) = layouts[id] ?: defaultLayout(id)
    suspend fun save(id: String, l: RoomLayout?) {
        if (l == null) layouts.remove(id) else layouts[id] = l
        try { Net.put("/api/v2/rooms/" + Net.enc(id), if (l == null) JSONObject() else layoutToJson(l)) } catch (_: Exception) { }
    }
    suspend fun saveSettings(id: String, st: RoomSettings) = save(id, get(id).copy(settings = st))
}

// ───────────────────────── geometry & walking ─────────────────────────

data class Spot(val x: Int, val y: Int, val face: Int)

fun useSpot(it: RoomItem): Spot? {
    val d = ITEM_DEFS[it.type] ?: return null
    val u = d.use ?: return null
    val flip = it.rot % 2 == 1
    val dx = if (flip) d.w - 1 - u.first else u.first
    val face = if (flip) when (u.third) { 1 -> 3; 3 -> 1; else -> u.third } else u.third
    return Spot(it.x + dx, it.y + u.second, face)
}

private fun blocked(l: RoomLayout): Array<BooleanArray> {
    val b = Array(ROOM_N) { y -> BooleanArray(ROOM_N) { y < ROOM_WALL } }
    l.items.forEach { i ->
        val d = ITEM_DEFS[i.type] ?: return@forEach
        if (!d.blocks || d.wall) return@forEach
        for (yy in i.y until i.y + d.h) for (xx in i.x until i.x + d.w) if (xx in 0 until ROOM_N && yy in 0 until ROOM_N) b[yy][xx] = true
    }
    return b
}

fun findPath(l: RoomLayout, from: Pair<Int, Int>, to: Pair<Int, Int>): List<Pair<Int, Int>>? {
    val b = blocked(l)
    fun ok(x: Int, y: Int) = x in 0 until ROOM_N && y in ROOM_WALL until ROOM_N && (!b[y][x] || (x to y) == to || (x to y) == from)
    if (!ok(to.first, to.second)) return null
    if (from == to) return listOf(from)
    val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    // Dijkstra over (tile, direction): a step costs 10, a turn 3 more
    val n = ROOM_N * ROOM_N * 4
    val cost = IntArray(n) { Int.MAX_VALUE }
    val prev = IntArray(n) { -1 }
    fun key(x: Int, y: Int, d: Int) = (y * ROOM_N + x) * 4 + d
    val q = java.util.PriorityQueue<IntArray>(compareBy { it[0] })
    for (d in 0 until 4) {
        val nx = from.first + dirs[d].first; val ny = from.second + dirs[d].second
        if (ok(nx, ny)) { val k = key(nx, ny, d); cost[k] = 10; q.add(intArrayOf(10, k)) }
    }
    var end = -1
    while (q.isNotEmpty()) {
        val (c, k) = q.poll()
        if (c > cost[k]) continue
        val d = k % 4; val tileI = k / 4; val x = tileI % ROOM_N; val y = tileI / ROOM_N
        if ((x to y) == to) { end = k; break }
        for (nd in 0 until 4) {
            val nx = x + dirs[nd].first; val ny = y + dirs[nd].second
            if (!ok(nx, ny) || (nx to ny) == from) continue
            val nk = key(nx, ny, nd); val nc = c + 10 + if (nd != d) 3 else 0
            if (nc < cost[nk]) { cost[nk] = nc; prev[nk] = k; q.add(intArrayOf(nc, nk)) }
        }
    }
    if (end < 0) return null
    val path = ArrayList<Pair<Int, Int>>()
    var k = end
    while (k >= 0) { val ti = k / 4; path.add(ti % ROOM_N to ti / ROOM_N); k = prev[k] }
    path.add(from); path.reverse()
    return path
}

private const val SPEED = 2.6f          // tiles per second
private const val GET_UP = 600L         // ms to climb out of bed

enum class Mode { SLEEP, WALK, WORK, ACT, STAND }

/** The guy: where he is, what he's doing next. Stepped every frame from the worker's live state. */
class Actor(seed: Int) {
    var x = 0f; var y = 0f; var face = 2
    var mode = Mode.SLEEP
    var pose = Pose.STAND
    var look = Look(Color.Black, Color.White, Color.Black, Color.White, Color.Black)
    var char = "guy"
    var path: List<Pair<Int, Int>> = emptyList()
    var walkStart = 0L
    var goal = ""                 // "desk" | "bed" | "act"
    var actType: String? = null; var actItem = -1; var bedIdx = -1
    var actUntil = 0L
    var modeSince = 0L            // when he got to what he's doing now
    var checkAt = 0L; var doneUntil = 0L; var doneOk = true; var happyUntil = 0L; var happy = "😄"
    var lastRunSeen: Double? = null
    var committedRun: Double? = null   // the next_run he already left for — once he's going, he doesn't turn back
    var inited = false
    private val rnd = Random(seed)
    private var pendingPose = Pose.STAND; private var pendingFace = 2

    private fun tile() = Math.round(x) to Math.round(y)
    var cfg = RoomSettings()
    private fun st() = cfg

    private fun walkTo(l: RoomLayout, spot: Spot, now: Long, wasSleeping: Boolean): Boolean {
        val p = findPath(l, tile(), spot.x to spot.y) ?: return false
        path = p; walkStart = now + if (wasSleeping) GET_UP else 0; mode = Mode.WALK
        pendingFace = spot.face
        return true
    }

    private fun msTo(l: RoomLayout, spot: Spot, from: Pair<Int, Int> = tile(), asleep: Boolean = mode == Mode.SLEEP): Long {
        val p = findPath(l, from, spot.x to spot.y) ?: return 4000
        return ((p.size - 1) / SPEED * 1000).toLong() + (if (asleep) GET_UP else 0)
    }

    fun step(now: Long, w: Worker?, l: RoomLayout) {
        val deskI = l.items.indexOfFirst { it.type == "desk" }
        bedIdx = l.items.indexOfFirst { it.type == "bed" }
        val desk = l.items.getOrNull(deskI)?.let { useSpot(it) }
        val bed = l.items.getOrNull(bedIdx)?.let { useSpot(it) }
        if (desk == null || bed == null) { mode = Mode.STAND; return }
        if (!inited) {
            inited = true; x = bed.x.toFloat(); y = bed.y.toFloat(); mode = Mode.SLEEP; goal = "bed"; lastRunSeen = w?.lastRun
            modeSince = now; actUntil = now + (st().sleepSec * 1000L * (0.2 + rnd.nextDouble() * 0.6)).toLong()
        }
        // a run finished → (instant tasks: 1 s of typing first) ✅ / ❌, sometimes a happy face after
        if (w != null && w.lastRun != null && w.lastRun != lastRunSeen) {
            lastRunSeen = w.lastRun
            doneOk = w.ok
            val atDesk = mode == Mode.WORK
            checkAt = now + (if ((w.lastMs ?: 0) < 1000) 1000 else 0)       // instant tasks: 1 s of typing, then the check
            if (!atDesk) checkAt = now + msTo(l, desk) + 1000
            doneUntil = checkAt + 1600
            happy = HAPPY[rnd.nextInt(HAPPY.size)]
            happyUntil = doneUntil + if (w.ok && rnd.nextInt(3) == 0) 1300 else 0
        }
        val sNow = Workers.serverNow()
        val untilNext = w?.nextRun?.let { ((it - sNow) * 1000).toLong() }
        // leave for the desk with a margin, so he's sitting down before it starts
        // (the get-up time only counts while he's in bed; without sticking to the decision he'd flip
        //  between bed and standing every frame the moment he starts getting up)
        if (w?.nextRun != null && untilNext != null && untilNext <= msTo(l, desk) + 1500) committedRun = w.nextRun
        val needDesk = w != null && (w.running || now < doneUntil || (w.nextRun != null && w.nextRun == committedRun))
        when {
            needDesk -> if (goal != "desk") { goal = "desk"; actType = null; actItem = -1; walkTo(l, desk, now, mode == Mode.SLEEP) }
            goal == "desk" || goal == "" -> pickIdle(now, l, untilNext, desk)
            goal == "act" && mode == Mode.ACT && now > actUntil -> pickIdle(now, l, untilNext, desk)
            goal == "bed" && mode == Mode.SLEEP && now > actUntil -> pickIdle(now, l, untilNext, desk)   // wakes up now and then
        }
        if (mode == Mode.WALK) {
            val t = (now - walkStart).coerceAtLeast(0) / 1000f * SPEED
            val i = floor(t).toInt()
            if (i >= path.size - 1) {
                val end = path.last(); x = end.first.toFloat(); y = end.second.toFloat()
                face = pendingFace
                modeSince = now
                when (goal) {
                    "desk" -> { mode = Mode.WORK; pose = Pose.SIT_BACK; face = 0 }
                    "bed" -> { mode = Mode.SLEEP; pose = Pose.STAND }
                    else -> {
                        mode = Mode.ACT; pose = pendingPose
                        if (pose == Pose.SIT_FRONT) face = 2
                        if (pose == Pose.SIT_BACK) face = 0
                    }
                }
            } else {
                pose = Pose.STAND
                val a = path[i]; val b = path[i + 1]; val f = t - i
                x = a.first + (b.first - a.first) * f; y = a.second + (b.second - a.second) * f
                face = when { b.first > a.first -> 1; b.first < a.first -> 3; b.second > a.second -> 2; else -> 0 }
            }
        }
    }

    /** What to do with free time: mostly sleep; sometimes (settings) one activity, only if it fits before the next task. */
    private fun pickIdle(now: Long, l: RoomLayout, untilNext: Long?, desk: Spot) {
        val cfg = st()
        val free = untilNext ?: Long.MAX_VALUE / 4
        if (rnd.nextInt(100) >= cfg.chance) { goBed(now, l); return }
        val options = ArrayList<Pair<Int, Int>>()      // (item index, weight)
        l.items.forEachIndexed { i, it ->
            val d = ITEM_DEFS[it.type] ?: return@forEachIndexed
            val wgt = when (d.act) { null, "work", "sleep" -> 0; "window", "tv", "read" -> 3; else -> 2 }
            val allowed = if (d.act == "tv") ("tv" !in cfg.off || "console" !in cfg.off) else d.act !in cfg.off
            if (wgt > 0 && allowed && useSpot(it) != null && i != actItem) options.add(i to wgt)
        }
        if (options.isEmpty()) { goBed(now, l); return }
        var roll = rnd.nextInt(options.sumOf { it.second })
        val pick = options.first { roll -= it.second; roll < 0 }.first
        val item = l.items[pick]
        val d = ITEM_DEFS[item.type]!!
        val spot = useSpot(item) ?: return goBed(now, l)
        val there = msTo(l, spot)
        val back = msTo(l, desk, spot.x to spot.y, false)
        val want = (cfg.actSec * 1000L * (0.75 + rnd.nextDouble() * 0.5)).toLong()
        val room = free - there - back - 4000                      // what's left for the activity itself
        if (room < minOf(want, 15_000L)) { goBed(now, l); return }  // not worth starting — nap instead
        if (!walkTo(l, spot, now, mode == Mode.SLEEP)) { goBed(now, l); return }
        goal = "act"; actItem = pick
        actType = if (d.act == "tv") when {
            "tv" in cfg.off -> "console"; "console" in cfg.off -> "tv"; else -> if (rnd.nextBoolean()) "console" else "tv"
        } else d.act
        val seatHere = l.items.any { s -> s.type in setOf("beanbag") && spot.x in s.x until s.x + ITEM_DEFS[s.type]!!.w && spot.y == s.y }
        pendingPose = if (actType in setOf("tv", "console")) (if (seatHere) Pose.SIT_BACK else Pose.STAND) else d.pose
        if (actType in setOf("tv", "console")) pendingFace = 0
        actUntil = now + there + minOf(want, room)
    }

    private fun goBed(now: Long, l: RoomLayout) {
        val bed = l.items.getOrNull(bedIdx)?.let { useSpot(it) } ?: return
        goal = "bed"; actType = null; actItem = -1
        actUntil = now + (st().sleepSec * 1000L * (0.7 + rnd.nextDouble() * 0.6)).toLong()
        if (tile() == (bed.x to bed.y) && mode != Mode.WALK) mode = Mode.SLEEP else walkTo(l, bed, now, false)
    }

    /** The bubble above his head right now — only for things worth saying. */
    fun bubble(now: Long, w: Worker?): Pair<String, Float>? {
        if (mode == Mode.WORK && now in checkAt until doneUntil) return (if (doneOk) "✅" else "❌") to 1f
        if (now in doneUntil until happyUntil) return (if (doneOk) happy else "😵") to 1f
        if (mode == Mode.WORK && w?.running == true && now - modeSince > 1500) return "💻" to 1f   // a long task: "…"
        if (mode == Mode.ACT && st().bubbles) {
            val set = ACT_EMOJI[actType] ?: return null
            val t = now - modeSince
            // one when he starts, then every 20 s or so
            val show = t < 2600 || (t % 20_000) in 0 until 2600
            return if (show) set[((t / 20_000) % set.size).toInt()] to 1f else null
        }
        return null
    }
}

// ───────────────────────── drawing a room ─────────────────────────

fun DrawScope.drawRoom(l: RoomLayout, s: Scene, selected: Int = -1, grid: Boolean = false) {
    val t = size.width / ROOM_N
    drawFloorAndWalls(l)
    val a = s.actor
    fun draw(i: Int) {
        val it = l.items[i]; val d = ITEM_DEFS[it.type] ?: return
        val flip = it.rot % 2 == 1 && it.type != "clock" && !d.wall
        var r: androidx.compose.ui.geometry.Rect? = null
        withTransform({ if (flip) scale(-1f, 1f, Offset((it.x + d.w / 2f) * t, 0f)) }) {
            r = drawItemArt(it, i, it.x * t, it.y * t, t, s)
        }
        if (it.type == "bed" && i == a.bedIdx && a.mode == Mode.SLEEP) r?.let { b ->
            val fr = if (flip) androidx.compose.ui.geometry.Rect(2 * (it.x + d.w / 2f) * t - b.right, b.top, 2 * (it.x + d.w / 2f) * t - b.left, b.bottom) else b
            drawSleepingHead(fr, t, a.char)
        }
        val sp = if (it.type == "desk") useSpot(it) else null
        if (sp != null && !(a.mode == Mode.WORK && Math.round(a.x) == sp.x && Math.round(a.y) == sp.y)) deskChair(sp.x.toFloat(), sp.y.toFloat(), t)
        if (i == selected) drawRect(Color(0xFFFFD54A), Offset(it.x * t, it.y * t), Size(d.w * t, d.h * t), style = Stroke(width = 3f))
    }
    // flat things, the wall decor, then furniture + the guy sorted by their bottom row
    l.items.indices.filter { ITEM_DEFS[l.items[it].type]?.let { d -> !d.blocks && !d.wall } == true }.forEach { draw(it) }
    l.items.indices.filter { ITEM_DEFS[l.items[it].type]?.wall == true }.forEach { draw(it) }
    val solid = l.items.indices.filter { ITEM_DEFS[l.items[it].type]?.blocks == true }.sortedBy { l.items[it].y + ITEM_DEFS[l.items[it].type]!!.h }
    var guyDone = false
    val guyBottom = a.y + 1
    solid.forEach { i ->
        val bottom = l.items[i].y + ITEM_DEFS[l.items[i].type]!!.h
        if (!guyDone && bottom > guyBottom + 0.01f) { guy(s, t); guyDone = true }
        draw(i)
    }
    if (!guyDone) guy(s, t)
    if (grid) for (k in 0..ROOM_N) {
        drawRect(Color(0x30FFFFFF), Offset(k * t, 0f), Size(1f, size.height)); drawRect(Color(0x30FFFFFF), Offset(0f, k * t), Size(size.width, 1f))
    }
    // lights
    if (s.dark > 0.01f) drawRect(Color(0xFF05030C).copy(alpha = s.dark), Offset.Zero, size)
    l.items.forEachIndexed { i, it ->
        val d = ITEM_DEFS[it.type] ?: return@forEachIndexed
        val cx = (it.x + d.w / 2f) * t
        when (it.type) {
            "desk" -> glow(cx, (it.y + 0.1f) * t, t * 3.2f, if (s.sick) Color(0xFFFF2040) else Pal.screen, if (s.working) 0.45f else 0.16f + s.dark * 0.2f)
            "tv" -> if (s.using(i) && a.actType in setOf("tv", "console")) glow(cx, (it.y - 0.2f) * t, t * 3f, Pal.tv, 0.42f)
            "lavalamp", "lavastand" -> glow(cx, (it.y + 0.2f) * t, t * 1.7f, Pal.lava, 0.32f + 0.06f * kotlin.math.sin(s.now / 700.0).toFloat())
            "pc" -> glow(cx, (it.y + 0.3f) * t, t * 1.3f, Color(0xFFE040FB), 0.22f)
            "window" -> glow(cx, (it.y + 1.6f) * t, t * 2.4f, Pal.moon, 0.08f + s.dark * 0.14f)
        }
    }
    // asleep: a zzz bubble over the bed, bobbing
    if (a.mode == Mode.SLEEP) l.items.getOrNull(a.bedIdx)?.let { b ->
        val bob = kotlin.math.sin(s.now / 600.0).toFloat() * t * 0.06f
        drawBubble((b.x + 1.3f) * t, (b.y - 0.2f) * t + bob, t, "💤", 0.95f)
    }
    (a.bubble(s.now, s.worker) ?: if (s.sick && a.mode != Mode.SLEEP && (s.now / 2500) % 2 == 0L) "⚠" to 1f else null)?.let { (e, al) ->
        val sitting = a.mode == Mode.WORK || a.pose == Pose.SIT_BACK || a.pose == Pose.SIT_FRONT
        val headY = when {                      // about where his head is
            a.mode == Mode.WORK -> a.y - 1.2f
            sitting -> a.y + 0.05f
            else -> a.y - 0.05f
        }
        drawBubble((a.x + 0.72f) * t, headY * t, t, e, al)
    }
    if (s.sick) drawRect(Color(0x22FF0000), Offset.Zero, size)
}

private fun DrawScope.guy(s: Scene, t: Float) {
    val a = s.actor
    if (a.mode == Mode.SLEEP) return
    when {
        a.mode == Mode.WORK -> {               // seen from behind, his head over the chair's back
            drawGuyArt(a, a.x * t, a.y * t, t, s.now, face = 0, lift = 1.22f)
            deskChair(a.x, a.y, t)
        }
        a.pose == Pose.SIT_BACK -> drawGuyArt(a, a.x * t, a.y * t, t, s.now, face = 0, lift = -0.1f, part = 0.8f)
        a.pose == Pose.SIT_FRONT -> drawGuyArt(a, a.x * t, a.y * t, t, s.now, face = 2, lift = -0.12f, part = 0.78f)
        else -> drawGuyArt(a, a.x * t, a.y * t, t, s.now)
    }
}

// ───────────────────────── one live room ─────────────────────────

/** 45 · 4:05 · 6h27 · 2d — or, after a tap, the exact time it runs (16:40). */
private fun countdownText(w: Worker?, a: Actor, exact: Boolean): String {
    w ?: return "--"
    if (w.running || a.mode == Mode.WORK) return ">>"
    val n = w.nextRun ?: return "--"
    if (exact) {
        val local = ((n - Workers.offset) * 1000).toLong()
        return java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(local))
    }
    val left = (n - Workers.serverNow()).toInt().coerceAtLeast(0)
    return when {
        left < 60 -> "$left"
        left < 3600 -> "%d:%02d".format(left / 60, left % 60)
        left < 86400 -> "%dh%02d".format(left / 3600, left % 3600 / 60)
        else -> "${left / 86400}d"
    }
}

private fun isSick(w: Worker?): Boolean {
    w ?: return false
    if (!w.ok) return true
    val e = w.every ?: return false
    val last = w.lastRun ?: return false
    return Workers.serverNow() - last > e * 3 + 15
}

@Composable
fun LiveRoom(id: String, layout: RoomLayout, modifier: Modifier = Modifier, selected: Int = -1, grid: Boolean = false,
             onTap: (() -> Unit)? = null, clockTap: Boolean = false) {
    var exactUntil by remember { mutableLongStateOf(0L) }
    val w = Workers.map[id]
    val ctx = androidx.compose.ui.platform.LocalContext.current
    remember { RoomSprites.load(ctx) }
    val actor = remember(id, layout.items) { Actor(id.hashCode()) }
    actor.cfg = layout.cfg
    actor.char = layout.style.char
    actor.look = Look(Color(layout.style.hair), Color(layout.style.skin), Color(layout.style.hoodie), Color(layout.style.logo), Color(layout.style.pants))
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val wNow by rememberUpdatedState(w)
    LaunchedEffect(id, layout) {
        while (true) withFrameMillis { now = System.currentTimeMillis(); actor.step(now, wNow, layout) }
    }
    val sick = isSick(w)
    val darkTarget = when {
        sick -> 0.1f
        actor.mode == Mode.SLEEP -> 0.56f
        actor.mode == Mode.ACT && actor.actType in setOf("tv", "console", "window", "lava") -> 0.36f
        else -> 0.05f
    }
    val dark by animateFloatAsState(darkTarget, tween(1100), label = "dark")
    val tapMod = if (!clockTap) Modifier else Modifier.pointerInput(layout) {
        detectTapGestures { o ->
            val t = size.width.toFloat() / ROOM_N
            val tx = (o.x / t).toInt(); val ty = (o.y / t).toInt()
            val onClock = layout.items.any { it.type == "clock" && tx in it.x until it.x + 2 && ty == it.y }
            if (onClock) exactUntil = if (System.currentTimeMillis() < exactUntil) 0L else System.currentTimeMillis() + 6000 else onTap?.invoke()
        }
    }
    Canvas(modifier.aspectRatio(1f).then(tapMod)) {
        drawRoom(layout, Scene(now, actor, w, dark, sick, countdownText(w, actor, now < exactUntil)), selected, grid)
    }
}

private fun agoText(sec: Double): String = when {
    sec < 60 -> "${sec.toInt()} s ago"; sec < 3600 -> "${(sec / 60).toInt()} min ago"; else -> "${(sec / 3600).toInt()} h ago"
}

/** The Agents → Rooms tab: two square rooms per row. */
@Composable
fun RoomsTab() {
    LaunchedEffect(Unit) { Workers.load(); RoomStore.load() }
    var open by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    open?.let { id -> AgentRoomDetail(id) { open = null } }
    if (Workers.order.isEmpty()) { Text("Waking the agents up…", color = Spook.Mist, modifier = Modifier.padding(20.dp)); return }
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(Workers.order.toList(), key = { it }) { id ->
            val w = Workers.map[id]
            val lay = RoomStore.get(id)
            Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Spook.Crypt2.copy(alpha = 0.8f)).clickable { open = id }.padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LiveRoom(id, lay, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), onTap = { open = id }, clockTap = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (isSick(w)) Color(0xFFFF4D5E) else if (w?.running == true) Color(0xFF40D8FF) else Color(0xFF5BD44A)))
                    Spacer(Modifier.width(6.dp))
                    Text("${w?.emoji ?: "🤖"} ${roomTitle(lay, w, id)} · ${lay.style.name}", color = Spook.Bone, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val sub = buildList {
                    if (w?.every != null) add("every ${w.every.toInt()} s") else add("after each reply")
                    w?.lastMs?.let { add("$it ms") }
                    w?.lastRun?.let { add(agoText(Workers.serverNow() - it)) }
                }
                Text(sub.joinToString(" · "), color = Spook.Mist, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AgentRoomDetail(id: String, onClose: () -> Unit) {
    val w = Workers.map[id]
    val lay = RoomStore.get(id)
    var tab by remember { mutableIntStateOf(0) }
    Page("${w?.emoji ?: "🤖"} ${roomTitle(lay, w, id)}", onClose) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("🏠 Room", "⚙ Settings", "✎ Edit").forEachIndexed { i, label -> Pill(label, tab == i) { tab = i } }
            }
            when (tab) {
                0 -> RoomInfo(id)
                1 -> RoomSettingsBody(id)
                else -> RoomEditorBody(id) { tab = 0 }
            }
        }
    }
}

@Composable
private fun RoomInfo(id: String) {
    val w = Workers.map[id]
    val lay = RoomStore.get(id)
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.widthIn(max = 520.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
            LiveRoom(id, lay, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), clockTap = true)
        }
        Text("${lay.style.name} lives here · ${w?.name ?: id}", color = Spook.Mist, fontSize = 12.sp)
        Text(w?.desc ?: "", color = Spook.Bone, fontSize = 14.sp, lineHeight = 19.sp)
        key(tick) {
            val facts = buildList {
                add("Runs" to (w?.every?.let { "every ${it.toInt()} s" } ?: "on its own schedule"))
                w?.nextRun?.let { add("Next run" to java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(((it - Workers.offset) * 1000).toLong())) +
                    " (in ${(it - Workers.serverNow()).toInt().coerceAtLeast(0)} s)") }
                w?.lastRun?.let { add("Last run" to agoText(Workers.serverNow() - it)) }
                w?.lastMs?.let { add("Took" to "$it ms" + (w.avgMs?.let { a -> " (usually $a ms)" } ?: "")) }
                add("Status" to (if (isSick(w)) "⚠ needs attention" else "✓ alive and well"))
                w?.note?.takeIf { it.isNotBlank() }?.let { add("Last result" to it) }
            }
            facts.forEach { (k, v) -> Row { Text(k, color = Spook.Mist, fontSize = 13.sp, modifier = Modifier.width(110.dp)); Text(v, color = Spook.Bone, fontSize = 13.sp) } }
        }
    }
}

// ───────────────────────── editor ─────────────────────────

private fun validSpot(l: RoomLayout, it: RoomItem, ignore: Int = -1): Boolean {
    val d = ITEM_DEFS[it.type] ?: return false
    if (it.x < 0 || it.x + d.w > ROOM_N || it.y < 0 || it.y + d.h > ROOM_N) return false
    if (d.wall && !d.anywhere && it.y + d.h > ROOM_WALL) return false
    if (!d.wall && !d.anywhere && it.y < ROOM_WALL) return false
    if (d.anywhere && it.y < ROOM_WALL && it.y + d.h > ROOM_WALL) return false
    if (!d.blocks) return true
    l.items.forEachIndexed { i, o ->
        if (i == ignore) return@forEachIndexed
        val od = ITEM_DEFS[o.type] ?: return@forEachIndexed
        if (!od.blocks) return@forEachIndexed
        if (it.x < o.x + od.w && o.x < it.x + d.w && it.y < o.y + od.h && o.y < it.y + d.h) return false
    }
    return true
}

private fun itemAt(l: RoomLayout, tx: Int, ty: Int): Int {
    val hits = l.items.withIndex().filter { (_, it) -> val d = ITEM_DEFS[it.type]!!; tx in it.x until it.x + d.w && ty in it.y until it.y + d.h }
    return (hits.lastOrNull { ITEM_DEFS[it.value.type]!!.blocks } ?: hits.lastOrNull())?.index ?: -1
}

@Composable
private fun RoomEditorBody(id: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var layout by remember { mutableStateOf(RoomStore.get(id)) }
    var sel by remember { mutableIntStateOf(-1) }
    var msg by remember { mutableStateOf("") }
    var boxPx by remember { mutableFloatStateOf(1f) }
    val layoutNow by rememberUpdatedState(layout)
    fun reachable(l: RoomLayout): Boolean {
        val b = l.items.firstOrNull { it.type == "bed" }?.let { useSpot(it) } ?: return false
        val d = l.items.firstOrNull { it.type == "desk" }?.let { useSpot(it) } ?: return false
        return findPath(l, b.x to b.y, d.x to d.y) != null
    }
    fun update(i: Int, it: RoomItem) {
        if (validSpot(layout, it, ignore = i)) { layout = layout.copy(items = layout.items.toMutableList().also { l -> l[i] = it }); msg = "" }
        else msg = "Can't go there"
    }
    run {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tap an item to select it, drag to move it. With nothing selected you edit the room and ${layout.style.name}.",
                color = Spook.Mist, fontSize = 12.sp)
            Box(Modifier.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.CenterHorizontally).aspectRatio(1f)
                .onSizeChanged { boxPx = it.width.toFloat() }
                .pointerInput(Unit) {
                    detectTapGestures { o -> val t = boxPx / ROOM_N; sel = itemAt(layoutNow, (o.x / t).toInt(), (o.y / t).toInt()) }
                }
                .pointerInput(Unit) {
                    var start = Offset.Zero; var from = 0 to 0; var acc = Offset.Zero
                    detectDragGestures(onDragStart = { o ->
                        val t = boxPx / ROOM_N
                        val hit = itemAt(layoutNow, (o.x / t).toInt(), (o.y / t).toInt()); if (hit >= 0) sel = hit
                        start = o; acc = Offset.Zero
                        layoutNow.items.getOrNull(sel)?.let { from = it.x to it.y }
                    }) { change, drag ->
                        change.consume()
                        val i = sel
                        if (i < 0 || i >= layoutNow.items.size) return@detectDragGestures
                        val t = boxPx / ROOM_N
                        acc += drag
                        val cur = layoutNow.items[i]
                        val nx = from.first + Math.round(acc.x / t); val ny = from.second + Math.round(acc.y / t)
                        if (nx != cur.x || ny != cur.y) {
                            val moved = cur.copy(x = nx, y = ny)
                            if (validSpot(layoutNow, moved, ignore = i)) { layout = layout.copy(items = layout.items.toMutableList().also { l -> l[i] = moved }); msg = "" }
                        }
                    }
                }) {
                LiveRoom(id, layout, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).border(1.dp, Spook.Crypt3, RoundedCornerShape(10.dp)), selected = sel, grid = true)
            }
            if (msg.isNotBlank()) Text(msg, color = Color(0xFFFF8A65), fontSize = 12.sp)
            if (!reachable(layout)) Text("⚠ ${layout.style.name} can't reach the desk from the bed — clear a path.", color = Color(0xFFFFD54A), fontSize = 12.sp)

            val s = sel.takeIf { it in layout.items.indices }
            if (s != null) {
                val it = layout.items[s]
                val d = ITEM_DEFS[it.type]!!
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.label, color = Spook.Pumpkin, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (!d.wall) Pill("⇋ Flip", false) { update(s, it.copy(rot = 1 - it.rot % 2)) }
                    VARIANTS[it.type]?.let { n -> Pill("✦ Design ${it.variant % n + 1}/$n", false) { update(s, it.copy(variant = (it.variant + 1) % n)) } }
                    Pill("🗑 Remove", false, color = Spook.Blood) {
                        if ((it.type == "bed" || it.type == "desk") && layout.items.count { o -> o.type == it.type } == 1) msg = "${layout.style.name} needs at least one ${d.label.lowercase()}"
                        else { layout = layout.copy(items = layout.items.filterIndexed { k, _ -> k != s }); sel = -1 }
                    }
                    Pill("Done", false) { sel = -1 }
                }
                d.act?.takeIf { a -> a != "work" && a != "sleep" }?.let { a ->
                    Text("${layout.style.name} uses it when idle: ${(ACT_EMOJI[a] ?: emptyList()).joinToString(" ")}" + if (a == "tv") " · 🎮 🔥 🏆" else "", color = Spook.Mist, fontSize = 12.sp)
                }
            } else {
                Text("Character", color = Spook.Pumpkin, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CHARACTERS.forEach { (cid, label) -> Pill(label, layout.style.char == cid) { layout = layout.copy(style = layout.style.copy(char = cid)) } }
                }
                Text("Names", color = Spook.Pumpkin, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Field(layout.style.title, "Room name (empty = ${Workers.map[id]?.name ?: id})", Modifier.fillMaxWidth()) { n ->
                    layout = layout.copy(style = layout.style.copy(title = n.take(30))) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(layout.style.name, "Character name", Modifier.weight(1f)) { n -> layout = layout.copy(style = layout.style.copy(name = n.take(16))) }
                    Pill("🎲", false) { layout = layout.copy(style = layout.style.copy(name = NAMES.random())) }
                }
            }

            Text("Add", color = Spook.Pumpkin, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ITEM_DEFS.values.forEach { d ->
                    Column(Modifier.width(78.dp).clip(RoundedCornerShape(10.dp)).background(Spook.Crypt2).clickable {
                        val cands = (0 until ROOM_N).flatMap { y -> (0 until ROOM_N).map { x -> RoomItem(d.type, x, y) } }.sortedBy { abs(it.x - 5) + abs(it.y - 6) }
                        val ok = cands.firstOrNull { validSpot(layout, it) }
                        if (ok == null) msg = "No room for a ${d.label.lowercase()}" else { layout = layout.copy(items = layout.items + ok); sel = layout.items.lastIndex; msg = "" }
                    }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val demo = remember { Actor(0).also { a -> a.mode = Mode.STAND } }
                        Canvas(Modifier.size(56.dp)) {
                            val span = maxOf(d.w, d.h + 2).toFloat()
                            val t = size.width / span
                            drawItemArt(RoomItem(d.type, 0, 0), -1, (span - d.w) * t / 2f, (span - d.h) * t / 2f + if (d.wall) 0f else t * 0.8f, t,
                                Scene(System.currentTimeMillis(), demo, null, 0f, false, "29"))
                        }
                        Text(d.label, color = Spook.Bone, fontSize = 10.5.sp, maxLines = 1)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Save", color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(Spook.Pumpkin)
                    .clickable {
                        if (!reachable(layout)) { msg = "Clear a path from the bed to the desk first"; return@clickable }
                        scope.launch { RoomStore.save(id, layout.copy(settings = RoomStore.get(id).settings)); onClose() }
                    }.padding(horizontal = 18.dp, vertical = 9.dp))
                Text("Reset to default", color = Spook.Mist, modifier = Modifier.clip(RoundedCornerShape(50)).background(Spook.Crypt3)
                    .clickable { layout = defaultLayout(id).let { d -> d.copy(style = d.style.copy(name = layout.style.name, title = layout.style.title)) }; sel = -1 }.padding(horizontal = 14.dp, vertical = 9.dp))
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** How this room's guy spends his free time. */
@Composable
private fun RoomSettingsBody(id: String) {
    val scope = rememberCoroutineScope()
    var c by remember(id) { mutableStateOf(RoomStore.get(id).cfg) }
    fun set(n: RoomSettings) { c = n; scope.launch { RoomStore.saveSettings(id, n) } }
    @Composable
    fun SliderRow(title: String, hint: String, value: Int, range: IntRange, step: Int, unit: String, onSet: (Int) -> Unit) {
        var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Spook.Crypt2.copy(alpha = 0.6f)).padding(10.dp)) {
            Row { Text(title, color = Spook.Bone, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text("${v.toInt()}$unit", color = Spook.Pumpkin, fontSize = 14.sp) }
            Text(hint, color = Spook.Mist, fontSize = 12.sp)
            Slider(v, { v = (Math.round(it / step) * step).toFloat() }, valueRange = range.first.toFloat()..range.last.toFloat(),
                onValueChangeFinished = { onSet(v.toInt()) },
                colors = SliderDefaults.colors(thumbColor = Spook.Pumpkin, activeTrackColor = Spook.Pumpkin))
        }
    }
    run {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Only for this room. ${RoomStore.get(id).style.name} always gets to the desk in time for the task — an activity only starts if it fits before it.",
                color = Spook.Mist, fontSize = 12.sp)
            SliderRow("How often they do something", "Each time one wakes up or finishes a task, this is the chance he gets up to do something instead of sleeping.",
                c.chance, 0..100, 5, " %") { set(c.copy(chance = it)) }
            SliderRow("How long an activity lasts", "Give or take a quarter.", c.actSec, 10..300, 5, " s") { set(c.copy(actSec = it)) }
            SliderRow("Nap length", "How long he sleeps before thinking about doing something again.", c.sleepSec, 20..600, 10, " s") { set(c.copy(sleepSec = it)) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Spook.Crypt2.copy(alpha = 0.6f)).clickable { set(c.copy(bubbles = !c.bubbles)) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Thought bubbles during activities", color = Spook.Bone, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("One when he starts, then about every 20 s. ✅ / ❌ after a task always show.", color = Spook.Mist, fontSize = 12.sp)
                }
                androidx.compose.material3.Switch(c.bubbles, { set(c.copy(bubbles = it)) })
            }
            Text("What they can do", color = Spook.Pumpkin, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Only things that are in the room get used.", color = Spook.Mist, fontSize = 12.sp)
            ACTIVITIES.forEach { (act, label) ->
                val on = act !in c.off
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Spook.Crypt2.copy(alpha = 0.6f))
                    .clickable { set(c.copy(off = if (on) c.off + act else c.off - act)) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = Spook.Bone, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(on, { set(c.copy(off = if (on) c.off + act else c.off - act)) })
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}
